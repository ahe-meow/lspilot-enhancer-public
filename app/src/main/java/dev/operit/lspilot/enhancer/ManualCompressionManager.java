package dev.operit.lspilot.enhancer;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Coordinates model-driven, chat-scoped context compression. */
final class ManualCompressionManager {
    interface Callback {
        void onComplete(Result result);
    }

    interface SummaryRequester {
        void request(SummaryTaskSnapshot snapshot);
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

        CompressionMetrics(JSONArray before, JSONArray after, long durationMs) {
            String original = before == null ? "" : before.toString();
            String compacted = after == null ? "" : after.toString();
            originalMessages = before == null ? 0 : before.length();
            compactedMessages = after == null ? 0 : after.length();
            originalChars = original.length();
            compactedChars = compacted.length();
            originalBytes = original.getBytes(StandardCharsets.UTF_8).length;
            compactedBytes = compacted.getBytes(StandardCharsets.UTF_8).length;
            originalApproxTokens = estimate(original);
            compactedApproxTokens = estimate(compacted);
            this.durationMs = durationMs;
            chunks = 1;
        }

        int ratioPercent() {
            return originalApproxTokens == 0 ? 0
                    : Math.round(compactedApproxTokens * 100f / originalApproxTokens);
        }

        private static int estimate(String text) {
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            return Math.max(Math.round(text.length() / 4f), Math.round(bytes / 3f));
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

    static final class SummaryTaskSnapshot {
        final CompressionStateMachine.Task task;
        final JSONArray source;
        final Object config;
        final String prompt;

        SummaryTaskSnapshot(CompressionStateMachine.Task task, JSONArray source,
                Object config, String prompt) {
            this.task = task;
            this.source = copyArray(source);
            this.config = config;
            this.prompt = prompt;
        }
    }

    static final class UiProjection {
        final boolean sendBlocked;
        final boolean sendDisabled;
        final boolean stopDisabled;
        final boolean stopUsesHostState;
        final boolean inputEditable;

        private UiProjection(boolean sendBlocked, boolean sendDisabled,
                boolean stopDisabled, boolean stopUsesHostState, boolean inputEditable) {
            this.sendBlocked = sendBlocked;
            this.sendDisabled = sendDisabled;
            this.stopDisabled = stopDisabled;
            this.stopUsesHostState = stopUsesHostState;
            this.inputEditable = inputEditable;
        }

        static UiProjection forState(CompressionStateMachine.State state,
                boolean hostStopActive) {
            if (state == CompressionStateMachine.State.WAITING_SAFE_BOUNDARY) {
                return new UiProjection(true, false, false, true, true);
            }
            boolean formal = state == CompressionStateMachine.State.SUMMARIZING
                    || state == CompressionStateMachine.State.RETRYING
                    || state == CompressionStateMachine.State.VALIDATING
                    || state == CompressionStateMachine.State.AWAITING_USER_ACTION;
            if (formal) return new UiProjection(true, true, true, false, true);
            return new UiProjection(false, false, false, hostStopActive, true);
        }
    }

    private static final String ENHANCER_MARKER = "[系统提示 · 上下文压缩]";
    static final String RETRY_STATUS_MARKER = "[系统提示 · 自动重试]";
    private static final String TAG = "LSPilotEnhancer";
    private static final long STATUS_MIN_INTERVAL_MS = 1_500L;
    static final long SUMMARY_TIMEOUT_MS = 300_000L;
    private static final ExecutorService STATUS_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LSPilotCompressionStatus");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicLong STATUS_SEQUENCE = new AtomicLong();
    private static final ThreadLocal<Boolean> INTERNAL_BUILD = new ThreadLocal<>();

    private static volatile HostAbi hostAbi;
    private static volatile SummaryRequester summaryRequester;
    private static volatile ScreenState currentScreen;
    private static volatile WeakReference<Object> viewModelRef = new WeakReference<>(null);
    private static volatile String viewModelChatId;
    private static volatile String currentChatId;
    private static volatile Result lastResult;
    private static volatile int lastKeepRecent = ModuleSettings.DEFAULT_SUMMARY_KEEP_RECENT;
    private static volatile CompressionMetrics lastMetrics;
    private static volatile boolean compressionUsedForPendingRequest;
    private static volatile long lastBlockedSendNoticeAt;
    private static volatile long lastStatusPostAt;
    private static volatile String lastStatusFingerprint;

    private static CompressionStateMachine.State state = CompressionStateMachine.State.IDLE;
    private static CompressionStateMachine.Task activeTask;
    private static JSONArray taskSource = new JSONArray();
    private static JSONArray taskBoundary = new JSONArray();
    private static Object taskConfig;
    private static String taskPrompt;
    private static int retryCount;
    private static boolean automaticRetryAllowed;
    private static boolean recoveryRequestNeeded;
    private static boolean recoveryBatchReady;
    private static boolean hasCompleteSnapshot;
    private static boolean lastFailureOverThreshold;
    private static String lastFailureReason;
    private static JSONArray latestHostMessages = new JSONArray();
    private static JSONArray previousCompleteMessages = new JSONArray();
    private static JSONArray pendingUserRequest = new JSONArray();
    private static JSONArray pendingRecoveryEvents = new JSONArray();
    private static SummaryRecordStore.Record usableRecord;
    private static Callback completionCallback;
    private static long compressionStartedAt;

    private ManualCompressionManager() {
    }

    static void configure(HostAbi abi) {
        hostAbi = abi;
    }

    static void setSummaryRequester(SummaryRequester requester) {
        summaryRequester = requester;
    }

    static HostAbi hostAbi() {
        return hostAbi;
    }

    static Result getLastResult() {
        return lastResult;
    }

    static int getLastKeepRecent() {
        return lastKeepRecent;
    }

    static boolean isInternalBuild() {
        return Boolean.TRUE.equals(INTERNAL_BUILD.get());
    }

    static void setInternalBuild(boolean internal) {
        if (internal) INTERNAL_BUILD.set(Boolean.TRUE);
        else INTERNAL_BUILD.remove();
    }

    static synchronized CompressionStateMachine.State getCompressionState() {
        return state;
    }

    static synchronized UiProjection currentUiProjection(boolean hostStopActive) {
        return UiProjection.forState(state, hostStopActive);
    }

    static synchronized boolean isSummaryTaskActive() {
        return state != CompressionStateMachine.State.IDLE;
    }

    static boolean blocksNewSend(CompressionStateMachine.State value) {
        return value != null && value != CompressionStateMachine.State.IDLE;
    }

    static synchronized boolean isPreparing() {
        return blocksNewSend(state);
    }

    static synchronized SummaryTaskSnapshot currentSummaryTask() {
        if (activeTask == null || state != CompressionStateMachine.State.SUMMARIZING) return null;
        return new SummaryTaskSnapshot(activeTask, taskSource, taskConfig, taskPrompt);
    }

    static synchronized Object currentViewModel(String chatId) {
        if (chatId == null || !chatId.equals(viewModelChatId)) return null;
        return viewModelRef.get();
    }

    static void captureViewModel(Object viewModel, String chatId) {
        if (viewModel == null || chatId == null) return;
        viewModelRef = new WeakReference<>(viewModel);
        viewModelChatId = chatId;
    }

    static synchronized void enterChat(String chatId) {
        if (chatId == null || chatId.equals(currentChatId)) return;
        cancelActive("对话已切换，原上下文未改变。", false);
        currentChatId = chatId;
        currentScreen = null;
        latestHostMessages = new JSONArray();
        previousCompleteMessages = new JSONArray();
        pendingUserRequest = new JSONArray();
        pendingRecoveryEvents = new JSONArray();
        recoveryRequestNeeded = false;
        recoveryBatchReady = false;
        hasCompleteSnapshot = false;
        usableRecord = null;
    }

    static void updateMinifiedScreen(String chatId, Object viewModel) {
        HostAbi abi = hostAbi;
        if (abi == null || !abi.minified || chatId == null || viewModel == null) return;
        viewModelRef = new WeakReference<>(viewModel);
        viewModelChatId = chatId;
        try {
            Object value = abi.currentState(viewModel);
            if (value != null) updateScreen(chatId, value);
        } catch (Throwable error) {
            DebugLogger.e("minified chat state capture failed", error);
        }
    }

    static void refreshCurrentScreen() {
        HostAbi abi = hostAbi;
        Object viewModel = viewModelRef.get();
        if (abi != null && abi.minified && viewModel != null && viewModelChatId != null) {
            updateMinifiedScreen(viewModelChatId, viewModel);
        }
    }

    static void updateScreen(String chatId, Object uiState) {
        if (chatId == null || uiState == null) return;
        enterChat(chatId);
        try {
            HostAbi abi = requireHostAbi();
            List<?> messages = abi.minified ? abi.stateMessages(uiState)
                    : (List<?>) invoke(uiState, "getMessages");
            MessageStats stats = measureHostMessages(messages);
            boolean loading = abi.minified ? abi.stateLoading(uiState)
                    : Boolean.TRUE.equals(invoke(uiState, "isLoading"));
            Object config = abi.minified ? abi.stateConfig(uiState)
                    : invoke(uiState, "getSelectedProvider");
            String signature = config == null ? "unknown" : abi.providerSignature(config);
            ScreenState previous = currentScreen;
            currentScreen = new ScreenState(chatId, uiState, stats.messageCount,
                    stats.turnCount, stats.approxContextTokens, loading, signature);
            if (previous != null && !signature.equals(previous.providerSignature)) {
                SummaryRecordStore.invalidate(chatId);
                usableRecord = null;
                cancelActive("Provider 已变化，压缩记录已失效；原上下文未改变。", true);
            }
            if (messages != null) latestHostMessages = cleanMessages(abi.serializeMessages(messages));
        } catch (Throwable error) {
            currentScreen = new ScreenState(chatId, uiState, 0, 0, 0, false, "unknown");
        }
    }

    static ScreenState getCurrentScreen() {
        return currentScreen;
    }

    static synchronized boolean hasPreparedForCurrentChat() {
        return usableRecord != null && usableRecord.complete
                && usableRecord.chatId.equals(currentChatId);
    }

    static synchronized void clearPrepared() {
        if (currentChatId != null) SummaryRecordStore.invalidate(currentChatId);
        usableRecord = null;
    }

    static boolean shouldAutoCompressBeforeSend() {
        return false;
    }

    static boolean blockSendWhilePreparing() {
        CompressionStateMachine.State current;
        synchronized (ManualCompressionManager.class) {
            current = state;
        }
        if (!UiProjection.forState(current, false).sendBlocked) return false;
        long now = System.currentTimeMillis();
        if (now - lastBlockedSendNoticeAt >= 3_000L) {
            lastBlockedSendNoticeAt = now;
            postCompressionStatus("上下文压缩仍在进行，已阻止新的发送请求。");
        }
        refreshCompressionUi();
        return true;
    }

    static boolean blockStopWhileCompressing() {
        CompressionStateMachine.State current;
        synchronized (ManualCompressionManager.class) {
            current = state;
        }
        if (!UiProjection.forState(current, true).stopDisabled) return false;
        postCompressionStatus("上下文压缩正在整理摘要，已暂时禁用停止操作。");
        refreshCompressionUi();
        return true;
    }

    static void onCompleteEvent(String chatId, JSONArray effectiveMessages,
            boolean toolChainComplete, boolean replyFinished) {
        if (chatId == null || effectiveMessages == null) return;
        enterChat(chatId);
        JSONArray hostMessages = cleanMessages(effectiveMessages);
        Object config = currentConfig();
        String model = currentModel();
        JSONArray added = recoveryEventsSince(previousCompleteMessages, hostMessages,
                hasCompleteSnapshot);
        synchronized (ManualCompressionManager.class) {
            latestHostMessages = copyArray(hostMessages);
            previousCompleteMessages = copyArray(hostMessages);
            hasCompleteSnapshot = true;
            if (isSummaryTaskActive()) {
                if (!isBoundaryPrefix(taskBoundary, hostMessages)) {
                    SummaryRecordStore.invalidate(chatId);
                    cancelActive("历史在摘要期间发生变化，原上下文未改变。", true);
                    return;
                }
                bufferRecoveryEvents(added);
                recoveryRequestNeeded |= !replyFinished;
                if (state == CompressionStateMachine.State.WAITING_SAFE_BOUNDARY
                        && toolChainComplete) {
                    JSONArray source = effectiveSummaryMessages(
                            hostMessages, config, model, pendingUserRequest);
                    startTask(chatId, source, hostMessages, config, model,
                            activeTask == null ? ModuleSettings.getSummaryKeepRecent()
                                    : activeTask.keepRecent,
                            true, automaticRetryAllowed, completionCallback);
                }
                return;
            }
        }
        if (!ModuleSettings.isEnabled() || !ModuleSettings.isContextCompressionEnabled()) return;
        JSONArray source = effectiveSummaryMessages(
                hostMessages, config, model, pendingUserRequest);
        if (completedContextTokens(source) < ModuleSettings.getAutoContextTokens()) return;
        bufferRecoveryEvents(added);
        synchronized (ManualCompressionManager.class) {
            recoveryRequestNeeded = !replyFinished;
            startTask(chatId, source, hostMessages, config, model,
                    ModuleSettings.getSummaryKeepRecent(), toolChainComplete, true, null);
        }
    }

    static synchronized boolean beginPendingUserRequest(String chatId, JSONArray userRequest) {
        if (chatId == null || userRequest == null || userRequest.length() == 0) return false;
        enterChat(chatId);
        bufferPendingUserRequest(userRequest);
        if (isSummaryTaskActive()) return true;
        Object config = currentConfig();
        String model = currentModel();
        JSONArray boundary = copyArray(latestHostMessages);
        JSONArray source = effectiveSummaryMessages(boundary, config, model, pendingUserRequest);
        if (completedContextTokens(source) < ModuleSettings.getAutoContextTokens()) {
            pendingUserRequest = new JSONArray();
            recoveryRequestNeeded = false;
            return false;
        }
        return startTask(chatId, source, excludePendingUser(boundary, pendingUserRequest),
                config, model, ModuleSettings.getSummaryKeepRecent(),
                SummaryProtocol.hasCompleteToolPairs(source), true, null);
    }

    static synchronized void onSummaryResponse(long taskId, String chatId, String markdown,
            boolean terminalAssistant, boolean returnedToolCall, boolean thinkingOnly) {
        if (!isCurrentSummaryResponse(activeTask, taskId, chatId, state,
                taskBoundary, latestHostMessages, providerSignature(currentConfig()),
                currentModel())) {
            DebugLogger.w("stale compression response ignored task=" + taskId);
            if (CompressionStateMachine.isCurrent(activeTask, taskId, chatId)) {
                SummaryRecordStore.invalidate(chatId);
                cancelActive("摘要响应已过期，原上下文未改变。", true);
            }
            return;
        }
        state = CompressionStateMachine.transition(state,
                CompressionStateMachine.Event.SUMMARY_RESPONSE);
        if (!terminalAssistant || returnedToolCall || thinkingOnly) {
            handleSummaryFailure("摘要响应不是终态 Markdown", false);
            return;
        }
        SummaryProtocol.Validation validation = SummaryProtocol.validateTerminalMarkdown(
                markdown, taskSource, activeTask.keepRecent);
        if (!validation.success) {
            handleSummaryFailure(validation.reason, false);
            return;
        }
        try {
            SummaryRecordStore.Record candidate = buildRecord(markdown);
            JSONArray rebuilt = rebuildEffective(latestHostMessages, candidate);
            rebuilt = appendPendingUserOnce(rebuilt, pendingUserRequest);
            int threshold = ModuleSettings.getAutoContextTokens();
            if (completedContextTokens(rebuilt) >= threshold) {
                handleSummaryFailure("保留最近 " + activeTask.keepRecent
                        + " 轮后上下文仍超过 " + threshold + " token", true);
                return;
            }
            SummaryRecordStore.writeComplete(activeTask.chatId, candidate);
            usableRecord = candidate;
            CompressionMetrics metrics = new CompressionMetrics(taskSource, rebuilt,
                    System.currentTimeMillis() - compressionStartedAt);
            lastMetrics = metrics;
            compressionUsedForPendingRequest = recoveryRequestNeeded;
            recoveryBatchReady = pendingRecoveryEvents.length() > 0
                    || pendingUserRequest.length() > 0;
            state = CompressionStateMachine.transition(state,
                    CompressionStateMachine.Event.SUMMARY_VALID);
            Result result = new Result(true,
                    "摘要已就绪：估算 token " + candidate.beforeTokens + " -> "
                            + candidate.afterTokens + "，减少 " + candidate.reduction
                            + "（" + Math.round(candidate.ratio) + "%）。",
                    taskSource.length(), rebuilt.length(), metrics);
            postStatus(activeTask.chatId, result.message);
            finishActive(result);
        } catch (Throwable error) {
            handleSummaryFailure(readableError(error), false);
        }
    }

    static synchronized void onSummaryTimeout(long taskId, String chatId) {
        if (state != CompressionStateMachine.State.SUMMARIZING
                || !CompressionStateMachine.isCurrent(activeTask, taskId, chatId)) return;
        handleSummaryFailure("摘要请求超时", false);
    }

    static synchronized void onCompressionAction(CompressionStateMachine.Action action) {
        long taskId = activeTask == null ? -1L : activeTask.taskId;
        String chatId = activeTask == null ? null : activeTask.chatId;
        onCompressionAction(taskId, chatId, action);
    }

    static synchronized boolean onCompressionAction(long taskId, String chatId,
            CompressionStateMachine.Action action) {
        if (action == null || activeTask == null) return false;
        if (!CompressionStateMachine.isCurrent(activeTask, taskId, chatId)) return false;
        if (action == CompressionStateMachine.Action.CANCEL) {
            SummaryRecordStore.invalidate(activeTask.chatId);
            cancelActive("已取消上下文压缩，原上下文未改变。", true);
            return true;
        }
        if (!isCompressionActionAllowed(state, activeTask.keepRecent,
                lastFailureOverThreshold, action)) return false;
        int keep = activeTask.keepRecent;
        if (action == CompressionStateMachine.Action.KEEP_2 && keep > 2) keep = 2;
        else if (action == CompressionStateMachine.Action.KEEP_1 && keep > 1) keep = 1;
        else if (action != CompressionStateMachine.Action.RETRY) return false;
        startTask(activeTask.chatId, taskSource, taskBoundary, taskConfig,
                activeTask.model, keep, true, false, completionCallback);
        return true;
    }

    static synchronized long currentCompressionTaskId() {
        return activeTask == null ? -1L : activeTask.taskId;
    }

    static synchronized String currentCompressionChatId() {
        return activeTask == null ? null : activeTask.chatId;
    }

    static synchronized boolean isCompressionActionAvailable(
            CompressionStateMachine.Action action) {
        return activeTask != null && isCompressionActionAllowed(state,
                activeTask.keepRecent, lastFailureOverThreshold, action);
    }

    static synchronized JSONArray excludePendingUser(
            JSONArray effectiveMessages, JSONArray pendingUser) {
        JSONArray source = copyArray(effectiveMessages);
        int start = lastSequenceStart(source, pendingUser);
        if (start < 0) return source;
        JSONArray result = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            if (index < start || index >= start + pendingUser.length()) result.put(source.opt(index));
        }
        return result;
    }

    static synchronized JSONArray appendPendingUserOnce(
            JSONArray messages, JSONArray pendingUser) {
        JSONArray result = copyArray(messages);
        if (pendingUser == null || pendingUser.length() == 0
                || lastSequenceStart(result, pendingUser) >= 0) return result;
        for (int index = 0; index < pendingUser.length(); index++) {
            result.put(copyValue(pendingUser.opt(index)));
        }
        return result;
    }

    static synchronized void bufferRecoveryEvents(JSONArray events) {
        if (events == null) return;
        for (int index = 0; index < events.length(); index++) {
            pendingRecoveryEvents.put(copyValue(events.opt(index)));
        }
        if (events.length() > 0) recoveryBatchReady = true;
    }

    static synchronized void bufferPendingUserRequest(JSONArray request) {
        if (request != null && request.length() > 0 && pendingUserRequest.length() == 0) {
            pendingUserRequest = copyArray(request);
            recoveryRequestNeeded = true;
            recoveryBatchReady = true;
        }
    }

    static synchronized JSONArray drainRecoveryEvents() {
        JSONArray result = copyArray(pendingRecoveryEvents);
        pendingRecoveryEvents = new JSONArray();
        if (pendingUserRequest.length() == 0) recoveryBatchReady = false;
        return result;
    }

    static synchronized JSONArray drainRecoveryBatch() {
        JSONArray result = copyArray(pendingRecoveryEvents);
        pendingRecoveryEvents = new JSONArray();
        for (int index = 0; index < pendingUserRequest.length(); index++) {
            result.put(copyValue(pendingUserRequest.opt(index)));
        }
        pendingUserRequest = new JSONArray();
        recoveryRequestNeeded = false;
        recoveryBatchReady = false;
        return result;
    }

    static synchronized JSONArray drainPendingUserRequest() {
        JSONArray result = copyArray(pendingUserRequest);
        pendingUserRequest = new JSONArray();
        recoveryRequestNeeded = false;
        if (pendingRecoveryEvents.length() == 0) recoveryBatchReady = false;
        return result;
    }

    static JSONArray recoveryEventsSince(JSONArray previous, JSONArray current,
            boolean hasSnapshot) {
        if (!hasSnapshot) return new JSONArray();
        JSONArray suffix = appendedSuffix(previous, current);
        JSONArray result = new JSONArray();
        for (int index = 0; index < suffix.length(); index++) {
            JSONObject event = suffix.optJSONObject(index);
            if (event != null && !"user".equalsIgnoreCase(event.optString("role"))) {
                result.put(copyValue(event));
            }
        }
        return result;
    }

    static int completedContextTokens(JSONArray messages) {
        return SummaryProtocol.estimateTokens(cleanMessages(messages));
    }

    static synchronized JSONArray effectiveRequestMessages(
            JSONArray hostMessages, Object config, String model) {
        return effectiveRequestMessagesInternal(hostMessages, config, model, true);
    }

    static synchronized JSONArray effectiveSummaryMessages(
            JSONArray hostMessages, Object config, String model, JSONArray pendingUser) {
        return effectiveRequestMessagesInternal(
                excludePendingUser(hostMessages, pendingUser), config, model, false);
    }

    private static JSONArray effectiveRequestMessagesInternal(
            JSONArray hostMessages, Object config, String model, boolean includePending) {
        JSONArray clean = cleanMessages(hostMessages);
        latestHostMessages = copyArray(clean);
        if (currentChatId == null) {
            return includePending ? appendPendingUserOnce(clean, pendingUserRequest) : clean;
        }
        String signature = providerSignature(config);
        SummaryRecordStore.Record record = findUsableRecord(
                currentChatId, clean, signature, model);
        if (record == null) {
            SummaryRecordStore.invalidate(currentChatId);
            usableRecord = null;
            return includePending ? appendPendingUserOnce(clean, pendingUserRequest) : clean;
        }
        usableRecord = record;
        JSONArray rebuilt = rebuildEffective(clean, record);
        return includePending ? appendPendingUserOnce(rebuilt, pendingUserRequest) : rebuilt;
    }

    static boolean isCurrentSummaryResponse(CompressionStateMachine.Task task, long taskId,
            String chatId, CompressionStateMachine.State currentState, JSONArray boundary,
            JSONArray currentMessages, String providerSignature, String model) {
        if (task == null || currentState != CompressionStateMachine.State.SUMMARIZING
                || !CompressionStateMachine.isCurrent(task, taskId, chatId)
                || !task.boundaryFingerprint.equals(SummaryRecordStore.fingerprint(boundary))
                || !isBoundaryPrefix(boundary, currentMessages)) return false;
        if (providerSignature != null && !providerSignature.isEmpty()
                && !task.providerSignature.equals(providerSignature)) return false;
        return model == null || model.isEmpty() || task.model.equals(safe(model));
    }

    static boolean isCompressionActionAllowed(CompressionStateMachine.State currentState,
            int keepRecent, boolean overThreshold, CompressionStateMachine.Action action) {
        if (currentState != CompressionStateMachine.State.AWAITING_USER_ACTION
                || action == null) return false;
        if (action == CompressionStateMachine.Action.CANCEL) return true;
        if (overThreshold) {
            return action == CompressionStateMachine.Action.KEEP_2 && keepRecent > 2
                    || action == CompressionStateMachine.Action.KEEP_1 && keepRecent > 1;
        }
        return action == CompressionStateMachine.Action.RETRY;
    }

    static boolean hasSummaryTimedOut(long startedAt, long now) {
        return now - startedAt >= SUMMARY_TIMEOUT_MS;
    }

    static JSONArray sanitizeRequestMessages(JSONArray messages) {
        return cleanMessages(messages);
    }

    static boolean isInternalSummaryRequest(JSONArray messages) {
        if (messages == null || messages.length() == 0) return false;
        SummaryTaskSnapshot current = currentSummaryTask();
        if (current == null) return false;
        boolean promptFound = false;
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null) return false;
            String role = message.optString("role");
            if ("system".equalsIgnoreCase(role)) continue;
            String text = messageText(message.opt("content"));
            if (promptFound || !"user".equalsIgnoreCase(role)
                    || !isInternalPromptText(text, current.prompt)) return false;
            promptFound = true;
        }
        return promptFound;
    }

    private static boolean isInternalPromptText(String text, String prompt) {
        if (text == null || prompt == null || prompt.isEmpty()) return false;
        if (text.equals(prompt)) return true;
        int start = text.indexOf(prompt);
        if (start < 0) return false;
        int end = start + prompt.length();
        return isPromptBoundary(text, start - 1) && isPromptBoundary(text, end);
    }

    private static boolean isPromptBoundary(String text, int index) {
        return index < 0 || index >= text.length()
                || !Character.isLetterOrDigit(text.charAt(index));
    }

    private static String messageText(Object content) {
        if (content == null || JSONObject.NULL.equals(content)) return "";
        if (content instanceof JSONArray) {
            StringBuilder result = new StringBuilder();
            JSONArray array = (JSONArray) content;
            for (int index = 0; index < array.length(); index++) {
                result.append(messageText(array.opt(index)));
            }
            return result.toString();
        }
        if (content instanceof JSONObject) {
            JSONObject object = (JSONObject) content;
            if (object.has("text")) return messageText(object.opt("text"));
            if (object.has("content")) return messageText(object.opt("content"));
        }
        return String.valueOf(content);
    }

    static JSONArray sanitizeRequestMessagesOrThrow(JSONArray messages) {
        if (messages == null) throw new IllegalArgumentException("messages == null");
        JSONArray result = new JSONArray();
        try {
            for (int index = 0; index < messages.length(); index++) {
                JSONObject message = messages.optJSONObject(index);
                if (message == null) {
                    throw new IllegalArgumentException("message must be object");
                }
                if (!isEnhancerStatus(message)) {
                    result.put(SummaryProtocol.sanitizedMessage(message));
                }
            }
            return result;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalArgumentException("request messages cannot be sanitized", error);
        }
    }

    static JSONArray applyPrepared(JSONArray actualMessages, Object config) {
        if (actualMessages == null || config == null) return null;
        JSONArray rebuilt = effectiveRequestMessages(actualMessages, config, modelName(config));
        if (sameArray(actualMessages, rebuilt)) return null;
        lastMetrics = new CompressionMetrics(actualMessages, rebuilt, 0L);
        compressionUsedForPendingRequest = true;
        return rebuilt;
    }

    static void prepareCurrent(int keepRecent, Callback callback) {
        prepareCurrent(false, callback);
    }

    static void prepareCurrentAutomatic(int keepRecent, Callback callback) {
        prepareCurrent(true, callback);
    }

    private static void prepareCurrent(boolean automatic, Callback callback) {
        refreshCurrentScreen();
        ScreenState screen = currentScreen;
        if (screen == null || screen.loading) {
            finish(callback, new Result(false, screen == null
                    ? "当前没有可用的对话" : "请等待当前回复结束后再压缩", 0, 0));
            return;
        }
        try {
            Snapshot snapshot = snapshot(screen);
            JSONArray source = effectiveRequestMessages(
                    snapshot.messages, snapshot.config, snapshot.model);
            synchronized (ManualCompressionManager.class) {
                if (!startTask(screen.chatId, source, snapshot.messages, snapshot.config,
                        snapshot.model, ModuleSettings.getSummaryKeepRecent(),
                        SummaryProtocol.hasCompleteToolPairs(source), true, callback)) {
                    finish(callback, new Result(false, "压缩任务正在运行",
                            source.length(), 0));
                }
            }
        } catch (Throwable error) {
            finish(callback, new Result(false, readableError(error), 0, 0));
        }
    }

    private static boolean startTask(String chatId, JSONArray source, JSONArray boundary,
            Object config, String model, int keepRecent, boolean safeBoundary,
            boolean allowAutomaticRetry, Callback callback) {
        if (chatId == null || source == null || source.length() == 0) return false;
        if (isSummaryTaskActive() && state != CompressionStateMachine.State.WAITING_SAFE_BOUNDARY
                && state != CompressionStateMachine.State.AWAITING_USER_ACTION) return false;
        String signature = providerSignature(config);
        activeTask = CompressionStateMachine.newTask(chatId,
                SummaryRecordStore.fingerprint(boundary), signature, safe(model), keepRecent);
        taskSource = copyArray(source);
        taskBoundary = copyArray(boundary);
        taskConfig = config;
        taskPrompt = SummaryProtocol.buildPrompt(
                taskSource, keepRecent, ModuleSettings.getAutoContextTokens(), "");
        lastKeepRecent = keepRecent;
        retryCount = 0;
        automaticRetryAllowed = allowAutomaticRetry;
        lastFailureOverThreshold = false;
        lastFailureReason = null;
        completionCallback = callback;
        compressionStartedAt = System.currentTimeMillis();
        state = safeBoundary ? CompressionStateMachine.State.SUMMARIZING
                : CompressionStateMachine.State.WAITING_SAFE_BOUNDARY;
        lastResult = new Result(true, safeBoundary
                ? "正在使用当前模型压缩上下文，请稍候。"
                : "等待当前工具调用完成后开始压缩。", source.length(), 0);
        postStatus(chatId, lastResult.message);
        refreshCompressionUi();
        if (state == CompressionStateMachine.State.SUMMARIZING) dispatchSummaryRequest();
        return true;
    }

    private static void handleSummaryFailure(String reason) {
        handleSummaryFailure(reason, false);
    }

    private static void handleSummaryFailure(String reason, boolean overThreshold) {
        if (activeTask == null) return;
        if (state == CompressionStateMachine.State.SUMMARIZING) {
            state = CompressionStateMachine.transition(state,
                    CompressionStateMachine.Event.SUMMARY_RESPONSE);
        }
        lastFailureReason = reason;
        lastFailureOverThreshold = overThreshold;
        if (!overThreshold && automaticRetryAllowed && retryCount == 0) {
            retryCount = 1;
            state = CompressionStateMachine.transition(state,
                    CompressionStateMachine.Event.FIRST_FAILURE);
            activeTask = CompressionStateMachine.newTask(activeTask.chatId,
                    activeTask.boundaryFingerprint, activeTask.providerSignature,
                    activeTask.model, activeTask.keepRecent);
            state = CompressionStateMachine.transition(state,
                    CompressionStateMachine.Event.RETRY_REQUESTED);
            postStatus(activeTask.chatId, "摘要校验失败，正在自动重试一次：" + reason);
            dispatchSummaryRequest();
            return;
        }
        state = CompressionStateMachine.transition(state,
                CompressionStateMachine.Event.FAILURE_EXHAUSTED);
        SummaryRecordStore.invalidate(activeTask.chatId);
        usableRecord = null;
        lastResult = new Result(false, "压缩失败：" + reason + "；原上下文未改变。",
                taskSource.length(), 0);
        postStatus(activeTask.chatId, lastResult.message);
        refreshCompressionUi();
        Callback callback = completionCallback;
        completionCallback = null;
        finish(callback, lastResult);
    }

    private static SummaryRecordStore.Record buildRecord(String markdown) {
        int before = completedContextTokens(taskSource);
        SummaryRecordStore.Record provisional = new SummaryRecordStore.Record(
                activeTask.chatId, markdown, taskBoundary,
                SummaryRecordStore.fingerprint(taskBoundary), activeTask.providerSignature,
                activeTask.model, activeTask.keepRecent, before, 0, 0, 0d,
                true, System.currentTimeMillis());
        JSONArray rebuilt = appendPendingUserOnce(
                rebuildEffective(latestHostMessages, provisional), pendingUserRequest);
        int after = completedContextTokens(rebuilt);
        int reduction = Math.max(0, before - after);
        double ratio = before == 0 ? 0d : reduction * 100d / before;
        return new SummaryRecordStore.Record(activeTask.chatId, markdown, taskBoundary,
                provisional.fingerprint, activeTask.providerSignature, activeTask.model,
                activeTask.keepRecent, before, after, reduction, ratio,
                true, System.currentTimeMillis());
    }

    private static void finishActive(Result result) {
        Callback callback = completionCallback;
        activeTask = null;
        taskSource = new JSONArray();
        taskBoundary = new JSONArray();
        taskConfig = null;
        taskPrompt = null;
        retryCount = 0;
        automaticRetryAllowed = false;
        lastFailureOverThreshold = false;
        lastFailureReason = null;
        completionCallback = null;
        lastResult = result;
        refreshCompressionUi();
        finish(callback, result);
    }

    private static void cancelActive(String message, boolean postNotice) {
        if (!isSummaryTaskActive()) return;
        String chatId = activeTask == null ? currentChatId : activeTask.chatId;
        state = CompressionStateMachine.State.IDLE;
        Result result = new Result(false, message, taskSource.length(), 0);
        Callback callback = completionCallback;
        activeTask = null;
        taskSource = new JSONArray();
        taskBoundary = new JSONArray();
        taskConfig = null;
        taskPrompt = null;
        lastFailureOverThreshold = false;
        lastFailureReason = null;
        completionCallback = null;
        lastResult = result;
        if (postNotice) postStatus(chatId, message);
        finish(callback, result);
    }

    private static void finish(Callback callback, Result result) {
        lastResult = result;
        if (callback != null) callback.onComplete(result);
    }

    private static void dispatchSummaryRequest() {
        SummaryRequester requester = summaryRequester;
        SummaryTaskSnapshot snapshot = currentSummaryTask();
        if (requester == null || snapshot == null) return;
        try {
            requester.request(snapshot);
        } catch (Throwable error) {
            DebugLogger.e("failed to dispatch summary request", error);
            onSummaryResponse(snapshot.task.taskId, snapshot.task.chatId, "",
                    false, false, false);
        }
    }

    private static SummaryRecordStore.Record findUsableRecord(String chatId, JSONArray messages,
            String providerSignature, String model) {
        // ponytail: histories are small; index boundaries only if this prefix scan profiles hot.
        for (int length = messages.length(); length > 0; length--) {
            JSONArray prefix = prefix(messages, length);
            SummaryRecordStore.Record record = SummaryRecordStore.readUsable(
                    chatId, prefix, providerSignature, safe(model));
            if (record != null) return record;
        }
        return null;
    }

    private static boolean isBoundaryPrefix(JSONArray boundary, JSONArray current) {
        if (boundary == null || current == null || boundary.length() > current.length()) return false;
        for (int index = 0; index < boundary.length(); index++) {
            if (!sameMessage(boundary.opt(index), current.opt(index))) return false;
        }
        return true;
    }

    private static JSONArray rebuildEffective(
            JSONArray hostMessages, SummaryRecordStore.Record record) {
        JSONArray clean = cleanMessages(hostMessages);
        JSONArray result = new JSONArray();
        int systemCount = countLeadingSystemMessages(clean);
        for (int index = 0; index < systemCount; index++) result.put(clean.opt(index));
        try {
            result.put(SummaryProtocol.wrapBaseline(record.summaryText));
        } catch (Throwable error) {
            throw new IllegalStateException("summary baseline unavailable", error);
        }
        int boundaryLength = record.coveredBoundary.length();
        for (int index = Math.max(systemCount, boundaryLength);
                index < clean.length(); index++) {
            result.put(clean.opt(index));
        }
        return result;
    }

    private static Snapshot snapshot(ScreenState screen) throws Exception {
        HostAbi abi = requireHostAbi();
        Object config = abi.minified ? abi.stateConfig(screen.uiState)
                : invoke(screen.uiState, "getSelectedProvider");
        if (config == null) throw new IllegalStateException("当前对话没有选择 Provider");
        String model = abi.minified ? abi.stateSelectedModel(screen.uiState)
                : stringValue(invoke(screen.uiState, "getSelectedModel"));
        config = abi.copyConfigWithSingleModel(config, model);
        List<?> messages = abi.minified ? abi.stateMessages(screen.uiState)
                : (List<?>) invoke(screen.uiState, "getMessages");
        JSONArray serialized;
        if (abi.minified) {
            serialized = abi.serializeMessages(messages);
        } else {
            Object provider = findNamedProvider(abi, config);
            INTERNAL_BUILD.set(Boolean.TRUE);
            try {
                String body = (String) abi.buildRequestMethod.invoke(
                        provider, config, messages, "", false);
                serialized = new JSONObject(body).optJSONArray("messages");
            } finally {
                INTERNAL_BUILD.remove();
            }
        }
        if (serialized == null || serialized.length() == 0) {
            throw new IllegalStateException("无法序列化当前对话消息");
        }
        return new Snapshot(config, safe(model), cleanMessages(serialized));
    }

    private static Object currentConfig() {
        ScreenState screen = currentScreen;
        HostAbi abi = hostAbi;
        if (screen == null || abi == null) return null;
        try {
            return abi.minified ? abi.stateConfig(screen.uiState)
                    : invoke(screen.uiState, "getSelectedProvider");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String currentModel() {
        ScreenState screen = currentScreen;
        HostAbi abi = hostAbi;
        if (screen == null || abi == null) return "";
        try {
            return safe(abi.minified ? abi.stateSelectedModel(screen.uiState)
                    : stringValue(invoke(screen.uiState, "getSelectedModel")));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String modelName(Object config) {
        HostAbi abi = hostAbi;
        if (abi == null || config == null) return "";
        try {
            return safe(abi.modelName(config));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String providerSignature(Object config) {
        if (config == null) return "";
        HostAbi abi = hostAbi;
        if (abi == null) return String.valueOf(config);
        try {
            return safe(abi.providerSignature(config));
        } catch (Throwable error) {
            return String.valueOf(config);
        }
    }

    private static Object findNamedProvider(HostAbi abi, Object config) throws Exception {
        Class<?> managerClass = Class.forName(
                "me.yun.lspilot.data.provider.ProviderManager", false,
                config.getClass().getClassLoader());
        Object manager = HostAbi.singletonInstance(managerClass);
        Object provider = managerClass.getMethod("getProvider", String.class)
                .invoke(manager, abi.providerId(config));
        if (!abi.isProvider(provider)) {
            throw new IllegalStateException("当前 Provider 不支持模型摘要");
        }
        return provider;
    }

    private static MessageStats measureHostMessages(List<?> messages) {
        if (messages == null) return new MessageStats(0, 0, 0);
        HostAbi abi = hostAbi;
        try {
            JSONArray serialized = cleanMessages(abi.serializeMessages(messages));
            int users = 0;
            for (int index = 0; index < serialized.length(); index++) {
                JSONObject message = serialized.optJSONObject(index);
                if (message != null && "user".equalsIgnoreCase(message.optString("role"))) users++;
            }
            return new MessageStats(serialized.length(), users > 0 ? users
                    : Math.max(1, (serialized.length() + 1) / 2),
                    completedContextTokens(serialized));
        } catch (Throwable ignored) {
            int chars = 0;
            int bytes = 0;
            int users = 0;
            for (Object message : messages) {
                String content = abi == null ? null : abi.messageContent(message);
                if (isEnhancerStatusContent(content)) continue;
                if ("user".equalsIgnoreCase(abi.messageRole(message))) users++;
                if (content != null) {
                    chars += content.length();
                    bytes += content.getBytes(StandardCharsets.UTF_8).length;
                }
            }
            int tokens = Math.max(Math.round(chars / 4f), Math.round(bytes / 3f));
            return new MessageStats(messages.size(), users > 0 ? users
                    : Math.max(1, (messages.size() + 1) / 2), tokens);
        }
    }

    private static JSONArray cleanMessages(JSONArray messages) {
        JSONArray result = new JSONArray();
        if (messages == null) return result;
        for (int index = 0; index < messages.length(); index++) {
            Object value = messages.opt(index);
            JSONObject message = value instanceof JSONObject ? (JSONObject) value : null;
            if (message == null || !isEnhancerStatus(message)) result.put(copyValue(value));
        }
        return result;
    }

    private static boolean isEnhancerStatus(JSONObject message) {
        return message != null && ("system".equals(message.optString("role"))
                || "assistant".equals(message.optString("role")))
                && isEnhancerStatusContent(message.optString("content", null));
    }

    private static boolean isEnhancerStatusContent(String content) {
        return content != null && (content.startsWith(ENHANCER_MARKER)
                || content.startsWith(RETRY_STATUS_MARKER));
    }

    private static JSONArray appendedSuffix(JSONArray previous, JSONArray current) {
        if (previous == null || current == null || previous.length() > current.length()) {
            return new JSONArray();
        }
        for (int index = 0; index < previous.length(); index++) {
            if (!sameMessage(previous.opt(index), current.opt(index))) return new JSONArray();
        }
        JSONArray result = new JSONArray();
        for (int index = previous.length(); index < current.length(); index++) {
            result.put(copyValue(current.opt(index)));
        }
        return result;
    }

    private static void bufferNonUserEvents(JSONArray events) {
        JSONArray filtered = new JSONArray();
        for (int index = 0; index < events.length(); index++) {
            JSONObject event = events.optJSONObject(index);
            if (event != null && !"user".equals(event.optString("role"))) {
                filtered.put(copyValue(event));
            }
        }
        bufferRecoveryEvents(filtered);
    }

    private static int lastSequenceStart(JSONArray haystack, JSONArray needle) {
        if (haystack == null || needle == null || needle.length() == 0
                || needle.length() > haystack.length()) return -1;
        for (int start = haystack.length() - needle.length(); start >= 0; start--) {
            boolean matches = true;
            for (int index = 0; index < needle.length(); index++) {
                if (!sameMessage(haystack.opt(start + index), needle.opt(index))) {
                    matches = false;
                    break;
                }
            }
            if (matches) return start;
        }
        return -1;
    }

    private static boolean sameMessage(Object first, Object second) {
        if (first == second) return true;
        if (!(first instanceof JSONObject) || !(second instanceof JSONObject)) {
            return String.valueOf(first).equals(String.valueOf(second));
        }
        JSONObject a = (JSONObject) first;
        JSONObject b = (JSONObject) second;
        return a.optString("role").equals(b.optString("role"))
                && stableContent(a.opt("content")).equals(stableContent(b.opt("content")))
                && a.optString("tool_call_id", a.optString("_lspilot_tool_call_id"))
                .equals(b.optString("tool_call_id", b.optString("_lspilot_tool_call_id")));
    }

    private static boolean sameArray(JSONArray first, JSONArray second) {
        if (first == null || second == null || first.length() != second.length()) return false;
        for (int index = 0; index < first.length(); index++) {
            if (!sameMessage(first.opt(index), second.opt(index))) return false;
        }
        return true;
    }

    private static String stableContent(Object value) {
        return value == null || JSONObject.NULL.equals(value) ? "" : String.valueOf(value);
    }

    private static int countLeadingSystemMessages(JSONArray messages) {
        int count = 0;
        while (count < messages.length()) {
            JSONObject message = messages.optJSONObject(count);
            if (message == null || !"system".equals(message.optString("role"))) break;
            count++;
        }
        return count;
    }

    private static JSONArray prefix(JSONArray source, int length) {
        JSONArray result = new JSONArray();
        for (int index = 0; index < length && index < source.length(); index++) {
            result.put(copyValue(source.opt(index)));
        }
        return result;
    }

    private static JSONArray copyArray(JSONArray source) {
        JSONArray result = new JSONArray();
        if (source == null) return result;
        for (int index = 0; index < source.length(); index++) {
            result.put(copyValue(source.opt(index)));
        }
        return result;
    }

    private static Object copyValue(Object value) {
        try {
            if (value instanceof JSONObject) return new JSONObject(value.toString());
            if (value instanceof JSONArray) return new JSONArray(value.toString());
        } catch (Throwable ignored) {
            // JSON values in provider messages are normally serializable; keep the value if not.
        }
        return value;
    }

    private static HostAbi requireHostAbi() {
        HostAbi abi = hostAbi;
        if (abi == null) throw new IllegalStateException("Host ABI unavailable");
        return abi;
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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

    public static void main(String[] args) throws Exception {
        JSONArray clean = sanitizeRequestMessagesOrThrow(new JSONArray()
                .put(new JSONObject().put("role", "assistant")
                        .put("content", ENHANCER_MARKER + "\nworking"))
                .put(new JSONObject().put("role", "user").put("content", "continue")
                        .put("_lspilot_host_index", 1)));
        if (clean.length() != 1 || clean.getJSONObject(0).has("_lspilot_host_index")) {
            throw new AssertionError("provider messages must exclude module-only data");
        }
    }

    static void onProviderUsage(long inputTokens, long outputTokens, long cachedTokens,
            long totalTokens) {
        boolean used = compressionUsedForPendingRequest;
        compressionUsedForPendingRequest = false;
        if (used && lastMetrics != null) {
            postCompressionStatus("Provider 已确认压缩上下文参与请求：输入 token "
                    + display(inputTokens) + "，总 token " + display(totalTokens) + "。");
        }
    }

    private static String display(long value) {
        return value < 0 ? "不可用" : Long.toString(value);
    }

    static void postChatStatus(String chatId, String content) {
        postStatus(chatId, RETRY_STATUS_MARKER, content, false);
    }

    static void postCompressionStatus(String content) {
        postCompressionStatus(content, true);
    }

    static void postCompressionStatusQuiet(String content) {
        postCompressionStatus(content, false);
    }

    private static void postCompressionStatus(String content, boolean allowPanel) {
        postStatus(currentChatId, ENHANCER_MARKER, content, allowPanel);
    }

    private static void postStatus(String chatId, String content) {
        postStatus(chatId, ENHANCER_MARKER, content, true);
    }

    private static void postStatus(String chatId, String marker, String content,
            boolean allowPanel) {
        HostAbi abi = hostAbi;
        if (chatId == null || content == null || abi == null
                || abi.repositoryAddMessageMethod == null || shouldDropStatus(content)) return;
        STATUS_EXECUTOR.execute(() -> {
            try {
                Object message = abi.newStatusMessage(
                        "lspilot-enhancer-" + System.currentTimeMillis() + "-"
                                + STATUS_SEQUENCE.incrementAndGet(),
                        "system", marker + "\n" + content, System.currentTimeMillis());
                Object repository = HostAbi.singletonInstance(abi.repositoryClass);
                abi.repositoryAddMessageMethod.invoke(repository, chatId, message);
                refreshCompressionUi(allowPanel);
            } catch (Throwable error) {
                DebugLogger.e("chat status insertion failed", error);
            }
        });
    }

    private static synchronized boolean shouldDropStatus(String content) {
        long now = System.currentTimeMillis();
        if (content.equals(lastStatusFingerprint)
                && now - lastStatusPostAt < STATUS_MIN_INTERVAL_MS) return true;
        lastStatusFingerprint = content;
        lastStatusPostAt = now;
        return false;
    }

    static void refreshCompressionUi() {
        refreshCompressionUi(true);
    }

    private static void refreshCompressionUi(boolean allowPanel) {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                NativeChatTopBarAction.refreshPanel(allowPanel);
                InjectedUiController.refreshChatCompressionOverlay(allowPanel);
            });
        } catch (Throwable error) {
            Log.d(TAG, "compression UI refresh unavailable", error);
        }
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
        final String model;
        final JSONArray messages;

        Snapshot(Object config, String model, JSONArray messages) {
            this.config = config;
            this.model = model;
            this.messages = messages;
        }
    }
}
