package com.lspilot.enhancer;

import java.util.Arrays;
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

        assertTrue(original != null, "current host state unavailable");
        List<String> preserved = Arrays.asList(
                "user-before-error", "assistant-error", "message-after-error");
        assertTrue(abi.replaceStateMessages(viewModel, preserved),
                "host message restoration was rejected");

        Object restored = abi.currentState(viewModel);
        assertEquals(preserved, abi.stateMessages(restored), "messages");
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
