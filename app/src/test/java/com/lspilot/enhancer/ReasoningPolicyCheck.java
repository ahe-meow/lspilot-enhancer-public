package com.lspilot.enhancer;

import java.lang.reflect.Method;

public final class ReasoningPolicyCheck {
    public ReasoningPolicyCheck() {
    }

    @org.junit.Test
    public void runsAssertions() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        String[] expectedValues = {"off", "low", "medium", "high", "xhigh", "max"};
        Integer[] expectedBudgets = {
                null,
                Integer.valueOf(1024),
                Integer.valueOf(4096),
                Integer.valueOf(8192),
                Integer.valueOf(16384),
                Integer.valueOf(16384)
        };

        assert ReasoningPolicy.SUPPORTED.length == expectedValues.length;
        for (int i = 0; i < expectedValues.length; i++) {
            String value = expectedValues[i];
            assert value.equals(ReasoningPolicy.SUPPORTED[i]);
            assert ReasoningPolicy.isSupported(value);
            assert value.equals(ReasoningPolicy.normalize(value));
            assert value.equals(ReasoningPolicy.genericWireValue(value));
            Integer actualBudget = ReasoningPolicy.thinkingBudget(value);
            assert expectedBudgets[i] == null
                    ? actualBudget == null
                    : expectedBudgets[i].equals(actualBudget);
        }

        String[] hostValues = {"OFF", "AUTO", "LOW", "MEDIUM", "HIGH", "MAX", "invalid"};
        String[] expectedPolicies = {"off", "low", "medium", "high", "xhigh", "max", "off"};
        for (int i = 0; i < hostValues.length; i++) {
            assert expectedPolicies[i].equals(fromHostEnumName(hostValues[i]));
        }
        assert "off".equals(fromHostEnumName(null));

        assert "off".equals(ReasoningPolicy.normalize(null));
        assert "off".equals(ReasoningPolicy.normalize("AUTO"));
        assert "off".equals(ReasoningPolicy.normalize("unknown"));
        assert !ReasoningPolicy.isSupported(null);
        assert !ReasoningPolicy.isSupported("AUTO");
        assert "max".equals(ReasoningPolicy.genericWireValue("max"));
        assert Integer.valueOf(16384).equals(ReasoningPolicy.thinkingBudget("max"));
    }

    private static String fromHostEnumName(String value) {
        try {
            Method method = ReasoningPolicy.class.getDeclaredMethod(
                    "fromHostEnumName", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, value);
        } catch (Throwable exception) {
            throw new AssertionError("host policy mapping is missing", exception);
        }
    }
}
