package com.lspilot.enhancer;

import java.util.Locale;

public final class ReasoningPolicy {
    public static final String[] SUPPORTED = {"off", "low", "medium", "high", "xhigh", "max"};

    private ReasoningPolicy() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return SUPPORTED[0];
        }
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        return isSupported(candidate) ? candidate : SUPPORTED[0];
    }

    public static boolean isSupported(String value) {
        if (value == null) {
            return false;
        }
        for (String supported : SUPPORTED) {
            if (supported.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public static String fromHostEnumName(String value) {
        if (value == null) {
            return SUPPORTED[0];
        }
        String hostValue = value.trim().toUpperCase(Locale.ROOT);
        if ("OFF".equals(hostValue)) {
            return "off";
        }
        if ("AUTO".equals(hostValue)) {
            return "low";
        }
        if ("LOW".equals(hostValue)) {
            return "medium";
        }
        if ("MEDIUM".equals(hostValue)) {
            return "high";
        }
        if ("HIGH".equals(hostValue)) {
            return "xhigh";
        }
        if ("MAX".equals(hostValue)) {
            return "max";
        }
        return SUPPORTED[0];
    }

    public static String genericWireValue(String value) {
        return normalize(value);
    }

    public static Integer thinkingBudget(String value) {
        String normalized = normalize(value);
        if ("off".equals(normalized)) {
            return null;
        }
        if ("low".equals(normalized)) {
            return Integer.valueOf(1024);
        }
        if ("medium".equals(normalized)) {
            return Integer.valueOf(4096);
        }
        if ("high".equals(normalized)) {
            return Integer.valueOf(8192);
        }
        return Integer.valueOf(16384);
    }
}
