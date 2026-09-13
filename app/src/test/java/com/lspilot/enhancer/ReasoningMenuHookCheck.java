package com.lspilot.enhancer;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
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

        Assert.assertEquals("off", ReasoningMenuHook.replacementFor(10, labels));
        Assert.assertEquals("low", ReasoningMenuHook.replacementFor(11, labels));
        Assert.assertEquals("medium", ReasoningMenuHook.replacementFor(12, labels));
        Assert.assertEquals("high", ReasoningMenuHook.replacementFor(13, labels));
        Assert.assertEquals("xhigh", ReasoningMenuHook.replacementFor(14, labels));
        Assert.assertEquals("max", ReasoningMenuHook.replacementFor(15, labels));
        Assert.assertNull(ReasoningMenuHook.replacementFor(99, labels));
        Assert.assertNull(ReasoningMenuHook.replacementFor(10, null));

        try {
            Method resolver = MenuFixtures.class.getDeclaredMethod(
                    "resolveLabel", int.class, ComposerQ.class, int.class);
            Method menu = MenuFixtures.class.getDeclaredMethod("renderChoices", ModeR.class);
            Method button = MenuFixtures.class.getDeclaredMethod("renderCurrent", ModeR.class);
            HostAbi.MenuCapability capability = new HostAbi.MenuCapability(
                    resolver, labels, menu, button, ModeR.class, 0);

            Assert.assertTrue("unknown resolver parameter names must be accepted",
                    ReasoningMenuHook.isInstallable(capability));
            Assert.assertTrue(ReasoningMenuHook.isTargetMenuCall(
                    new StackTraceElement[]{new StackTraceElement(
                            MenuFixtures.class.getName(), "renderChoices", "fixture", 1)},
                    menu));
            Assert.assertTrue(ReasoningMenuHook.isTargetMenuCall(
                    new StackTraceElement[]{new StackTraceElement(
                            MenuFixtures.class.getName(), "renderCurrent", "fixture", 1)},
                    button));
            Assert.assertFalse(ReasoningMenuHook.isTargetMenuCall(
                    new StackTraceElement[]{new StackTraceElement(
                            "unrelated.Owner", "renderChoices", "fixture", 1)},
                    menu));
            Assert.assertFalse(ReasoningMenuHook.isTargetMenuCall(
                    new StackTraceElement[]{new StackTraceElement(
                            MenuFixtures.class.getName(), "other", "fixture", 1)},
                    menu));

            ReasoningMenuHook.CalibrationResult beforeCalibration =
                    ReasoningMenuHook.calibrate(
                            "OFF", 99, "host-off", 2, -1, labels);
            Assert.assertEquals(-1, beforeCalibration.labelOrdinal);
            Assert.assertEquals("host-off", beforeCalibration.replacement);

            ReasoningMenuHook.CalibrationResult autoCalibration =
                    ReasoningMenuHook.calibrate(
                            "AUTO", 11, "host-low", 2, -1, labels);
            Assert.assertEquals(2, autoCalibration.labelOrdinal);
            Assert.assertEquals("low", autoCalibration.replacement);

            ReasoningMenuHook.CalibrationResult calibratedOff =
                    ReasoningMenuHook.calibrate(
                            "OFF", 99, "host-off", 2,
                            autoCalibration.labelOrdinal, labels);
            Assert.assertEquals("off", calibratedOff.replacement);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("adaptive menu fixture method missing", exception);
        }

        assertRegistryCanRemoveOneHook();
    }

    private static void assertRegistryCanRemoveOneHook() {
        HookRegistry registry = new HookRegistry();
        CleanupHandle first = new CleanupHandle();
        CleanupHandle second = new CleanupHandle();
        registry.addForTest(first);
        registry.addForTest(second);
        Assert.assertTrue("owned hook must be removable by identity",
                registry.removeHook(first));
        Assert.assertEquals(1, registry.resourceCount());
        Assert.assertEquals(1, first.unhookCalls);
        Assert.assertFalse("removing the same hook twice must be harmless",
                registry.removeHook(first));
        registry.close();
        Assert.assertEquals(1, second.unhookCalls);
    }

    private enum ModeR {
        OFF, AUTO, LOW, MEDIUM, HIGH, MAX
    }

    private static final class ComposerQ {
    }

    private static final class MenuFixtures {
        private MenuFixtures() {
        }

        static String resolveLabel(int resourceId, ComposerQ composer, int flags) {
            return composer == null ? null : String.valueOf(resourceId + flags);
        }

        static void renderChoices(ModeR mode) {
        }

        static void renderCurrent(ModeR mode) {
        }
    }

    private static final class CleanupHandle implements HookRegistry.TestHookHandle {
        private int unhookCalls;

        @Override
        public void unhook() {
            unhookCalls++;
        }
    }
}
