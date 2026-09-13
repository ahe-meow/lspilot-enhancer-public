package com.lspilot.enhancer;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public final class ReasoningMenuHookCheck {
    public ReasoningMenuHookCheck() {
    }

    @Test
    public void runsFocusedContracts() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        Map<Integer, String> labels = new HashMap<Integer, String>();
        labels.put(Integer.valueOf(10), "off");
        labels.put(Integer.valueOf(11), "low");
        labels.put(Integer.valueOf(12), "medium");
        labels.put(Integer.valueOf(13), "high");
        labels.put(Integer.valueOf(14), "xhigh");
        labels.put(Integer.valueOf(15), "max");
        labels.put(Integer.valueOf(16), "off");

        Assert.assertEquals("off", ReasoningMenuHook.replacementFor(10, labels));
        Assert.assertEquals("xhigh", ReasoningMenuHook.replacementFor(14, labels));
        Assert.assertEquals("max", ReasoningMenuHook.replacementFor(15, labels));
        Assert.assertEquals("off", ReasoningMenuHook.replacementFor(16, labels));
        Assert.assertNull(ReasoningMenuHook.replacementFor(99, labels));
        Assert.assertNull(ReasoningMenuHook.replacementFor(10, null));

        Assert.assertTrue(ReasoningMenuHook.isTargetMenuCall(
                new StackTraceElement[]{
                        new StackTraceElement("y71", "Z", "y71.smali", 1)},
                "y71", "Z", "A"));
        Assert.assertTrue(ReasoningMenuHook.isTargetMenuCall(
                new StackTraceElement[]{
                        new StackTraceElement("y71", "A", "y71.smali", 1)},
                "y71", "Z", "A"));
        Assert.assertFalse(ReasoningMenuHook.isTargetMenuCall(
                new StackTraceElement[]{
                        new StackTraceElement("other", "Z", "other.smali", 1)},
                "y71", "Z", "A"));
        Assert.assertFalse(ReasoningMenuHook.isTargetMenuCall(
                new StackTraceElement[]{
                        new StackTraceElement("y71", "other", "y71.smali", 1)},
                "y71", "Z", "A"));
    }
}
