package com.lspilot.enhancer;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Coordinates bounded, chat-scoped retries of the host's last response. */
final class AutoRetryManager {
    private static final int MAX_RETRIES = AutoRetryPolicy.maxRetries();
    private static final long RETRY_START_TIMEOUT_MS = 15_000L;
    private static final long RETRY_PERSIST_INTERVAL_MS = 1_000L;
    private static final Object LOCK = new Object();
    private static final Map<Object, RetryState> STATES = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> INTERNAL_RETRY = new ThreadLocal<>();
    private static volatile Method configuredRetryMethod;

    private AutoRetryManager() {
    }

    static void beginTurn(Object viewModel, String chatId) {
        if (viewModel == null || isBlank(chatId)) return;
        synchronized (LOCK) {
            RetryState previous = STATES.remove(viewModel);
            cancelLocked(previous);
            if (previous != null) previous.active = false;
            STATES.put(viewModel, new RetryState(viewModel, chatId, configuredRetryMethod));
        }
        DebugLogger.i("auto retry turn started chat=" + DebugLogger.id(chatId));
    }

    static void onAttemptStarted(Object viewModel, String chatId) {
        if (viewModel == null || isBlank(chatId) || !ModuleSettings.isEnabled()) return;
        synchronized (LOCK) {
            RetryState state = STATES.get(viewModel);
            if (state == null || !chatId.equals(state.chatId) || !state.active) {
                cancelLocked(state);
                if (state != null) state.active = false;
                state = new RetryState(viewModel, chatId, configuredRetryMethod);
                STATES.put(viewModel, state);
            }
            cancelWatchdogLocked(state);
            state.retryInvocationInFlight = false;
            if (state.pending == null) state.errorObserved = false;
        }
    }

    static void onUserSend(Object viewModel, String chatId) {
        if (!ModuleSettings.isEnabled()) return;
        if (isInternalRetry() || isRetryInvocationInFlight(viewModel, chatId)) {
            DebugLogger.i("user send hook ignored during auto retry chat="
                    + DebugLogger.id(chatId));
            return;
        }
        beginTurn(viewModel, chatId);
    }

    static void captureAttemptMessages(Object viewModel, String chatId, List<?> messages) {
        if (viewModel == null || isBlank(chatId) || messages == null
                || !ModuleSettings.isEnabled()) return;
        onAttemptStarted(viewModel, chatId);
        synchronized (LOCK) {
            RetryState state = STATES.get(viewModel);
            if (state == null || !state.active || !chatId.equals(state.chatId)
                    || state.attemptMessages != null) return;
            state.attemptMessages = new ArrayList<>(messages);
            DebugLogger.i("auto retry context snapshot captured chat="
                    + DebugLogger.id(chatId) + " messages=" + messages.size());
        }
    }
    static List<?> retryRequestMessages(HostAbi abi, Object viewModel, String chatId,
            List<?> messages) {
        if (abi == null || !abi.minified || viewModel == null || isBlank(chatId)
                || messages == null) return null;
        onAttemptStarted(viewModel, chatId);
        List<?> request;
        List<?> preserved;
        synchronized (LOCK) {
            RetryState state = STATES.get(viewModel);
            if (state == null || !state.active || state.retryNumber <= 0
                    || !chatId.equals(state.chatId) || state.retryRequestMessages == null
                    || state.retryHostMessages == null) return null;
            request = new ArrayList<>(state.retryRequestMessages);
            preserved = new ArrayList<>(state.retryHostMessages);
        }
        try {
            if (!abi.replaceStateMessages(viewModel, preserved)) {
                DebugLogger.w("auto retry could not publish preserved tail before stream");
            }
        } catch (Throwable error) {
            DebugLogger.e("failed to publish preserved tail before auto retry stream", error);
        }
        DebugLogger.i("auto retry request starts before failed assistant chat="
                + DebugLogger.id(chatId) + " requestMessages=" + request.size()
                + " preservedMessages=" + preserved.size());
        return request;
    }
    static List<?> restoreAttemptMessages(Object viewModel, String chatId, List<?> messages) {
        if (viewModel == null || isBlank(chatId) || messages == null) return messages;
        synchronized (LOCK) {
            RetryState state = STATES.get(viewModel);
            if (state == null || !state.active || state.retryNumber <= 0
                    || !chatId.equals(state.chatId) || state.attemptMessages == null) {
                return messages;
            }
            DebugLogger.w("auto retry restoring preserved context chat="
                    + DebugLogger.id(chatId) + " currentMessages=" + messages.size()
                    + " restoredMessages=" + state.attemptMessages.size());
            return new ArrayList<>(state.attemptMessages);
        }
    }



    static List<?> retryContextBeforeTarget(List<?> messages, int targetIndex) {
        if (messages == null) return null;
        if (targetIndex < 0 || targetIndex > messages.size()) return new ArrayList<>(messages);
        return new ArrayList<>(messages.subList(0, targetIndex));
    }
    static void prepareHostRetry(HostAbi abi, Object viewModel, String chatId) {
        if (abi == null || !abi.minified || viewModel == null || isBlank(chatId)) return;
        try {
            Object hostState = abi.currentState(viewModel);
            List<?> currentMessages = abi.stateMessages(hostState);
            if (currentMessages == null || currentMessages.isEmpty()) return;
            List<?> snapshot = new ArrayList<>(currentMessages);
            int failedAssistantIndex = -1;
            synchronized (LOCK) {
                RetryState state = STATES.get(viewModel);
                if (state == null || !state.active || !state.retryInvocationInFlight
                        || !chatId.equals(state.chatId)) return;
                failedAssistantIndex = findTargetIndex(abi, snapshot, state.targetMessageId);
                if (failedAssistantIndex < 0) failedAssistantIndex = findLastAssistant(abi, snapshot);
                if (failedAssistantIndex < 0) {
                    DebugLogger.w("auto retry failed to locate failed assistant chat="
                            + DebugLogger.id(chatId));
                    return;
                }
                Object target = snapshot.get(failedAssistantIndex);
                String targetId = abi.messageId(target);
                state.targetMessageId = targetId;
                state.targetMessageIndex = failedAssistantIndex;
                state.attemptMessages = snapshot;
                state.retryHostMessages = snapshot;
                state.retryRequestMessages = retryContextBeforeTarget(snapshot, failedAssistantIndex);
                state.retryAbi = abi;
                state.responseMerged = false;
                state.lastRetryPersistAtMs = 0L;
            }
            boolean persisted = false;
            try {
                abi.persistMessages(chatId, snapshot);
                persisted = true;
            } catch (Throwable error) {
                DebugLogger.e("failed to preserve host repository before auto retry", error);
            }
            DebugLogger.i("auto retry host snapshot captured chat=" + DebugLogger.id(chatId)
                    + " messages=" + snapshot.size() + " failedAssistantIndex="
                    + failedAssistantIndex + " persisted=" + persisted);
        } catch (Throwable error) {
            DebugLogger.e("failed to capture host state before auto retry", error);
        }
    }

private static int findTargetIndex(HostAbi abi, List<?> messages, String targetId) {
        if (abi == null || messages == null || isBlank(targetId)) return -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            try {
                if (targetId.equals(abi.messageId(messages.get(index)))) return index;
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static int findLastAssistant(HostAbi abi, List<?> messages) {
        if (abi == null || messages == null) return -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            try {
                if ("assistant".equals(abi.messageRole(messages.get(index)))) return index;
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static void rememberFailureTarget(HostAbi abi, Object viewModel, String chatId) {
        if (abi == null || viewModel == null || isBlank(chatId)) return;
        try {
            List<?> messages = abi.stateMessages(abi.currentState(viewModel));
            int index = findLastAssistant(abi, messages);
            if (index < 0) return;
            String id = abi.messageId(messages.get(index));
            synchronized (LOCK) {
                RetryState state = STATES.get(viewModel);
                if (state != null && state.active && chatId.equals(state.chatId)
                        && !isBlank(id) && isBlank(state.targetMessageId)) {
                    state.targetMessageId = id;
                }
            }
        } catch (Throwable error) {
            DebugLogger.e("failed to remember failed assistant target", error);
        }
    }

    private static Object findGeneratedAssistant(HostAbi abi, List<?> currentMessages,
            List<?> originalMessages) {
        if (abi == null || currentMessages == null || originalMessages == null) return null;
        for (int index = currentMessages.size() - 1; index >= 0; index--) {
            Object candidate = currentMessages.get(index);
            try {
                if (!"assistant".equals(abi.messageRole(candidate))) continue;
                String id = abi.messageId(candidate);
                if (!isBlank(id) && !containsMessageId(abi, originalMessages, id)) return candidate;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean containsMessageId(HostAbi abi, List<?> messages, String id) {
        if (abi == null || messages == null || isBlank(id)) return false;
        for (Object message : messages) {
            try {
                if (id.equals(abi.messageId(message))) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void mergeRetryState(HostAbi abi, Object viewModel, String chatId,
            boolean persist) {
        if (abi == null || viewModel == null || isBlank(chatId)) return;
        List<?> original;
        String targetId;
        int storedTargetIndex;
        boolean responseMerged;
        long persistTimeMs = System.currentTimeMillis();
        boolean persistMerged = persist;
        synchronized (LOCK) {
            RetryState state = STATES.get(viewModel);
            if (state == null || !state.active || state.retryNumber <= 0
                    || !chatId.equals(state.chatId) || state.retryHostMessages == null) return;
            original = new ArrayList<>(state.retryHostMessages);
            targetId = state.targetMessageId;
            storedTargetIndex = state.targetMessageIndex;
            responseMerged = state.responseMerged;
            if (!persistMerged
                    && persistTimeMs - state.lastRetryPersistAtMs >= RETRY_PERSIST_INTERVAL_MS) {
                persistMerged = true;
            }
        }
        try {
            List<?> current = abi.stateMessages(abi.currentState(viewModel));
            Object generated = findGeneratedAssistant(abi, current, original);
            if (generated == null) {
                if (persist && responseMerged) {
                    try {
                        abi.persistMessages(chatId, original);
                    } catch (Throwable error) {
                        DebugLogger.e("failed to persist final in-place retry response", error);
                    }
                }
                return;
            }
            int targetIndex = findTargetIndex(abi, original, targetId);
            if (targetIndex < 0 && storedTargetIndex >= 0
                    && storedTargetIndex < original.size()) {
                targetIndex = storedTargetIndex;
            }
            if (targetIndex < 0) return;
            String content = abi.messageContent(generated);
            Object replacement = abi.copyMessageWithContent(original.get(targetIndex), content);
            List<Object> merged = new ArrayList<>(original);
            merged.set(targetIndex, replacement);
            if (!abi.replaceStateMessages(viewModel, merged)) return;
            synchronized (LOCK) {
                RetryState state = STATES.get(viewModel);
                if (state != null && state.active && chatId.equals(state.chatId)) {
                    state.retryHostMessages = merged;
                    state.attemptMessages = merged;
                    state.responseMerged = true;
                }
            }
            if (persistMerged) {
                try {
                    abi.persistMessages(chatId, merged);
                    synchronized (LOCK) {
                        RetryState state = STATES.get(viewModel);
                        if (state != null && state.active && chatId.equals(state.chatId)) {
                            state.lastRetryPersistAtMs = persistTimeMs;
                        }
                    }
                } catch (Throwable error) {
                    DebugLogger.e("failed to persist generated failed-assistant replacement", error);
                }
            }
            DebugLogger.i("auto retry updated failed assistant in place chat="
                    + DebugLogger.id(chatId) + " targetIndex=" + targetIndex
                    + " tailMessages=" + (merged.size() - targetIndex - 1));
        } catch (Throwable error) {
            DebugLogger.e("failed to merge auto retry response into failed assistant", error);
        }
    }
    static void restoreHostRetry(HostAbi abi, Object viewModel, String chatId) {
        if (abi == null || !abi.minified || viewModel == null || isBlank(chatId)) return;
        List<?> snapshot;
        synchronized (LOCK) {
            RetryState state = STATES.get(viewModel);
            if (state == null || !state.active || !chatId.equals(state.chatId)
                    || state.retryHostMessages == null) return;
            snapshot = new ArrayList<>(state.retryHostMessages);
        }
        try {
            boolean restored = abi.replaceStateMessages(viewModel, snapshot);
            if (!restored) {
                throw new IllegalStateException("host StateFlow rejected message restoration");
            }
            DebugLogger.i("auto retry restored host state chat=" + DebugLogger.id(chatId)
                    + " messages=" + snapshot.size());
        } catch (Throwable error) {
            DebugLogger.e("failed to restore host state after auto retry", error);
        }
    }

    private static boolean isRetryInvocationInFlight(Object viewModel, String chatId) {
        if (viewModel == null || isBlank(chatId)) return false;
        synchronized (LOCK) {
            RetryState state = STATES.get(viewModel);
            return state != null && state.active && state.retryInvocationInFlight
                    && chatId.equals(state.chatId);
        }
    }

    static void onChatLoaded(Object viewModel, String chatId) {
        if (viewModel == null || isBlank(chatId)) return;
        synchronized (LOCK) {
            Object[] owners = STATES.keySet().toArray();
            for (Object owner : owners) {
                RetryState state = STATES.get(owner);
                if (state != null && (owner != viewModel || !chatId.equals(state.chatId))) {
                    STATES.remove(owner);
                    cancelLocked(state);
                    state.active = false;
                }
            }
        }
    }

    static Object wrapStreamCallback(Object callback, Object viewModel, String chatId) {
        return wrapStreamCallback(callback, viewModel, chatId, null);
    }

    static Object wrapStreamCallback(Object callback, Object viewModel, String chatId, HostAbi abi) {
        if (callback == null || viewModel == null || isBlank(chatId)) return callback;
        onAttemptStarted(viewModel, chatId);
        try {
            ClassLoader loader = callback.getClass().getClassLoader();
            Class<?> function1 = Class.forName("kotlin.jvm.functions.Function1", false, loader);
            if (!function1.isInstance(callback) || Proxy.isProxyClass(callback.getClass())) {
                return callback;
            }
            return Proxy.newProxyInstance(loader, new Class<?>[]{function1},
                    new StreamCallback(callback, viewModel, chatId, abi));
        } catch (Throwable error) {
            DebugLogger.e("failed to wrap host stream callback", error);
            return callback;
        }
    }

    static void onRepositoryMessage(HostAbi abi, String chatId, String role, String content,
            Object message) {
        if (!"assistant".equals(role) || !isFailureContent(content)) return;
        RetryState state = findByChat(chatId);
        if (state != null) {
            if (abi != null && message != null) {
                String messageId = abi.messageId(message);
                if (!isBlank(messageId)) {
                    synchronized (LOCK) {
                        if (state.active && chatId.equals(state.chatId)) {
                            state.targetMessageId = messageId;
                        }
                    }
                }
            }
            scheduleRetry(state, extractFailureReason(content));
        }
    }

    private static boolean isFailureContent(String content) {
        if (content == null) return false;
        String value = content.trim();
        return value.startsWith("请求失败:") || value.startsWith("请求失败：")
                || value.startsWith("Request failed:") || value.startsWith("Request error:");
    }

    private static String extractFailureReason(String content) {
        if (content == null) return "未知错误";
        String value = content.trim();
        int colon = Math.max(value.indexOf(':'), value.indexOf('：'));
        if (colon >= 0 && colon + 1 < value.length()) {
            String reason = value.substring(colon + 1).trim();
            if (!reason.isEmpty()) return reason;
        }
        return value;
    }

    static void cancelForStop(Object viewModel, String chatId) {
        RetryState state;
        synchronized (LOCK) {
            state = STATES.get(viewModel);
            if (state == null || (chatId != null && !chatId.equals(state.chatId))) return;
            STATES.remove(viewModel);
            cancelLocked(state);
            state.active = false;
        }
        DebugLogger.i("auto retry cancelled by host stop chat=" + DebugLogger.id(state.chatId));
    }

    static boolean isInternalRetry() {
        return Boolean.TRUE.equals(INTERNAL_RETRY.get());
    }

    static void invokeRetry(Method retryMethod, Object viewModel, RetryState state) {
        if (retryMethod == null || viewModel == null || state == null) {
            clearRetryInvocationFlag(state);
            scheduleRetry(state, "retry method unavailable");
            return;
        }
        try {
            INTERNAL_RETRY.set(Boolean.TRUE);
            retryMethod.invoke(viewModel);
            DebugLogger.i("auto retry request invoked chat=" + DebugLogger.id(state.chatId)
                    + " retry=" + state.retryNumber + "/" + MAX_RETRIES);
        } catch (Throwable error) {
            clearRetryInvocationFlag(state);
            Throwable cause = error instanceof InvocationTargetException
                    && ((InvocationTargetException) error).getTargetException() != null
                    ? ((InvocationTargetException) error).getTargetException() : error;
            DebugLogger.e("auto retry invocation failed", cause);
            scheduleRetry(state, "invoke failed: " + safeMessage(cause));
        } finally {
            INTERNAL_RETRY.remove();
        }
    }

    private static void clearRetryInvocationFlag(RetryState state) {
        if (state == null) return;
        synchronized (LOCK) {
            state.retryInvocationInFlight = false;
        }
    }

    private static void onStreamEvent(HostAbi abi, Object viewModel, String chatId, Object event) {
        if (event == null) return;
        String name = event.getClass().getName();
        if (HostAbi.isStreamErrorEvent(event, name)) {
            rememberFailureTarget(abi, viewModel, chatId);
            RetryState state = findByChat(chatId);
            if (state != null) scheduleRetry(state, eventMessage(event));
        } else if (HostAbi.isStreamDoneEvent(name)) {
            onAttemptSuccess(viewModel, chatId);
        }
    }

    private static String eventMessage(Object event) {
        for (String memberName : new String[]{
                "getMessage", "message", "getError", "error", "getCause", "cause", "a", "b"}) {
            String value = readEventMember(event, memberName);
            if (value != null) return value;
        }
        String text;
        try {
            text = String.valueOf(event);
        } catch (Throwable ignored) {
            text = null;
        }
        if (text != null && !text.trim().isEmpty()
                && !text.equals(event.getClass().getName())) {
            return DebugLogger.redact(text);
        }
        return "宿主流请求返回错误事件（" + event.getClass().getSimpleName() + "）";
    }

    private static String readEventMember(Object event, String memberName) {
        if (event == null) return null;
        try {
            Method method;
            try {
                method = event.getClass().getMethod(memberName);
            } catch (NoSuchMethodException ignored) {
                method = event.getClass().getDeclaredMethod(memberName);
                method.setAccessible(true);
            }
            return valueMessage(method.invoke(event));
        } catch (Throwable ignored) {
            // Some Kotlin error carriers expose fields instead of accessors.
        }
        try {
            java.lang.reflect.Field field = event.getClass().getDeclaredField(memberName);
            field.setAccessible(true);
            return valueMessage(field.get(event));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String valueMessage(Object value) {
        if (value == null) return null;
        if (value instanceof Throwable) return safeMessage((Throwable) value);
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text)
                ? null : DebugLogger.redact(text);
    }

    private static void onAttemptSuccess(Object viewModel, String chatId) {
        RetryState state;
        synchronized (LOCK) {
            state = STATES.get(viewModel);
            if (state == null || !chatId.equals(state.chatId) || !state.active) return;
            STATES.remove(viewModel);
            cancelLocked(state);
            state.active = false;
        }
        DebugLogger.i("auto retry request completed chat=" + DebugLogger.id(state.chatId)
                + " retries=" + state.retryNumber);
    }

    private static RetryState findByChat(String chatId) {
        if (isBlank(chatId)) return null;
        synchronized (LOCK) {
            for (RetryState state : STATES.values()) {
                if (state != null && state.active && chatId.equals(state.chatId)) return state;
            }
        }
        return null;
    }

    private static void scheduleRetry(RetryState state, String reason) {
        if (state == null || !ModuleSettings.isEnabled()) return;
        long delay;
        int retryNumber;
        synchronized (LOCK) {
            Object owner = state.viewModel.get();
            if (!state.active || state.errorObserved || owner == null
                    || STATES.get(owner) != state) return;
            cancelWatchdogLocked(state);
            if (state.retryNumber >= MAX_RETRIES) {
                state.errorObserved = true;
                STATES.remove(state.viewModel.get());
                state.active = false;
                cancelLocked(state);
                DebugLogger.w("auto retry exhausted chat=" + DebugLogger.id(state.chatId));
                return;
            }
            state.errorObserved = true;
            retryNumber = ++state.retryNumber;
            delay = AutoRetryPolicy.delayForRetry(retryNumber);
            state.pending = new Runnable() {
                @Override
                public void run() {
                    runRetry(state, retryNumber);
                }
            };
            mainHandler().postDelayed(state.pending, delay);
        }
        DebugLogger.w("auto retry scheduled chat=" + DebugLogger.id(state.chatId)
                + " retry=" + retryNumber + "/" + MAX_RETRIES
                + " delayMs=" + delay + " reason=" + DebugLogger.redact(reason));
    }

    private static void runRetry(RetryState state, int retryNumber) {
        synchronized (LOCK) {
            Object current = state.viewModel.get();
            if (!state.active || current == null || STATES.get(current) != state) return;
            state.pending = null;
            state.errorObserved = false;
            state.retryInvocationInFlight = true;
            int generation = ++state.attemptGeneration;
            state.watchdog = new Runnable() {
                @Override
                public void run() {
                    onRetryStartTimeout(state, generation);
                }
            };
            mainHandler().postDelayed(state.watchdog, RETRY_START_TIMEOUT_MS);
        }
        Object viewModel = state.viewModel.get();
        if (viewModel == null) return;
        Method retryMethod = state.retryMethod;
        invokeRetry(retryMethod, viewModel, state);
    }

    private static void onRetryStartTimeout(RetryState state, int generation) {
        Object owner;
        HostAbi abi;
        synchronized (LOCK) {
            owner = state.viewModel.get();
            if (!state.active || owner == null || STATES.get(owner) != state
                    || state.attemptGeneration != generation || state.watchdog == null) return;
            state.watchdog = null;
            state.errorObserved = false;
            abi = state.retryAbi;
        }
        if (abi != null) restoreHostRetry(abi, owner, state.chatId);
        scheduleRetry(state, "宿主重试入口未在 15 秒内启动流请求");
    }

    private static void cancelLocked(RetryState state) {
        if (state != null && state.pending != null) {
            mainHandler().removeCallbacks(state.pending);
            state.pending = null;
        }
        cancelWatchdogLocked(state);
    }

    private static void cancelWatchdogLocked(RetryState state) {
        if (state != null && state.watchdog != null) {
            mainHandler().removeCallbacks(state.watchdog);
            state.watchdog = null;
        }
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) return "未知错误";
        String compact = reason.replace('\n', ' ').replace('\r', ' ').trim();
        return DebugLogger.redact(compact);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "未知错误";
        Throwable current = error;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            String value = current.getMessage();
            if (value != null && !value.trim().isEmpty()) {
                return DebugLogger.redact(value);
            }
            current = current.getCause();
        }
        return error.getClass().getSimpleName();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static Handler mainHandler() {
        return MainHandlerHolder.INSTANCE;
    }

    static void configure(Method retryMethod) {
        configuredRetryMethod = retryMethod;
    }

    static void setRetryMethod(Object viewModel, Method retryMethod) {
        if (viewModel == null) return;
        synchronized (LOCK) {
            RetryState state = STATES.get(viewModel);
            if (state != null) state.retryMethod = retryMethod;
        }
    }

    private static final class MainHandlerHolder {
        static final Handler INSTANCE = new Handler(Looper.getMainLooper());
    }

    private static final class RetryState {
        final WeakReference<Object> viewModel;
        final String chatId;
        int retryNumber;
        boolean active = true;
        boolean errorObserved;
        int attemptGeneration;
        boolean retryInvocationInFlight;
        int targetMessageIndex = -1;
        String targetMessageId;
        boolean responseMerged;
        long lastRetryPersistAtMs;
        HostAbi retryAbi;
        List<?> attemptMessages;
        List<?> retryHostMessages;
        List<?> retryRequestMessages;
        Runnable pending;
        Runnable watchdog;
        Method retryMethod;

        RetryState(Object viewModel, String chatId, Method retryMethod) {
            this.viewModel = new WeakReference<>(viewModel);
            this.chatId = chatId;
            this.retryMethod = retryMethod;
        }
    }

    private static final class StreamCallback implements InvocationHandler {
        private final Object delegate;
        private final Object viewModel;
        private final String chatId;
        private final HostAbi abi;

        StreamCallback(Object delegate, Object viewModel, String chatId, HostAbi abi) {
            this.delegate = delegate;
            this.viewModel = viewModel;
            this.chatId = chatId;
            this.abi = abi;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"invoke".equals(method.getName()) || args == null || args.length != 1) {
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException error) {
                    throw error.getTargetException();
                }
            }
            Object event = args[0];
            String eventName = event == null ? null : event.getClass().getName();
            boolean errorEvent = HostAbi.isStreamErrorEvent(event, eventName);
            boolean doneEvent = HostAbi.isStreamDoneEvent(eventName);
            if (errorEvent) {
                // Capture the target before the host may replace the transient stream message.
                onStreamEvent(abi, viewModel, chatId, event);
            }
            Object result = null;
            Throwable delegateError = null;
            try {
                result = method.invoke(delegate, args);
            } catch (InvocationTargetException error) {
                delegateError = error.getTargetException();
            }
            mergeRetryState(abi, viewModel, chatId, errorEvent || doneEvent);
            if (doneEvent) {
                onStreamEvent(abi, viewModel, chatId, event);
            }
            if (delegateError != null) throw delegateError;
            return result;
        }
    }
}
