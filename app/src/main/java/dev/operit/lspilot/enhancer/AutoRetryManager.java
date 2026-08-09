package dev.operit.lspilot.enhancer;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Coordinates bounded, chat-scoped retries of the host's last response. */
final class AutoRetryManager {
    private static final int MAX_RETRIES = AutoRetryPolicy.maxRetries();
    private static final long RETRY_START_TIMEOUT_MS = 15_000L;
    private static final String ERROR_EVENT = "nyb$c";
    private static final String DONE_EVENT = "nyb$b";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
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
            if (state.pending == null) state.errorObserved = false;
        }
    }

    static void onUserSend(Object viewModel, String chatId) {
        if (!ModuleSettings.isEnabled()) return;
        beginTurn(viewModel, chatId);
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
        if (callback == null || viewModel == null || isBlank(chatId)) return callback;
        onAttemptStarted(viewModel, chatId);
        try {
            ClassLoader loader = callback.getClass().getClassLoader();
            Class<?> function1 = Class.forName("kotlin.jvm.functions.Function1", false, loader);
            if (!function1.isInstance(callback) || Proxy.isProxyClass(callback.getClass())) {
                return callback;
            }
            return Proxy.newProxyInstance(loader, new Class<?>[]{function1},
                    new StreamCallback(callback, viewModel, chatId));
        } catch (Throwable error) {
            DebugLogger.e("failed to wrap host stream callback", error);
            return callback;
        }
    }

    static void onRepositoryMessage(String chatId, String role, String content) {
        if (!"assistant".equals(role) || content == null
                || !content.startsWith("请求失败:")) return;
        RetryState state = findByChat(chatId);
        if (state != null) scheduleRetry(state, content);
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
        ManualCompressionManager.postChatStatus(state.chatId,
                "已按宿主停止操作取消自动重试。");
        DebugLogger.i("auto retry cancelled by host stop chat=" + DebugLogger.id(state.chatId));
    }

    static boolean isInternalRetry() {
        return Boolean.TRUE.equals(INTERNAL_RETRY.get());
    }

    static void invokeRetry(Method retryMethod, Object viewModel, RetryState state) {
        if (retryMethod == null || viewModel == null || state == null) {
            scheduleRetry(state, "retry method unavailable");
            return;
        }
        try {
            INTERNAL_RETRY.set(Boolean.TRUE);
            retryMethod.invoke(viewModel);
            DebugLogger.i("auto retry request invoked chat=" + DebugLogger.id(state.chatId)
                    + " retry=" + state.retryNumber + "/" + MAX_RETRIES);
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                    && ((InvocationTargetException) error).getTargetException() != null
                    ? ((InvocationTargetException) error).getTargetException() : error;
            DebugLogger.e("auto retry invocation failed", cause);
            scheduleRetry(state, "invoke failed: " + safeMessage(cause));
        } finally {
            INTERNAL_RETRY.remove();
        }
    }

    private static void onStreamEvent(Object viewModel, String chatId, Object event) {
        if (event == null) return;
        String name = event.getClass().getName();
        if (ERROR_EVENT.equals(name) || isNamedError(name)) {
            RetryState state = findByChat(chatId);
            if (state != null) scheduleRetry(state, eventMessage(event));
        } else if (DONE_EVENT.equals(name) || isNamedDone(name)) {
            onAttemptSuccess(viewModel, chatId);
        }
    }

    private static boolean isNamedError(String name) {
        return name != null && (name.endsWith("$Error") || name.endsWith(".Error"));
    }

    private static boolean isNamedDone(String name) {
        return name != null && (name.endsWith("$Done") || name.endsWith(".Done"));
    }

    private static String eventMessage(Object event) {
        for (String methodName : new String[]{"getMessage", "message", "a"}) {
            try {
                Method method;
                try {
                    method = event.getClass().getMethod(methodName);
                } catch (NoSuchMethodException ignored) {
                    method = event.getClass().getDeclaredMethod(methodName);
                    method.setAccessible(true);
                }
                Object value = method.invoke(event);
                if (value != null && !String.valueOf(value).trim().isEmpty()) {
                    return String.valueOf(value);
                }
            } catch (Throwable ignored) {
            }
        }
        return "宿主流请求返回错误事件";
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
        if (state.retryNumber > 0) {
            ManualCompressionManager.postChatStatus(state.chatId,
                    "自动重试成功（第 " + state.retryNumber + "/" + MAX_RETRIES + " 次）。");
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
                ManualCompressionManager.postChatStatus(state.chatId,
                        "自动重试已耗尽（" + MAX_RETRIES + "/" + MAX_RETRIES + "），请手动重试。");
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
            MAIN_HANDLER.postDelayed(state.pending, delay);
        }
        ManualCompressionManager.postChatStatus(state.chatId,
                "对话出错，" + AutoRetryPolicy.formatDelay(delay) + "后自动重试（第 "
                        + retryNumber + "/" + MAX_RETRIES + " 次）。");
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
            int generation = ++state.attemptGeneration;
            state.watchdog = new Runnable() {
                @Override
                public void run() {
                    onRetryStartTimeout(state, generation);
                }
            };
            MAIN_HANDLER.postDelayed(state.watchdog, RETRY_START_TIMEOUT_MS);
        }
        Object viewModel = state.viewModel.get();
        if (viewModel == null) return;
        ManualCompressionManager.postChatStatus(state.chatId,
                "正在自动重试（第 " + retryNumber + "/" + MAX_RETRIES + " 次）。");
        Method retryMethod = state.retryMethod;
        invokeRetry(retryMethod, viewModel, state);
    }

    private static void onRetryStartTimeout(RetryState state, int generation) {
        synchronized (LOCK) {
            Object owner = state.viewModel.get();
            if (!state.active || owner == null || STATES.get(owner) != state
                    || state.attemptGeneration != generation || state.watchdog == null) return;
            state.watchdog = null;
            state.errorObserved = false;
        }
        scheduleRetry(state, "宿主重试入口未在 15 秒内启动流请求");
    }

    private static void cancelLocked(RetryState state) {
        if (state != null && state.pending != null) {
            MAIN_HANDLER.removeCallbacks(state.pending);
            state.pending = null;
        }
        cancelWatchdogLocked(state);
    }

    private static void cancelWatchdogLocked(RetryState state) {
        if (state != null && state.watchdog != null) {
            MAIN_HANDLER.removeCallbacks(state.watchdog);
            state.watchdog = null;
        }
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.trim().isEmpty() ? "未知错误" : DebugLogger.redact(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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

    private static final class RetryState {
        final WeakReference<Object> viewModel;
        final String chatId;
        int retryNumber;
        boolean active = true;
        boolean errorObserved;
        int attemptGeneration;
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

        StreamCallback(Object delegate, Object viewModel, String chatId) {
            this.delegate = delegate;
            this.viewModel = viewModel;
            this.chatId = chatId;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                Object result = method.invoke(delegate, args);
                if ("invoke".equals(method.getName()) && args != null && args.length == 1) {
                    onStreamEvent(viewModel, chatId, args[0]);
                }
                return result;
            } catch (InvocationTargetException error) {
                throw error.getTargetException();
            }
        }
    }
}