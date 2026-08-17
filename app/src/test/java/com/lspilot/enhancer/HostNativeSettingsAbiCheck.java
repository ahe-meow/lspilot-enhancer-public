package com.lspilot.enhancer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class HostNativeSettingsAbiCheck {
    private HostNativeSettingsAbiCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("host APK path required");
        ClassLoader loader = HostNativeSettingsAbiCheck.class.getClassLoader();
        Class<?> arrowClass = DexAbiScanner.findArrowPreferenceClass(loader, args);
        Method arrow = null;
        for (Method method : arrowClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == void.class
                    && types.length == 16
                    && types[0] == String.class
                    && types[3] == String.class
                    && "kotlin.jvm.functions.Function2".equals(types[5].getName())
                    && "kotlin.jvm.functions.Function0".equals(types[9].getName())) {
                arrow = method;
                break;
            }
        }
        if (arrow == null) throw new AssertionError("ArrowPreference ABI missing");

        HostNativeSettings.resolve(loader, arrow);
        assertNotNull(HostNativeSettings.settingsPagerMethod(), "settings pager");
        assertNotNull(HostNativeSettings.settingsListMethod(), "settings list");
        Method navigate = HostNativeSettings.navigateMethod();
        assertNotNull(navigate, "typed navigation");
        if (!"e".equals(navigate.getName()) || navigate.getParameterCount() != 1) {
            throw new AssertionError("typed navigation ABI missing: " + navigate);
        }
        Method routeRenderer = HostNativeSettings.routeRendererMethod();
        assertNotNull(routeRenderer, "route renderer");
        if (!"k".equals(routeRenderer.getName())
                || routeRenderer.getParameterCount() != 3
                || !routeRenderer.getParameterTypes()[1].getName()
                        .equals(arrow.getParameterTypes()[12].getName())) {
            throw new AssertionError("route renderer ABI missing: " + routeRenderer);
        }

        Object about = HostNativeSettings.aboutRouteForCheck();
        Object realLog = HostNativeSettings.realLogRouteForCheck();
        assertTrue(!HostNativeSettings.isModuleRoute(about),
                "host About must not be the module route");
        assertTrue(!HostNativeSettings.isModuleRoute(realLog),
                "real host LogViewer must not be the module route");
        assertSame(about, HostNativeSettings.rewriteNavigationRoute(about),
                "ordinary About must remain unchanged");
        HostNativeSettings.beginModuleNavigationForCheck();
        Object moduleRoute = HostNativeSettings.rewriteNavigationRoute(about);
        assertTrue(moduleRoute != about && HostNativeSettings.isModuleRoute(moduleRoute),
                "module click must receive a dedicated sentinel route");
        assertSame(about, HostNativeSettings.rewriteNavigationRoute(about),
                "route rewrite must be one-shot");
        HostNativeSettings.beginModuleNavigationForCheck();
        assertSame(realLog, HostNativeSettings.rewriteNavigationRoute(realLog),
                "module navigation must never rewrite a real LogViewer route");
        HostNativeSettings.endModuleNavigationForCheck();
        Method lazyItem = HostNativeSettings.lazyItemMethod();
        assertNotNull(lazyItem, "lazy item");
        if (!"a".equals(lazyItem.getName())
                || lazyItem.getParameterCount() != 6
                || !"kotlin.jvm.functions.Function3".equals(
                        lazyItem.getParameterTypes()[3].getName())) {
            throw new AssertionError("settings lazy-item ABI missing: " + lazyItem);
        }
        assertNotNull(HostNativeSettings.topAppBarMethod(), "top app bar");
        Method dropdown = HostNativeSettings.dropdownPreferenceMethod();
        assertNotNull(dropdown, "reasoning dropdown");
        if (dropdown.getParameterCount() != 21
                || !"kotlin.jvm.functions.Function1".equals(
                        dropdown.getParameterTypes()[15].getName())
                || !"kotlin.jvm.functions.Function1".equals(
                        dropdown.getParameterTypes()[16].getName())) {
            throw new AssertionError("reasoning dropdown callback ABI missing: " + dropdown);
        }
        System.out.println("HostNativeSettingsAbiCheck: PASS");
    }

    private static void assertNotNull(Object value, String name) {
        if (value == null) throw new AssertionError(name + " missing");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) throw new AssertionError(message);
    }
}
