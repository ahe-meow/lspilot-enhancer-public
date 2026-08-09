package dev.operit.lspilot.enhancer;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        final int turnCount;
        final int approxContextTokens;
        final boolean loading;
        final String providerSignature;

        ScreenState(String chatId, Object uiState, int messageCount, int turnCount,
                int approxContextTokens, boolean loading, String providerSignature) {
            this.chatId = chatId;
            this.uiState = uiState;
            this.messageCount = messageCount;
            this.turnCount = turnCount;
            this.approxContextTokens = approxContextTokens;
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

        boolean reducesContext() {
            return originalApproxTokens > 0
                    && compactedApproxTokens > 0
                    && compactedApproxTokens < originalApproxTokens
                    && compactedChars < originalChars
                    && compactedBytes < originalBytes;
        }

        String notReducedMessage() {
            return "压缩结果没有降低上下文长度（估算 token "
                    + originalApproxTokens + " -> " + compactedApproxTokens
                    + "，字符 " + originalChars + " -> " + compactedChars + "）";
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
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LSPilotCompressionWatchdog");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicLong STATUS_SEQUENCE = new AtomicLong();
    private static final AtomicBoolean PREPARING = new AtomicBoolean(false);
    private static final ThreadLocal<Boolean> INTERNAL_BUILD = new ThreadLocal<>();

    private static final long COMPRESSION_TIMEOUT_MS = 60_000L;
    private static final long STATUS_MIN_INTERVAL_MS = 1_500L;
    private static final int METHOD_CACHE_MAX_ENTRIES = 64;
    private static final Map<String, Method> NO_ARG_METHOD_CACHE =
            new LinkedHashMap<String, Method>(METHOD_CACHE_MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Method> eldest) {
                    return size() > METHOD_CACHE_MAX_ENTRIES;
                }
            };
    private static final String ENHANCER_MARKER = "[系统提示 · 上下文压缩]";
    private static final String ENHANCER_ROLE = "system";
    private static final String TAG = "LSPilotEnhancer";
    private static volatile Method buildRequestMethod;
    private static volatile HostAbi hostAbi;
    private static volatile long compressionStartedAt;
    private static volatile CompressionMetrics lastMetrics;
    private static volatile boolean compressionUsedForPendingRequest;
    private static volatile int lastProgressCompleted;
    private static volatile int lastProgressTotal;
    private static volatile long lastBlockedSendNoticeAt;
    private static volatile long lastStatusPostAt;
    private static volatile String lastStatusFingerprint;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile ScreenState currentScreen;
    private static volatile WeakReference<Object> viewModelRef = new WeakReference<>(null);
    private static volatile WeakReference<Object> viewModelContextRef = new WeakReference<>(null);
    private static volatile String viewModelChatId;
    private static volatile String viewModelPackageName;
    private static volatile Prepared prepared;
    private static volatile Result lastResult;
    private static volatile int lastKeepRecent;
    private static volatile boolean preparedUsedForActiveResponse;
    private static volatile boolean preparedUsageNoticePosted;
    private static volatile int preparedApplyCount;

    static Result getLastResult() {
        return lastResult;
    }

    static int getLastKeepRecent() {
        return lastKeepRecent;
    }

    private ManualCompressionManager() {
    }

    static void configure(HostAbi abi) {
        hostAbi = abi;
        buildRequestMethod = abi.buildRequestMethod;
        DebugLogger.i("compression manager configured; minified=" + abi.minified
                + " buildMethod=" + (abi.buildRequestMethod == null ? "none"
                : abi.buildRequestMethod.getDeclaringClass().getName()
                + "#" + abi.buildRequestMethod.getName()));
    }

    static HostAbi hostAbi() {
        return hostAbi;
    }
    private static void postStatus(String chatId, String content) {
        HostAbi abi = hostAbi;
        if (chatId == null || content == null || abi == null
                || abi.repositoryAddMessageMethod == null) {
            DebugLogger.w("chat status skipped chat=" + DebugLogger.id(chatId)
                    + " bridgeReady=" + (abi != null && abi.repositoryAddMessageMethod != null));
            return;
        }
        if (shouldDropStatus(content)) {
            DebugLogger.d("chat status throttled chat=" + DebugLogger.id(chatId)
                    + " prefix=" + DebugLogger.redact(content));
            return;
        }
        STATUS_EXECUTOR.execute(() -> {
            try {
                Object message = abi.newStatusMessage(
                        "lspilot-enhancer-" + System.currentTimeMillis()
                                + "-" + STATUS_SEQUENCE.incrementAndGet(), ENHANCER_ROLE,
                        ENHANCER_MARKER + "\n" + content, System.currentTimeMillis());
                Object repository = HostAbi.singletonInstance(abi.repositoryClass);
                abi.repositoryAddMessageMethod.invoke(repository, chatId, message);
                boolean reloadChat = shouldReloadChat(content);
                DebugLogger.i("chat status inserted chat=" + DebugLogger.id(chatId)
                        + " chars=" + content.length()
                        + " refresh=" + (reloadChat ? "chat_session" : "local_compression_ui"));
                if (reloadChat) reloadCurrentSession(chatId);
                else refreshCompressionUi();
            } catch (Throwable error) {
                DebugLogger.e("chat status insertion failed chat=" + DebugLogger.id(chatId), error);
            }
        });
    }

    private static boolean shouldDropStatus(String content) {
        String fingerprint = statusFingerprint(content);
        long now = System.currentTimeMillis();
        synchronized (ManualCompressionManager.class) {
            if (fingerprint.equals(lastStatusFingerprint)
                    && now - lastStatusPostAt < STATUS_MIN_INTERVAL_MS) {
                return true;
            }
            lastStatusFingerprint = fingerprint;
            lastStatusPostAt = now;
            return false;
        }
    }

    private static String statusFingerprint(String content) {
        if (content.startsWith("压缩进度：摘要分块 ")
                || content.startsWith("压缩中：摘要分块 ")) {
            return "compression-progress";
        }
        return content;
    }

    private static boolean shouldReloadChat(String content) {
        return content.startsWith("压缩完成：")
                || content.startsWith("压缩失败：")
                || content.startsWith("压缩超时：");
    }

    private static void reloadCurrentSession(String chatId) {
        HostAbi abi = hostAbi;
        Object viewModel = viewModelRef.get();
        Object context = viewModelContextRef.get();
        String packageName = viewModelPackageName;
        if (abi == null || abi.loadSessionMethod == null || viewModel == null || context == null
                || packageName == null || !chatId.equals(viewModelChatId)) {
            DebugLogger.w("chat status reload skipped chat=" + DebugLogger.id(chatId));
            refreshCompressionUi();
            return;
        }
        MAIN_HANDLER.post(() -> {
            try {
                abi.loadSessionMethod.invoke(viewModel, packageName, chatId, context);
                DebugLogger.i("chat status reload completed chat=" + DebugLogger.id(chatId));
            } catch (Throwable error) {
                DebugLogger.e("chat status reload failed chat=" + DebugLogger.id(chatId), error);
            } finally {
                refreshCompressionUi();
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
        if (!metrics.reducesContext()) {
            Log.w(TAG, "automatic compression ignored: result is not smaller "
                    + metrics.describe());
            return;
        }
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

    static void captureViewModel(Object viewModel, String chatId, String packageName, Object context) {
        if (viewModel == null || chatId == null) return;
        viewModelRef = new WeakReference<>(viewModel);
        viewModelContextRef = new WeakReference<>(context);
        viewModelChatId = chatId;
        viewModelPackageName = packageName;
        DebugLogger.i("chat ViewModel captured class=" + viewModel.getClass().getName()
                + " chat=" + DebugLogger.id(chatId));
    }

    static void updateMinifiedScreen(String chatId, Object viewModel) {
        HostAbi abi = hostAbi;
        if (abi == null || !abi.minified || chatId == null || viewModel == null) return;
        viewModelRef = new WeakReference<>(viewModel);
        viewModelChatId = chatId;
        try {
            Object state = abi.currentState(viewModel);
            if (state != null) updateScreen(chatId, state);
        } catch (Throwable error) {
            DebugLogger.e("minified chat state capture failed", error);
        }
    }

    static void refreshCurrentScreen() {
        HostAbi abi = hostAbi;
        Object viewModel = viewModelRef.get();
        String chatId = viewModelChatId;
        if (abi == null || !abi.minified || viewModel == null || chatId == null) return;
        updateMinifiedScreen(chatId, viewModel);
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

    static List<Object> applyPreparedToHostMessages(List<?> messages, Object config, HostAbi abi) {
        if (messages == null || config == null || abi == null) return null;
        try {
            JSONArray serialized = abi.serializeMessages(messages);
            JSONArray clean = sanitizeRequestMessages(serialized);
            JSONArray source = clean == null ? serialized : clean;
            JSONArray compacted = applyPrepared(source, config);
            if (compacted != null && compacted != source) {
                return abi.materializeMessages(compacted, messages);
            }
            if (source != serialized) {
                DebugLogger.i("removed enhancer status messages from minified stream request"
                        + " originalMessages=" + serialized.length()
                        + " cleanMessages=" + source.length());
                return abi.materializeMessages(source, messages);
            }
            return null;
        } catch (Throwable error) {
            DebugLogger.e("prepared host message apply failed", error);
            return null;
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
            HostAbi abi = requireHostAbi();
            List<?> messages = abi.minified ? abi.stateMessages(uiState)
                    : (List<?>) invoke(uiState, "getMessages");
            MessageStats stats = measureHostMessages(messages);
            boolean loading = abi.minified ? abi.stateLoading(uiState)
                    : Boolean.TRUE.equals(invoke(uiState, "isLoading"));
            ScreenState previous = currentScreen;
            if (previous != null && previous.loading && !loading
                    && preparedUsedForActiveResponse) {
                Prepared value = prepared;
                if (value != null && chatId.equals(value.chatId)) {
                    preparedUsedForActiveResponse = false;
                    preparedApplyCount = 0;
                    compressionUsedForPendingRequest = false;
                    DebugLogger.i((value.automatic ? "automatic" : "manual")
                            + " prepared context retained after response"
                            + " baseTurns=" + value.baseTurnCount
                            + " baseTokens=" + value.baseApproxContextTokens);
                } else {
                    DebugLogger.i("prepared context cleared after response finished applyCount="
                            + preparedApplyCount);
                    resetPreparedState(true);
                }
            }
            Object config = abi.minified ? abi.stateConfig(uiState)
                    : invoke(uiState, "getSelectedProvider");
            String signature = config == null ? "unknown" : abi.providerSignature(config);
            currentScreen = new ScreenState(chatId, uiState,
                    stats.messageCount, stats.turnCount, stats.approxContextTokens,
                    loading, signature);
            if (ModuleSettings.isVerboseDebugLogEnabled()) {
                DebugLogger.d("screen update chat=" + DebugLogger.id(chatId)
                        + " messages=" + (messages == null ? 0 : messages.size())
                        + " effectiveMessages=" + stats.messageCount
                        + " turns=" + stats.turnCount
                        + " approxTokens=" + stats.approxContextTokens
                        + " loading=" + loading + " provider=" + DebugLogger.redact(signature));
            }
            if (previous != null && previous.loading != loading && loading && PREPARING.get()) {
                DebugLogger.i("chat became loading while compression is preparing; keeping task state");
            }
            if (previous != null && (!chatId.equals(previous.chatId)
                    || !signature.equals(previous.providerSignature))) {
                resetPreparedState(true);
            }
        } catch (Throwable ignored) {
            currentScreen = new ScreenState(chatId, uiState, 0, 0, 0, false, "unknown");
        }
    }

    static ScreenState getCurrentScreen() {
        return currentScreen;
    }

    static boolean isPreparing() {
        return PREPARING.get();
    }

    static boolean blockSendWhilePreparing() {
        if (!PREPARING.get()) {
            return false;
        }
        ScreenState screen = currentScreen;
        long now = System.currentTimeMillis();
        if (now - lastBlockedSendNoticeAt >= 3000L) {
            lastBlockedSendNoticeAt = now;
            postStatus(screen == null ? null : screen.chatId,
                    "压缩仍在进行，已阻止本次发送；请等待压缩完成后再发送。");
        }
        DebugLogger.i("send blocked while compression preparing chat="
                + (screen == null ? "none" : DebugLogger.id(screen.chatId)));
        refreshCompressionUi();
        return true;
    }

    static void refreshCompressionUi() {
        MAIN_HANDLER.post(() -> {
            try {
                NativeChatTopBarAction.refreshPanel();
                InjectedUiController.refreshChatCompressionOverlay();
            } catch (Throwable error) {
                DebugLogger.e("local compression UI refresh failed", error);
            }
        });
    }

    static boolean hasPreparedForCurrentChat() {
        ScreenState screen = currentScreen;
        Prepared value = prepared;
        return screen != null && value != null && screen.chatId.equals(value.chatId);
    }

    static boolean shouldAutoCompressBeforeSend() {
        refreshCurrentScreen();
        ScreenState screen = currentScreen;
        int keepRecent = ModuleSettings.getManualKeepRecent();
        int triggerTokens = ModuleSettings.getAutoContextTokens();
        if (!ModuleSettings.isEnabled()
                || !ModuleSettings.isContextCompressionEnabled()
                || screen == null
                || screen.loading
                || PREPARING.get()) {
            return false;
        }
        if (screen.messageCount <= keepRecent) {
            Log.i(TAG, "auto compression skipped: insufficient messages chat="
                    + DebugLogger.id(screen.chatId)
                    + " approxTokens=" + screen.approxContextTokens + "/" + triggerTokens
                    + " messages=" + screen.messageCount
                    + " keepRecent=" + keepRecent);
            return false;
        }
        if (hasManualPreparedForCurrentChat()) {
            return false;
        }
        Prepared value = prepared;
        if (value != null && value.automatic && screen.chatId.equals(value.chatId)) {
            if (!screen.providerSignature.equals(value.providerSignature)) {
                resetPreparedState(true);
            } else if (!hasEnoughNewContextForRecompression(screen, value, triggerTokens)) {
                int newTokens = Math.max(0,
                        screen.approxContextTokens - value.baseApproxContextTokens);
                Log.i(TAG, "auto compression skipped: reusing prepared baseline chat="
                        + DebugLogger.id(screen.chatId)
                        + " newTokens=" + newTokens + "/" + triggerTokens
                        + " approxTokens=" + screen.approxContextTokens);
                return false;
            }
        }
        if (screen.approxContextTokens >= triggerTokens) {
            Log.i(TAG, "auto compression trigger chat=" + DebugLogger.id(screen.chatId)
                    + " approxTokens=" + screen.approxContextTokens + "/" + triggerTokens
                    + " messages=" + screen.messageCount
                    + " keepRecent=" + keepRecent);
            return true;
        }
        Log.i(TAG, "auto compression skipped: below threshold chat="
                + DebugLogger.id(screen.chatId)
                + " approxTokens=" + screen.approxContextTokens + "/" + triggerTokens
                + " messages=" + screen.messageCount
                + " keepRecent=" + keepRecent);
        return false;
    }

    private static boolean hasManualPreparedForCurrentChat() {
        ScreenState screen = currentScreen;
        Prepared value = prepared;
        return screen != null && value != null && !value.automatic
                && screen.chatId.equals(value.chatId);
    }

    private static boolean hasEnoughNewContextForRecompression(ScreenState screen,
            Prepared value, int triggerTokens) {
        int newTokens = Math.max(0,
                screen.approxContextTokens - value.baseApproxContextTokens);
        return newTokens >= triggerTokens;
    }

    static void clearPrepared() {
        resetPreparedState(true);
    }

    private static void resetPreparedState(boolean bumpGeneration) {
        prepared = null;
        preparedUsedForActiveResponse = false;
        preparedUsageNoticePosted = false;
        preparedApplyCount = 0;
        lastMetrics = null;
        compressionUsedForPendingRequest = false;
        if (bumpGeneration) {
            GENERATION.incrementAndGet();
        }
    }

    static void prepareCurrent(int keepRecent, Callback callback) {
        prepareCurrent(keepRecent, false, callback);
    }

    static void prepareCurrentAutomatic(int keepRecent, Callback callback) {
        prepareCurrent(keepRecent, true, callback);
    }

    private static void prepareCurrent(int keepRecent, boolean automatic, Callback callback) {
        refreshCurrentScreen();
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
        if (!PREPARING.compareAndSet(false, true)) {
            finish(callback, new Result(false, "压缩任务正在运行", screen.messageCount, 0));
            return;
        }

        long generation = GENERATION.incrementAndGet();
        lastProgressCompleted = 0;
        lastProgressTotal = 0;
        compressionStartedAt = System.currentTimeMillis();
        postStatus(screen.chatId, "压缩开始：正在读取当前对话，原始消息 "
                + screen.messageCount + " 条，保留最近 " + keepRecent + " 条。\n"
                + "上下文长度统计将在每个摘要分块完成后更新。");
        AtomicBoolean finished = new AtomicBoolean(false);
        ScheduledFuture<?> watchdogFuture = WATCHDOG.schedule(() -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            long elapsed = System.currentTimeMillis() - compressionStartedAt;
            GENERATION.compareAndSet(generation, generation + 1);
            PREPARING.set(false);
            Result timeout = new Result(false, "压缩超时已强制结束，本次发送已取消。",
                    screen.messageCount, 0);
            DebugLogger.w("compression watchdog fired chat=" + DebugLogger.id(screen.chatId)
                    + " elapsedMs=" + elapsed
                    + " progress=" + lastProgressCompleted + "/" + lastProgressTotal
                    + " automatic=" + automatic);
            postStatus(screen.chatId, "压缩超时：已强制结束本次压缩，未发送本次消息，避免回退到未压缩历史。已耗时 "
                    + elapsed + " ms。");
            refreshCompressionUi();
            finish(callback, timeout);
        }, COMPRESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        EXECUTOR.execute(() -> {
            Result result;
            try {
                DebugLogger.d("compression snapshot begin chat=" + DebugLogger.id(screen.chatId));
                Snapshot snapshot = snapshot(screen);
                boolean incremental = false;
                Prepared previousPrepared = prepared;
                JSONArray compressionInput = snapshot.messages;
                if (!automatic && previousPrepared != null
                        && screen.chatId.equals(previousPrepared.chatId)
                        && snapshot.providerSignature.equals(previousPrepared.providerSignature)) {
                    compressionInput = buildIncrementalCompressionInput(snapshot.messages,
                            previousPrepared, keepRecent);
                    incremental = compressionInput != snapshot.messages;
                    if (incremental) {
                        DebugLogger.i("incremental manual compression input chat="
                                + DebugLogger.id(screen.chatId)
                                + " originalMessages=" + snapshot.messages.length()
                                + " inputMessages=" + compressionInput.length());
                    }
                }
                DebugLogger.d("compression snapshot ready messages=" + snapshot.messages.length()
                        + " inputMessages=" + compressionInput.length()
                        + " incremental=" + incremental
                        + " provider=" + DebugLogger.redact(snapshot.providerSignature));
                postStatus(screen.chatId, "压缩进度：快照完成，输入消息 " + compressionInput.length()
                        + " 条，字符 " + charLength(compressionInput) + "，估算 token "
                        + approxTokenLength(compressionInput) + (incremental ? "。本次使用已有摘要做增量压缩。" : "。"));
                JSONArray compacted = ContextCompression.compact(
                        compressionInput, snapshot.config, true, keepRecent,
                        (completed, total) -> {
                            lastProgressCompleted = completed;
                            lastProgressTotal = total;
                            lastResult = new Result(true,
                        "压缩中：摘要分块 " + completed + "/" + total
                                + " 已完成，已耗时 "
                                + (System.currentTimeMillis() - compressionStartedAt) + " ms。",
                        screen.messageCount, 0);
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
                CompressionMetrics metrics = CompressionMetrics.measure(snapshot.messages, compacted,
                        System.currentTimeMillis() - compressionStartedAt, 0);
                if (!metrics.reducesContext()) {
                    Log.w(TAG, "compression rejected: result is not smaller "
                            + metrics.describe());
                    throw new IllegalStateException(metrics.notReducedMessage());
                }
                if (generation != GENERATION.get()) {
                    throw new IllegalStateException("对话已切换，压缩结果已丢弃");
                }
                if (finished.get()) {
                    throw new IllegalStateException("压缩已超时，结果已丢弃");
                }
                prepared = new Prepared(screen.chatId, snapshot.providerSignature,
                        snapshot.messages, compacted, automatic,
                        screen.turnCount, screen.approxContextTokens);
                preparedUsedForActiveResponse = false;
                preparedUsageNoticePosted = false;
                preparedApplyCount = 0;
                lastMetrics = metrics;
                postStatus(screen.chatId, "压缩完成：消息 " + metrics.originalMessages + " -> "
                        + metrics.compactedMessages + "；字符 " + metrics.originalChars + " -> "
                        + metrics.compactedChars + "；UTF-8 字节 " + metrics.originalBytes + " -> "
                        + metrics.compactedBytes + "；估算 token " + metrics.originalApproxTokens
                        + " -> " + metrics.compactedApproxTokens + "；压缩率 " + metrics.ratioPercent()
                        + "%；耗时 " + metrics.durationMs + " ms。摘要已作为本会话长期基线，后续发送会持续复用，直到切换对话或 Provider。摘要后的新增内容会进行增量压缩。");
                result = new Result(true,
                        "摘要已就绪并将持续用于本会话。原始 "
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
                watchdogFuture.cancel(false);
            }
            if (finished.compareAndSet(false, true)) {
                PREPARING.set(false);
                finish(callback, result);
            } else {
                DebugLogger.w("compression result ignored after watchdog chat="
                        + DebugLogger.id(screen.chatId) + " success=" + result.success);
            }
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

    private static JSONArray buildIncrementalCompressionInput(JSONArray currentSource,
            Prepared previous, int keepRecent) throws Exception {
        if (currentSource == null || previous == null || previous.source == null
                || previous.compacted == null) {
            return currentSource;
        }
        int currentSystemCount = countLeadingSystemMessages(currentSource);
        int previousSystemCount = countLeadingSystemMessages(previous.source);
        int previousHistoryCount = previous.source.length() - previousSystemCount;
        if (currentSource.length() - currentSystemCount <= previousHistoryCount) {
            return currentSource;
        }
        for (int index = 0; index < previousHistoryCount; index++) {
            Object oldItem = previous.source.get(previousSystemCount + index);
            Object currentItem = currentSource.get(currentSystemCount + index);
            if (!String.valueOf(oldItem).equals(String.valueOf(currentItem))) {
                DebugLogger.w("incremental compression skipped: source prefix changed");
                return currentSource;
            }
        }
        JSONArray input = new JSONArray();
        for (int index = 0; index < currentSystemCount; index++) {
            input.put(currentSource.get(index));
        }
        int compactedSystemCount = countLeadingSystemMessages(previous.compacted);
        for (int index = compactedSystemCount; index < previous.compacted.length(); index++) {
            input.put(previous.compacted.get(index));
        }
        int tailStart = currentSystemCount + previousHistoryCount;
        for (int index = tailStart; index < currentSource.length(); index++) {
            input.put(currentSource.get(index));
        }
        if (input.length() >= currentSource.length()) {
            return currentSource;
        }
        DebugLogger.i("incremental compression assembled previousSource="
                + previous.source.length() + " previousCompacted=" + previous.compacted.length()
                + " current=" + currentSource.length() + " input=" + input.length()
                + " keepRecent=" + keepRecent);
        return input;
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
            int invalidToolIndex = ContextCompression.firstInvalidToolCallIndex(result);
            if (invalidToolIndex >= 0) {
                JSONObject invalid = result.optJSONObject(invalidToolIndex);
                JSONObject previous = invalidToolIndex <= 0
                        ? null : result.optJSONObject(invalidToolIndex - 1);
                DebugLogger.w("prepared context rejected: invalid tool call sequence"
                        + " index=" + invalidToolIndex
                        + " role=" + (invalid == null ? "null" : invalid.optString("role"))
                        + " toolCallId=" + (invalid == null ? ""
                        : DebugLogger.redact(invalid.optString("_lspilot_tool_call_id")))
                        + " previousRole=" + (previous == null ? "none"
                        : previous.optString("role"))
                        + " previousHasToolCalls=" + ContextCompression.hasToolCalls(previous));
                resetPreparedState(true);
                return null;
            }
            CompressionMetrics metrics = CompressionMetrics.measure(cleanActual, result, 0L, 0);
            if (!metrics.reducesContext()) {
                Log.w(TAG, "prepared context rejected: result is not smaller "
                        + metrics.describe());
                resetPreparedState(true);
                return null;
            }
            preparedUsedForActiveResponse = true;
            int applyIndex = ++preparedApplyCount;
            compressionUsedForPendingRequest = true;
            lastMetrics = metrics;
            DebugLogger.i("manual prepared context applied chat=" + DebugLogger.id(value.chatId)
                    + " applyCount=" + applyIndex + " " + metrics.describe());
            if (!preparedUsageNoticePosted) {
                preparedUsageNoticePosted = true;
                postStatus(value.chatId, "本会话已启用压缩基线：消息 " + metrics.originalMessages
                        + " -> " + metrics.compactedMessages + "；字符 " + metrics.originalChars + " -> "
                        + metrics.compactedChars + "；估算 token " + metrics.originalApproxTokens + " -> "
                        + metrics.compactedApproxTokens + "；压缩率 " + metrics.ratioPercent()
                        + "%。该摘要会作为本会话长期基线持续复用。新增内容达到阈值后可增量压缩。");
            }
            return result;
        } catch (Throwable error) {
            DebugLogger.e("prepared context apply failed", error);
            resetPreparedState(true);
            return null;
        }
    }

    private static Snapshot snapshot(ScreenState screen) throws Exception {
        HostAbi abi = requireHostAbi();
        Object uiState = screen.uiState;
        Object config = abi.minified ? abi.stateConfig(uiState)
                : invoke(uiState, "getSelectedProvider");
        if (config == null) {
            throw new IllegalStateException("当前对话没有选择 Provider");
        }
        String selectedModel = abi.minified ? abi.stateSelectedModel(uiState)
                : invokeStringOrNull(uiState, "getSelectedModel");
        config = abi.copyConfigWithSingleModel(config, selectedModel);
        List<?> messages = abi.minified ? abi.stateMessages(uiState)
                : (List<?>) invoke(uiState, "getMessages");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalStateException("当前对话没有消息");
        }

        JSONArray serialized;
        if (abi.minified) {
            serialized = abi.serializeMessages(messages);
        } else {
            Object provider = findNamedProvider(abi, config);
            INTERNAL_BUILD.set(Boolean.TRUE);
            String body;
            try {
                body = (String) buildRequestMethod.invoke(provider, config, messages, "", false);
            } finally {
                INTERNAL_BUILD.remove();
            }
            serialized = new JSONObject(body).optJSONArray("messages");
        }
        if (serialized == null || serialized.length() == 0) {
            throw new IllegalStateException("无法序列化当前对话消息");
        }
        JSONArray cleanSerialized = withoutEnhancerStatuses(serialized);
        return new Snapshot(config, abi.providerSignature(config),
                new JSONArray(cleanSerialized.toString()));
    }

    private static MessageStats measureHostMessages(List<?> messages) {
        if (messages == null) return new MessageStats(0, 0, 0);
        HostAbi abi = hostAbi;
        int messageCount = 0;
        int userMessages = 0;
        int charCount = 0;
        int byteCount = 0;
        for (Object message : messages) {
            String content = abi == null ? null : abi.messageContent(message);
            if (content == null) {
                messageCount++;
                continue;
            }
            if (content.startsWith(ENHANCER_MARKER)) continue;
            messageCount++;
            if ("user".equalsIgnoreCase(abi.messageRole(message))) userMessages++;
            charCount += content.length();
            byteCount += content.getBytes(StandardCharsets.UTF_8).length;
        }
        int turnCount = userMessages > 0 ? userMessages : Math.max(1, (messageCount + 1) / 2);
        int approxTokens = Math.max(0, Math.round((charCount + byteCount / 3f) / 3f));
        return new MessageStats(messageCount, turnCount, approxTokens);
    }

    private static String invokeStringOrNull(Object target, String methodName) {
        try {
            Object value = invoke(target, methodName);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
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
        return requireHostAbi().providerSignature(config);
    }

    private static HostAbi requireHostAbi() {
        HostAbi abi = hostAbi;
        if (abi == null) throw new IllegalStateException("Host ABI unavailable");
        return abi;
    }

    private static Object findNamedProvider(HostAbi abi, Object config) throws Exception {
        ClassLoader loader = config.getClass().getClassLoader();
        Class<?> managerClass = Class.forName(
                "me.yun.lspilot.data.provider.ProviderManager", false, loader);
        Object manager = HostAbi.singletonInstance(managerClass);
        Method getProvider = managerClass.getMethod("getProvider", String.class);
        Object provider = getProvider.invoke(manager, abi.providerId(config));
        if (!abi.isProvider(provider)) {
            throw new IllegalStateException("手动压缩目前仅支持 OpenAI-compatible Provider");
        }
        return provider;
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
        if (target == null) {
            throw new NullPointerException("target");
        }
        Method method = cachedNoArgMethod(target.getClass(), methodName);
        return method.invoke(target);
    }

    private static Method cachedNoArgMethod(Class<?> targetClass, String methodName)
            throws NoSuchMethodException {
        String key = targetClass.getName() + '#' + methodName;
        synchronized (NO_ARG_METHOD_CACHE) {
            Method cached = NO_ARG_METHOD_CACHE.get(key);
            if (cached != null && cached.getDeclaringClass().isAssignableFrom(targetClass)) {
                return cached;
            }
            Method method = targetClass.getMethod(methodName);
            NO_ARG_METHOD_CACHE.put(key, method);
            return method;
        }
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

    private static final class MessageStats {
        final int messageCount;
        final int turnCount;
        final int approxContextTokens;

        MessageStats(int messageCount, int turnCount, int approxContextTokens) {
            this.messageCount = messageCount;
            this.turnCount = turnCount;
            this.approxContextTokens = approxContextTokens;
        }
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
        final boolean automatic;
        final int baseTurnCount;
        final int baseApproxContextTokens;

        Prepared(String chatId, String providerSignature, JSONArray source,
                JSONArray compacted, boolean automatic, int baseTurnCount,
                int baseApproxContextTokens) {
            this.chatId = chatId;
            this.providerSignature = providerSignature;
            this.source = source;
            this.compacted = compacted;
            this.automatic = automatic;
            this.baseTurnCount = baseTurnCount;
            this.baseApproxContextTokens = baseApproxContextTokens;
        }
    }
}