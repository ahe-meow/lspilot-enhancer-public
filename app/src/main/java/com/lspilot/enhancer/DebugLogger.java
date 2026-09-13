package com.lspilot.enhancer;

import android.util.Log;

/** Emits allowlisted, capability-level diagnostics without exposing host data. */
public final class DebugLogger {
    private static final String LOG_TAG = "LSPilot";

    private DebugLogger() {
    }

    public static void capability(String capability, String event, Object... values) {
        String line = safeCapability(capability) + ":" + safeEvent(event);
        if (values != null) {
            for (Object value : values) {
                line = line + " " + safeValue(value);
            }
        }
        try {
            Log.i(LOG_TAG, line);
        } catch (Throwable ignored) {
            // Logging must never become a host-facing failure.
        }
    }

    private static String safeCapability(String value) {
        if ("lifecycle".equals(value)
                || "routing".equals(value)
                || "reasoningSource".equals(value)
                || "menuLabels".equals(value)
                || "genericRequest".equals(value)
                || "thinkingRequest".equals(value)) {
            return value;
        }
        return "unknown";
    }

    private static String safeEvent(String value) {
        if ("module_loaded".equals(value)
                || "package_accepted".equals(value)
                || "package_rejected".equals(value)
                || "process_rejected".equals(value)
                || "process_accepted".equals(value)
                || "source_paths".equals(value)
                || "candidates".equals(value)
                || "ready".equals(value)
                || "installed".equals(value)
                || "disabled".equals(value)
                || "hot_reloading".equals(value)
                || "hot_reloaded".equals(value)) {
            return value;
        }
        return "unknown";
    }

    private static String safeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue() ? "true" : "false";
        }
        if (value instanceof Byte) {
            return String.valueOf(((Byte) value).byteValue());
        }
        if (value instanceof Short) {
            return String.valueOf(((Short) value).shortValue());
        }
        if (value instanceof Integer) {
            return String.valueOf(((Integer) value).intValue());
        }
        if (value instanceof Long) {
            return String.valueOf(((Long) value).longValue());
        }
        if (value instanceof Float) {
            return String.valueOf(((Float) value).floatValue());
        }
        if (value instanceof Double) {
            return String.valueOf(((Double) value).doubleValue());
        }
        if (value instanceof Throwable) {
            return "exception=" + safeExceptionName((Throwable) value);
        }
        return "redacted";
    }

    private static String safeExceptionName(Throwable exception) {
        if (exception == null) {
            return "Unknown";
        }
        String name = exception.getClass().getSimpleName();
        if (name == null || name.isEmpty()) {
            return "Unknown";
        }
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (!isSafeNameCharacter(character)) {
                return "Unknown";
            }
        }
        return name;
    }

    private static boolean isSafeNameCharacter(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '_' || character == '$';
    }
}
