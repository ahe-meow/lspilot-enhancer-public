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
        HostAbi abi = HostAbi.resolve(loader);
        Object viewModel = abi.viewModelClass.getConstructor().newInstance();
        Object stateFlow = abi.viewModelStateField.get(viewModel);
        Object original = abi.stateFlowValueMethod.invoke(stateFlow);

        Class<?> stateClass = original.getClass();
        Class<?> sessionClass = Class.forName("ua", false, loader);
        Constructor<?> constructor = stateClass.getConstructor(
                sessionClass, List.class, String.class, boolean.class, List.class,
                abi.configClass, String.class, String.class);
        List<String> truncated = Collections.singletonList("user-before-error");
        List<String> attachments = Collections.singletonList("attachment");
        Object seeded = constructor.newInstance(null, truncated, "draft", true,
                attachments, null, "selected-model", "host-error");

        Method compareAndSet = abi.viewModelStateField.getType().getMethod(
                "e", Object.class, Object.class);
        assertTrue(Boolean.TRUE.equals(compareAndSet.invoke(stateFlow, original, seeded)),
                "failed to seed host StateFlow");

        List<String> preserved = Arrays.asList(
                "user-before-error", "assistant-error", "message-after-error");
        assertTrue(abi.replaceStateMessages(viewModel, preserved),
                "host message restoration was rejected");

        Object restored = abi.currentState(viewModel);
        assertEquals(preserved, abi.stateMessages(restored), "messages");
        assertTrue(abi.stateLoading(restored), "loading flag must be retained");
        assertEquals("selected-model", abi.stateSelectedModel(restored), "selected model");
        assertEquals("draft", stateClass.getMethod("c").invoke(restored), "draft");
        assertEquals(attachments, stateClass.getMethod("e").invoke(restored), "attachments");
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
