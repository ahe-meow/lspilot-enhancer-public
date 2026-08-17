package com.lspilot.enhancer;

public final class NativeSettingsNavigationCheck {
    private NativeSettingsNavigationCheck() {}

    public static void main(String[] args) {
        HostNativeSettings.setModuleRouteVisible(false);
        assertTrue(!HostNativeSettings.shouldRenderSettings(),
                "host About must keep the host settings content");

        HostNativeSettings.setModuleRouteVisible(true);
        assertTrue(HostNativeSettings.shouldRenderSettings(),
                "the dedicated module route must render module settings");

        HostNativeSettings.setModuleRouteVisible(false);
        assertTrue(!HostNativeSettings.shouldRenderSettings(),
                "returning from the module route must restore host content");

        HostNativeSettings.setModuleRouteVisible(true);
        assertTrue(HostNativeSettings.shouldRenderSettings(),
                "About then module must remain independently selectable");
        HostNativeSettings.setModuleRouteVisible(false);

        HostNativeSettings.beginHostList();
        assertTrue(!HostNativeSettings.shouldInsertBeforeHostItem(), "item 1 must stay first");
        assertTrue(!HostNativeSettings.shouldInsertBeforeHostItem(), "item 2 must stay second");
        assertTrue(!HostNativeSettings.shouldInsertBeforeHostItem(), "item 3 must stay third");
        assertTrue(HostNativeSettings.shouldInsertBeforeHostItem(),
                "module entry must be inserted before the bottom-padding item");
        HostNativeSettings.markHostEntryInserted();
        assertTrue(HostNativeSettings.hostEntryInserted(), "entry insertion must be recorded");
        assertTrue(!HostNativeSettings.shouldInsertBeforeHostItem(),
                "module entry must not be inserted twice");
        HostNativeSettings.endHostList();

        System.out.println("NativeSettingsNavigationCheck: PASS");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
