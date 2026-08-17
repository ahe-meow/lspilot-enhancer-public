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
        assertNotNull(HostNativeSettings.aboutScreenMethod(), "about screen");
        assertNotNull(HostNativeSettings.topAppBarMethod(), "top app bar");
        System.out.println("HostNativeSettingsAbiCheck: PASS");
    }

    private static void assertNotNull(Object value, String name) {
        if (value == null) throw new AssertionError(name + " missing");
    }
}
