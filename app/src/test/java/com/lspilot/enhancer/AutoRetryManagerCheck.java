package com.lspilot.enhancer;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public final class AutoRetryManagerCheck {
    private AutoRetryManagerCheck() {
    }

    public static void main(String[] args) throws Exception {
        assertTrue(HostAbi.isStreamErrorEvent(
                new IllegalStateException("socket closed"), IllegalStateException.class.getName()),
                "Throwable must be treated as an error event");

        Failure failure = new Failure(new IllegalStateException("upstream rejected request"));
        assertTrue(HostAbi.isStreamErrorEvent(failure, failure.getClass().getName()),
                "$Failure carrier must be treated as an error event");
        assertEquals("upstream rejected request", invokeString("eventMessage",
                new Class<?>[]{Object.class}, failure));

        Failed failed = new Failed("HTTP 502: context window exceeded");
        assertTrue(HostAbi.isStreamErrorEvent(failed, failed.getClass().getName()),
                "$Failed carrier must be treated as an error event");
        assertEquals("HTTP 502: context window exceeded", invokeString("eventMessage",
                new Class<?>[]{Object.class}, failed));

        assertTrue(invokeBoolean("isFailureContent", new Class<?>[]{String.class},
                "Request error: connection reset"), "English error prefix must be recognized");
        assertEquals("connection reset", invokeString("extractFailureReason",
                new Class<?>[]{String.class}, "Request error: connection reset"));
        assertEquals("上游拒绝请求", invokeString("extractFailureReason",
                new Class<?>[]{String.class}, "请求失败：上游拒绝请求"));
        List<String> history = Arrays.asList(
                "user-before", "assistant-previous", "assistant-failed",
                "user-after", "assistant-after");
        List<?> request = AutoRetryManager.retryContextBeforeTarget(history, 2);
        if (!Arrays.asList("user-before", "assistant-previous").equals(request)) {
            throw new AssertionError("retry request must stop before failed assistant: " + request);
        }
        if (request.size() >= history.size()) {
            throw new AssertionError("retry request must exclude failed assistant and tail");
        }
        if (!AutoRetryManager.retryContextBeforeTarget(history, 0).isEmpty()) {
            throw new AssertionError("retry request before first message must be empty");
        }
    }

    private static boolean invokeBoolean(String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        return (Boolean) method(name, parameterTypes).invoke(null, args);
    }

    private static String invokeString(String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        return (String) method(name, parameterTypes).invoke(null, args);
    }

    private static Method method(String name, Class<?>... parameterTypes) throws Exception {
        Method method = AutoRetryManager.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }

    private static final class Failure {
        private final Throwable cause;

        Failure(Throwable cause) {
            this.cause = cause;
        }
    }

    private static final class Failed {
        private final String error;

        Failed(String error) {
            this.error = error;
        }
    }
}