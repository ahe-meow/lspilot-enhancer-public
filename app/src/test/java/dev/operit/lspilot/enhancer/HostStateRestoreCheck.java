package dev.operit.lspilot.enhancer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class HostStateRestoreCheck {
    private HostStateRestoreCheck() {
    }

    public static void main(String[] args) throws Exception {
        ClassLoader loader = HostStateRestoreCheck.class.getClassLoader();
        HostAbi abi = args.length == 0 ? HostAbi.resolve(loader) : HostAbi.resolve(loader, args);
        Object viewModel = abi.viewModelClass.getConstructor().newInstance();
        Object stateFlow = abi.viewModelStateField.get(viewModel);
        Object original = abi.stateFlowValueMethod.invoke(stateFlow);

        if (args.length == 0) {
            seedLegacyPreviewState(abi, stateFlow, original, loader);
            original = abi.currentState(viewModel);
        }

        assertTrue(original != null, "current host state unavailable");
        String selectedModel = abi.stateSelectedModel(original);
        boolean loading = abi.stateLoading(original);
        Class<?> stateClass = original.getClass();
        List<String> preserved = Arrays.asList(
                "user-before-error", "assistant-error", "message-after-error");
        assertTrue(abi.replaceStateMessages(viewModel, preserved),
                "host message restoration was rejected");

        Object restored = abi.currentState(viewModel);
        assertEquals(preserved, abi.stateMessages(restored), "messages");
        assertEquals(loading, abi.stateLoading(restored), "loading flag");
        assertEquals(selectedModel, abi.stateSelectedModel(restored), "selected model");
        if (args.length == 0) {
            assertEquals("draft", stateClass.getMethod("c").invoke(restored), "draft");
            assertEquals(Collections.singletonList("attachment"),
                    stateClass.getMethod("e").invoke(restored), "attachments");
        }
        Object failed = abi.newStatusMessage("failed-id", "assistant", "Request failed: old",
                System.currentTimeMillis());
        Object retried = abi.copyMessageWithContent(failed, "new response");
        assertEquals("failed-id", abi.messageId(retried), "failed assistant id");
        assertEquals("new response", abi.messageContent(retried), "failed assistant content");
    }

    private static void seedLegacyPreviewState(HostAbi abi, Object stateFlow, Object original,
            ClassLoader loader) throws Exception {
        Class<?> stateClass = original.getClass();
        Class<?> sessionClass = Class.forName("ua", false, loader);
        Constructor<?> constructor = stateClass.getConstructor(
                sessionClass, List.class, String.class, boolean.class, List.class,
                abi.configClass, String.class, String.class);
        Object seeded = constructor.newInstance(null,
                Collections.singletonList("user-before-error"), "draft", true,
                Collections.singletonList("attachment"), null, "selected-model", "host-error");

        Method compareAndSet = abi.viewModelStateField.getType().getMethod(
                "e", Object.class, Object.class);
        assertTrue(Boolean.TRUE.equals(compareAndSet.invoke(stateFlow, original, seeded)),
                "failed to seed host StateFlow");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
