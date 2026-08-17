package com.lspilot.enhancer;

public final class NativeSettingsNavigationCheck {
    private NativeSettingsNavigationCheck() {}

    public static void main(String[] args) {
        assertTrue(!HostNativeSettings.pageRequestedForCheck(), "initial state must be idle");

        HostNativeSettings.requestPageForCheck();
        HostNativeSettings.markHostSettingsRenderedForCheck();
        assertTrue(HostNativeSettings.pageRequestedForCheck(),
                "main-page recomposition before navigation must not cancel the request");

        HostNativeSettings.markPageRenderedForCheck();
        assertTrue(HostNativeSettings.pageRequestedForCheck(),
                "the native module page must remain active while rendered");

        HostNativeSettings.markHostSettingsRenderedForCheck();
        assertTrue(!HostNativeSettings.pageRequestedForCheck(),
                "returning to host settings must restore the real About page");

        System.out.println("NativeSettingsNavigationCheck: PASS");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
