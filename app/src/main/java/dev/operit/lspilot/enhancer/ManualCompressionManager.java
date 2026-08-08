package dev.operit.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Coordinates chat-scoped manual compression without mutating host state. */
final class ManualCompressionManager {
    interface Callback {
        void onComplete(Result result);
    }

    static final class ScreenState {
        final String chatId;
        final Object uiState;
        final int messageCount;
        final boolean loading;
        final String providerSignature;

        ScreenState(String chatId, Object uiState, int messageCount, boolean loading,
                String providerSignature) {
            this.chatId = chatId;
            this.uiState = uiState;
            this.messageCount = messageCount;
            this.loading = loading;
            this.providerSignature = providerSignature;
        }
    }

    static final class CompressionMetrics {
        final int originalMessages;
        final int compactedMessages;
        final int originalChars;
        final int compactedChars;
        final int originalBytes;
        final int compactedBytes;
        final int originalApproxTokens;
        final int compactedApproxTokens;
        final long durationMs;
        final int chunks;

        CompressionMetrics(int originalMessages, int compactedMessages, int originalChars,
                int compactedChars, int originalBytes, int compactedBytes,
                long durationMs, int chunks) {
            this.originalMessages = originalMessages;
            this.compactedMessages = compactedMessages;
            this.originalChars = originalChars;
            this.compactedChars = compactedChars;
            this.originalBytes = originalBytes;
            this.compactedBytes = compactedBytes;
            this.originalApproxTokens = approxTokens(originalChars, originalBytes);
            this.compactedApproxTokens = approxTokens(compactedChars, compactedBytes);
            this.durationMs = durationMs;
            this.chunks = chunks;
        }

        static CompressionMetrics measure(JSONArray before, JSONArray after,
                long durationMs, int chunks) {
            int[] beforeSize = size(before);
            int[] afterSize = size(after);
            return new CompressionMetrics(before == null ? 0 : before.length(),
                    after == null ? 0 : after.length(), beforeSize[0], afterSize[0],
                    beforeSize[1], afterSize[1], durationMs, chunks);
        }

        private static int[] size(JSONArray messages) {
            if (messages == null) return new int[]{0, 0};
            String text = messages.toString();
            return new int[]{text.length(), text.getBytes(StandardCharsets.UTF_8).length};
        }

        private static int approxTokens(int chars, int bytes) {
            return Math.max(0, Math.round((chars + bytes / 3f) / 3f));
        }

        int ratioPercent() {
            return originalChars <= 0 ? 0 : Math.round(compactedChars * 100f / originalChars);
        }

        String describe() {
            return "messages=" + originalMessages + "->" + compactedMessages
                    + " chars=" + originalChars + "->" + compactedChars
                    + " bytes=" + originalBytes + "->" + compactedBytes
                    + " approxTokens=" + originalApproxTokens + "->" + compactedApproxTokens
                    + " ratio=" + ratioPercent() + "% durationMs=" + durationMs
                    + " chunks=" + chunks;
        }
    }
static final class Result {
        final boolean success;
        final String message;
        final int originalCount;
        final int compactedCount;
        final CompressionMetrics metrics;

        Result(boolean success, String message, int originalCount, int compactedCount) {
            this(success, message, originalCount, compactedCount, null);
        }

        Result(boolean success, String message, int originalCount, int compactedCount,
                CompressionMetrics metrics) {
            this.success = success;
            this.message = message;
            this.originalCount = originalCount;
            this.compactedCount = compactedCount;
            this.metrics = metrics;
        }
    }



    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LSPilotManualCompression");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService STATUS_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LSPilotCompressionStatus");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicLong STATUS_SEQUENCE = new AtomicLong();
    private static final ThreadLocal<Boolean> INTERNAL_BUILD = new ThreadLocal<>();

    private static final String ENHANCER_MARKER = "[系统提示 · 上下文压缩]";
    private static final String ENHANCER_ROLE = "system";
    private static final String TAG = "LSPilotEnhancer";
    private static volatile Method buildRequestMethod;
    private static volatile Object providerManager;
    private static volatile Method getProviderMethod;
    private static volatile ClassLoader hostLoader;
    private static volatile Object repositoryInstance;
    private static volatile Method repositoryAddMessage;
    private static volatile Constructor<?> hostMessageConstructor;
    private static volatile Object viewModelInstance;
    private static volatile Method viewModelLoadSession;
    private static volatile String viewModelPackageName;
    private static volatile Object viewModelContext;
    private static volatile long compressionStartedAt;
    private static volatile CompressionMetrics lastMetrics;
    private static volatile boolean compressionUsedForPendingRequest;
    private static volatile int lastProgressCompleted;
    private static volatile int lastProgressTotal;


    private static volatile ScreenState currentScreen;
    private static volatile Prepared prepared;
    private static volatile boolean preparing;
    private static volatile Result lastResult;
    private static volatile int lastKeepRecent;
    private static volatile boolean preparedUsedForActiveResponse;
    private static volatile int preparedApplyCount;

    static Result getLastResult() {
        return lastResult;
    }

    static int getLastKeepRecent() {
        return lastKeepRecent;
    }

    private ManualCompressionManager() {
    }

    static void configure(ClassLoader loader, Method buildRequest) throws Exception {
        hostLoader = loader;
        buildRequestMethod = buildRequest;
        DebugLogger.i("compression manager configured; buildMethod="
                + buildRequest.getDeclaringClass().getName() + "#" + buildRequest.getName());
        Class<?> managerClass = Class.forName(
                "me.yun.lspilot.data.provider.ProviderManager", false, loader);
        Field instanceField = managerClass.getField("INSTANCE");
        providerManager = instanceField.get(null);
        getProviderMethod = managerClass.getMethod("getProvider", String.class);
        configureHostMessageBridge(loader);
    }

    private static void configureHostMessageBridge(ClassLoader loader) {
        try {
            Class<?> repositoryClass = Class.forName(
                    "me.yun.lspilot.data.repository.AiChatRepository", false, loader);
            repositoryInstance = repositoryClass.getField("INSTANCE").get(null);
            Class<?> messageClass = Class.forName(
                    "me.yun.lspilot.data.model.AiChatMessage", false, loader);
            repositoryAddMessage = repositoryClass.getMethod("addMessage", String.class, messageClass);
            hostMessageConstructor = messageClass.getConstructor(
                    String.class, String.class, String.class, boolean.class, String.class,
                    long.class, long.class, long.class, int.class, List.class, int.class,
                    List.class, List.class, String.class, List.class, boolean.class,
                    int.class, int.class, int.class, int.class, long.class, int.class,
                    int.class, long.class, int.class);
            DebugLogger.i("host message bridge ready: AiChatRepository.addMessage");
        } catch (Throwable error) {
            repositoryInstance = null;
            repositoryAddMessage = null;
            hostMessageConstructor = null;
            DebugLogger.e("host message bridge unavailable; chat status messages disabled", error);
        }
    }

    private static void postStatus(String chatId, String content) {
        if (chatId == null || content == null || repositoryAddMessage == null
                || hostMessageConstructor == null || repositoryInstance == null) return;
        STATUS_EXECUTOR.execute(() -> {
            try {
                Object message = hostMessageConstructor.newInstance(
                        "lspilot-enhancer-" + STATUS_SEQUENCE.incrementAndGet(), ENHANCER_ROLE,
                        ENHANCER_MARKER + "\n" + content,
                        false, "", 0L, 0L, System.currentTimeMillis(), 0,
                        Collections.emptyList(), 0, Collections.emptyList(),
                        Collections.emptyList(), "", Collections.singletonList("content"),
                        false, 0, 0, 0, 0, 0L, 0, 0, 0L, 0);
                repositoryAddMessage.invoke(repositoryInstance, chatId, message);
                DebugLogger.i("chat status inserted chat=" + DebugLogger.id(chatId)
                        + " chars=" + content.length());
                refreshChat(chatId);
            } catch (Throwable error) {
                DebugLogger.e("chat status insertion failed chat=" + DebugLogger.id(chatId), error);
            }
        });
    }

    static void postCompressionStatus(String content) {
        ScreenState screen = currentScreen;
        if (screen != null) postStatus(screen.chatId, content);
    }

    static void onProviderUsage(long inputTokens, long outputTokens, long cachedTokens,
            long totalTokens) {
        CompressionMetrics metrics = lastMetrics;
        boolean used = compressionUsedForPendingRequest;
        compressionUsedForPendingRequest = false;
        String chatId = currentScreen == null ? "none" : DebugLogger.id(currentScreen.chatId);
        DebugLogger.i("provider usage observed chat=" + chatId
                + " inputTokens=" + display(inputTokens)
                + " outputTokens=" + display(outputTokens)
                + " totalTokens=" + display(totalTokens)
                + " cachedTokens=" + display(cachedTokens)
                + " compressionUsed=" + used
                + " compressionPrepared=" + (metrics != null));
        if (used && metrics != null) {
            postCompressionStatus("Provider 已返回本次请求用量，确认压缩上下文已参与请求："
                    + "输入 token " + display(inputTokens) + "，总 token "
                    + display(totalTokens) + "，缓存 token " + display(cachedTokens) + "。"
                    + "压缩上下文估算 token " + metrics.compactedApproxTokens + "。");
        }
    }

    private static String display(long value) {
        return value < 0L ? "不可用" : Long.toString(value);
    }

    static void recordAutomaticCompression(JSONArray before, JSONArray after) {
        if (before == null || after == null || before == after) return;
        CompressionMetrics metrics = CompressionMetrics.measure(before, after, 0L, 0);
        lastMetrics = metrics;
        compressionUsedForPendingRequest = true;
        DebugLogger.i("automatic compression applied chat="
                + (currentScreen == null ? "none" : DebugLogger.id(currentScreen.chatId))
                + " " + metrics.describe());
        postCompressionStatus("自动压缩已应用：消息 " + metrics.originalMessages + " -> "
                + metrics.compactedMessages + "；上下文字符 " + metrics.originalChars + " -> "
                + metrics.compactedChars + "；估算 token " + metrics.originalApproxTokens + " -> "
                + metrics.compactedApproxTokens + "；压缩率 " + metrics.ratioPercent() + "%。"
                + " 本次请求已使用压缩上下文。");
    }

    private static boolean isEnhancerStatus(Object message) {
        return message instanceof JSONObject
                && ((JSONObject) message).optString("role").equals(ENHANCER_ROLE)
                && ((JSONObject) message).optString("content").startsWith(ENHANCER_MARKER);
    }

    private static JSONArray withoutEnhancerStatuses(JSONArray messages) throws Exception {
        JSONArray result = new JSONArray();
        if (messages == null) return result;
        for (int index = 0; index < messages.length(); index++) {
            Object item = messages.get(index);
            if (!isEnhancerStatus(item)) result.put(item);
        }
        return result;
    }
    static void captureViewModel(Object viewModel, String packageName, Object context) {
        if (viewModel == null || packageName == null || context == null) return;
        try {
            Class<?> viewModelClass = viewModel.getClass();
            viewModelInstance = viewModel;
            viewModelPackageName = packageName;
            viewModelContext = context;
            viewModelLoadSession = viewModelClass.getMethod(
                    "loadSession", String.class, String.class,
                    Class.forName("android.content.Context", false, hostLoader));
            DebugLogger.i("chat ViewModel refresh bridge ready class=" + viewModelClass.getName());
        } catch (Throwable error) {
            DebugLogger.e("chat ViewModel refresh bridge unavailable", error);
        }
    }

    private static void refreshChat(String chatId) {
        Object viewModel = viewModelInstance;
        Method loadSession = viewModelLoadSession;
        String packageName = viewModelPackageName;
        Object context = viewModelContext;
        if (viewModel == null || loadSession == null || packageName == null || context == null) return;
        try {
            loadSession.invoke(viewModel, packageName, chatId, context);
            DebugLogger.i("chat UI refresh requested chat=" + DebugLogger.id(chatId));
        } catch (Throwable error) {
            DebugLogger.e("chat UI refresh failed chat=" + DebugLogger.id(chatId), error);
        }
    }

    static JSONArray sanitizeRequestMessages(JSONArray messages) {
        if (messages == null) return null;
        try {
            JSONArray clean = withoutEnhancerStatuses(messages);
            return clean.length() == messages.length() ? messages : clean;
        } catch (Throwable error) {
            DebugLogger.e("request message sanitization failed", error);
            return messages;
        }
    }

    static boolean isInternalBuild() {
        return Boolean.TRUE.equals(INTERNAL_BUILD.get());
    }

    static void enterChat(String chatId) {
        if (chatId == null) {
            return;
        }
        ScreenState previous = currentScreen;
        if (previous != null && !chatId.equals(previous.chatId)) {
            currentScreen = null;
            resetPreparedState(true);
        }
    }

    static void updateScreen(String chatId, Object uiState) {
        if (chatId == null || uiState == null) {
            return;
        }
        try {
            List<?> messages = (List<?>) invoke(uiState, "getMessages");
            int effectiveMessageCount = countHostMessages(messages);
            boolean loading = Boolean.TRUE.equals(invoke(uiState, "isLoading"));
            ScreenState previous = currentScreen;
            if (previous != null && previous.loading && !loading
                    && preparedUsedForActiveResponse) {
                DebugLogger.i("prepared context cleared after response finished applyCount="
                        + preparedApplyCount);
                resetPreparedState(true);
            }
            Object config = invoke(uiState, "getSelectedProvider");
            String signature = config == null ? "unknown" : providerSignature(config);
            currentScreen = new ScreenState(chatId, uiState,
                    effectiveMessageCount, loading, signature);
            if (ModuleSettings.isVerboseDebugLogEnabled()) {
                DebugLogger.d("screen update chat=" + DebugLogger.id(chatId)
                        + " messages=" + (messages == null ? 0 : messages.size())
                        + " loading=" + loading + " provider=" + DebugLogger.redact(signature));
            }
            if (previous != null && previous.loading != loading && loading && preparing) {
                resetPreparedState(true);
                DebugLogger.i("in-flight compression invalidated: chat became loading");
            }
            if (previous != null && (!chatId.equals(previous.chatId)
                    || !signature.equals(previous.providerSignature))) {
                resetPreparedState(true);
            }
        } catch (Throwable ignored) {
            currentScreen = new ScreenState(chatId, uiState, 0, false, "unknown");
        }
    }

    static ScreenState getCurrentScreen() {
        return currentScreen;
    }

    static boolean isPreparing() {
        return preparing;
    }

    static boolean hasPreparedForCurrentChat() {
        ScreenState screen = currentScreen;
        Prepared value = prepared;
        return screen != null && value != null && screen.chatId.equals(value.chatId);
    }

    static boolean shouldAutoCompressBeforeSend() {
        ScreenState screen = currentScreen;
        return ModuleSettings.isEnabled()
                && ModuleSettings.isContextCompressionEnabled()
                && screen != null
                && !screen.loading
                && !preparing
                && !hasPreparedForCurrentChat()
                && screen.messageCount > ModuleSettings.getManualKeepRecent();
    }

    static void clearPrepared() {
        resetPreparedState(true);
    }

    private static void resetPreparedState(boolean bumpGeneration) {
        prepared = null;
        preparedUsedForActiveResponse = false;
        preparedApplyCount = 0;
        lastMetrics = null;
        compressionUsedForPendingRequest = false;
        if (bumpGeneration) {
            GENERATION.incrementAndGet();
        }
    }

    static void prepareCurrent(int keepRecent, Callback callback) {
        lastKeepRecent = keepRecent;
        lastResult = null;
        ScreenState screen = currentScreen;
        DebugLogger.i("compression start chat=" + (screen == null ? "none" : DebugLogger.id(screen.chatId))
                + " messages=" + (screen == null ? 0 : screen.messageCount)
                + " keepRecent=" + keepRecent
                + " loading=" + (screen != null && screen.loading));
        if (screen == null) {
            finish(callback, new Result(false, "当前没有可用的对话", 0, 0));
            return;
        }
        if (preparing) {
            finish(callback, new Result(false, "压缩任务正在运行", screen.messageCount, 0));
            return;
        }
        if (screen.loading) {
            finish(callback, new Result(false, "请等待当前回复结束后再压缩",
                    screen.messageCount, 0));
            return;
        }
        if (screen.messageCount <= keepRecent) {
            finish(callback, new Result(false,
                    "消息数量不足，需要多于 " + keepRecent + " 条",
                    screen.messageCount, screen.messageCount));
            return;
        }

        long generation = GENERATION.incrementAndGet();
        preparing = true;
        lastProgressCompleted = 0;
        lastProgressTotal = 0;
        compressionStartedAt = System.currentTimeMillis();
        postStatus(screen.chatId, "压缩开始：正在读取当前对话，原始消息 "
                + screen.messageCount + " 条，保留最近 " + keepRecent + " 条。\n"
                + "上下文长度统计将在每个摘要分块完成后更新。");
        EXECUTOR.execute(() -> {
            Result result;
            try {
                DebugLogger.d("compression snapshot begin chat=" + DebugLogger.id(screen.chatId));
                Snapshot snapshot = snapshot(screen);
                DebugLogger.d("compression snapshot ready messages=" + snapshot.messages.length()
                        + " provider=" + DebugLogger.redact(snapshot.providerSignature));
                postStatus(screen.chatId, "压缩进度：快照完成，输入消息 " + snapshot.messages.length()
                        + " 条，字符 " + charLength(snapshot.messages) + "，估算 token "
                        + approxTokenLength(snapshot.messages) + "。");
                JSONArray compacted = ContextCompression.compact(
                        snapshot.messages, snapshot.config, true, keepRecent,
                        (completed, total) -> {
                            lastProgressCompleted = completed;
                            lastProgressTotal = total;
                            String progress = "压缩进度：摘要分块 " + completed + "/" + total
                                    + " 已完成。当前已耗时 "
                                    + (System.currentTimeMillis() - compressionStartedAt) + " ms。";
                            DebugLogger.i("compression progress chat=" + DebugLogger.id(screen.chatId)
                                    + " chunk=" + completed + "/" + total);
                            postStatus(screen.chatId, progress);
                        });
                if (compacted == snapshot.messages
                        || compacted.length() >= snapshot.messages.length()) {
                    throw new IllegalStateException("没有可压缩的完整历史轮次");
                }
                if (generation != GENERATION.get()) {
                    throw new IllegalStateException("对话已切换，压缩结果已丢弃");
                }
                prepared = new Prepared(screen.chatId, snapshot.providerSignature,
                        snapshot.messages, compacted);
                preparedUsedForActiveResponse = false;
                preparedApplyCount = 0;
                CompressionMetrics metrics = CompressionMetrics.measure(snapshot.messages, compacted,
                        System.currentTimeMillis() - compressionStartedAt, 0);
                lastMetrics = metrics;
                postStatus(screen.chatId, "压缩完成：消息 " + metrics.originalMessages + " -> "
                        + metrics.compactedMessages + "；字符 " + metrics.originalChars + " -> "
                        + metrics.compactedChars + "；UTF-8 字节 " + metrics.originalBytes + " -> "
                        + metrics.compactedBytes + "；估算 token " + metrics.originalApproxTokens
                        + " -> " + metrics.compactedApproxTokens + "；压缩率 " + metrics.ratioPercent()
                        + "%；耗时 " + metrics.durationMs + " ms。摘要已准备，将在下一次发送使用。");
                result = new Result(true,
                        "摘要已就绪，将用于下一次发送。原始 "
                                + snapshot.messages.length() + " 条，压缩后 "
                                + compacted.length() + " 条，保留最近 " + keepRecent + " 条。",
                        snapshot.messages.length(), compacted.length(), metrics);
                DebugLogger.i("compression success chat=" + DebugLogger.id(screen.chatId)
                        + " " + metrics.describe());
            } catch (Throwable error) {
                DebugLogger.e("compression failed chat=" + DebugLogger.id(screen.chatId)
                        + " messages=" + screen.messageCount, error);
                postStatus(screen.chatId, "压缩失败：" + readableError(error)
                        + "。原始消息 " + screen.messageCount + " 条，已耗时 "
                        + (System.currentTimeMillis() - compressionStartedAt) + " ms。");
                result = new Result(false, readableError(error), screen.messageCount, 0);
            } finally {
                preparing = false;
            }
            finish(callback, result);
        });
    }

    private static void finish(Callback callback, Result result) {
        lastResult = result;
        DebugLogger.i("compression result success=" + result.success
                + " original=" + result.originalCount + " compacted=" + result.compactedCount
                + " message=" + DebugLogger.redact(result.message));
        callback.onComplete(result);
    }

    private static JSONArray stripEnhancerStatuses(JSONArray messages) throws Exception {
        return withoutEnhancerStatuses(messages);
    }

    static JSONArray applyPrepared(JSONArray actualMessages, Object config) {
        Prepared value = prepared;
        if (value == null || actualMessages == null || config == null) {
            return null;
        }
        try {
            JSONArray cleanActual = stripEnhancerStatuses(actualMessages);
            if (!value.providerSignature.equals(providerSignature(config))) {
                resetPreparedState(true);
                return null;
            }
            int actualSystemCount = countLeadingSystemMessages(cleanActual);
            int sourceSystemCount = countLeadingSystemMessages(value.source);
            int sourceHistoryCount = value.source.length() - sourceSystemCount;
            if (cleanActual.length() - actualSystemCount < sourceHistoryCount) {
                DebugLogger.w("prepared context rejected: actual history shorter than source");
                resetPreparedState(true);
                return null;
            }
            for (int index = 0; index < sourceHistoryCount; index++) {
                Object sourceItem = value.source.get(sourceSystemCount + index);
                Object actualItem = cleanActual.get(actualSystemCount + index);
                if (!String.valueOf(sourceItem).equals(String.valueOf(actualItem))) {
                    DebugLogger.w("prepared context rejected: chat changed before send");
                    resetPreparedState(true);
                    return null;
                }
            }

            JSONArray result = new JSONArray();
            for (int index = 0; index < actualSystemCount; index++) {
                result.put(cleanActual.get(index));
            }
            int compactedSystemCount = countLeadingSystemMessages(value.compacted);
            for (int index = compactedSystemCount; index < value.compacted.length(); index++) {
                result.put(value.compacted.get(index));
            }
            int tailStart = actualSystemCount + sourceHistoryCount;
            for (int index = tailStart; index < cleanActual.length(); index++) {
                result.put(cleanActual.get(index));
            }
            preparedUsedForActiveResponse = true;
            int applyIndex = ++preparedApplyCount;
            compressionUsedForPendingRequest = true;
            CompressionMetrics metrics = CompressionMetrics.measure(cleanActual, result, 0L, 0);
            lastMetrics = metrics;
            DebugLogger.i("manual prepared context applied chat=" + DebugLogger.id(value.chatId)
                    + " applyCount=" + applyIndex + " " + metrics.describe());
            if (applyIndex == 1) {
                postStatus(value.chatId, "本轮请求已使用压缩上下文：消息 " + metrics.originalMessages
                        + " -> " + metrics.compactedMessages + "；字符 " + metrics.originalChars + " -> "
                        + metrics.compactedChars + "；估算 token " + metrics.originalApproxTokens + " -> "
                        + metrics.compactedApproxTokens + "；压缩率 " + metrics.ratioPercent()
                        + "%。本轮回复结束前，后续工具/阶段请求会继续复用该压缩上下文。");
            }
            return result;
        } catch (Throwable error) {
            DebugLogger.e("prepared context apply failed", error);
            resetPreparedState(true);
            return null;
        }
    }

    private static Snapshot snapshot(ScreenState screen) throws Exception {
        Object uiState = screen.uiState;
        Object config = invoke(uiState, "getSelectedProvider");
        if (config == null) {
            throw new IllegalStateException("当前对话没有选择 Provider");
        }
        Object selectedModel = invoke(uiState, "getSelectedModel");
        if (selectedModel instanceof String && !((String) selectedModel).trim().isEmpty()) {
            Method copyDefault = config.getClass().getMethod(
                    "copy$default", config.getClass(), String.class, String.class,
                    String.class, String.class, String.class, String.class,
                    List.class, int.class, Object.class);
            config = copyDefault.invoke(null, config, null, null, null, null, null,
                    null, Collections.singletonList(selectedModel), 0x3f, null);
        }
        List<?> messages = (List<?>) invoke(uiState, "getMessages");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalStateException("当前对话没有消息");
        }
        String providerId = String.valueOf(invoke(config, "getProviderId"));
        Object provider = getProviderMethod.invoke(providerManager, providerId);
        if (provider == null || !provider.getClass().getName().endsWith("OpenAiApiProvider")) {
            throw new IllegalStateException("手动压缩目前仅支持 OpenAI-compatible Provider");
        }

        INTERNAL_BUILD.set(Boolean.TRUE);
        String body;
        try {
            body = (String) buildRequestMethod.invoke(provider, config, messages, "", false);
        } finally {
            INTERNAL_BUILD.remove();
        }
        JSONArray serialized = new JSONObject(body).optJSONArray("messages");
        if (serialized == null) {
            throw new IllegalStateException("无法序列化当前对话消息");
        }
        JSONArray cleanSerialized = withoutEnhancerStatuses(serialized);
        return new Snapshot(config, providerSignature(config),
                new JSONArray(cleanSerialized.toString()));
    }

    private static int countHostMessages(List<?> messages) {
        if (messages == null) return 0;
        int count = 0;
        for (Object message : messages) {
            try {
                String content = String.valueOf(invoke(message, "getContent"));
                if (!content.startsWith(ENHANCER_MARKER)) count++;
            } catch (Throwable ignored) {
                count++;
            }
        }
        return count;
    }

    private static int charLength(JSONArray messages) {
        return messages == null ? 0 : messages.toString().length();
    }

    private static int approxTokenLength(JSONArray messages) {
        if (messages == null) return 0;
        String text = messages.toString();
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(0, Math.round((text.length() + bytes / 3f) / 3f));
    }

    private static String providerSignature(Object config) throws Exception {
        return String.valueOf(invoke(config, "getFullApiUrl")) + "\n"
                + String.valueOf(invoke(config, "getModelName"));
    }

    private static int countLeadingSystemMessages(JSONArray messages) {
        int count = 0;
        while (count < messages.length()) {
            JSONObject message = messages.optJSONObject(count);
            if (message == null || !"system".equals(message.optString("role"))) {
                break;
            }
            count++;
        }
        return count;
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static String readableError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    private static final class Snapshot {
        final Object config;
        final String providerSignature;
        final JSONArray messages;

        Snapshot(Object config, String providerSignature, JSONArray messages) {
            this.config = config;
            this.providerSignature = providerSignature;
            this.messages = messages;
        }
    }

    private static final class Prepared {
        final String chatId;
        final String providerSignature;
        final JSONArray source;
        final JSONArray compacted;

        Prepared(String chatId, String providerSignature, JSONArray source,
                JSONArray compacted) {
            this.chatId = chatId;
            this.providerSignature = providerSignature;
            this.source = source;
            this.compacted = compacted;
        }
    }
}