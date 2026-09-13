package com.lspilot.enhancer;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class DexKitFailurePolicyCheck {
    public DexKitFailurePolicyCheck() {
    }

    @org.junit.Test
    public void runsAssertions() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        assertUniqueSelectionFailsClosed();
        assertCapabilitiesInstallIndependently();
        assertCapabilityAttemptRunsIndependently();
        assertHookRegistryResourceCount();
        assertFilteringRejectsWrongReturnType();
        assertExactReasoningEnumContract();
        assertCacheKeyIdentity();
        assertCacheSourceContract();
        assertCurrentV12AbiContracts();
        assertLifecycleSourceContract();
        assertDebugLoggerSourceContract();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertUniqueSelectionFailsClosed() {
        Object one = new Object();
        Object two = new Object();

        require(DexKitAbiScanner.chooseUnique("request", Collections.emptyList()) == null,
                "zero candidates must fail closed");
        require(DexKitAbiScanner.chooseUnique("request", Collections.singletonList(one)) == one,
                "one candidate must be selected");
        require(DexKitAbiScanner.chooseUnique("request", Arrays.asList(one, two)) == null,
                "multiple candidates must fail closed");
    }

    private static void assertCapabilitiesInstallIndependently() {
        Method exact = declaredMethod("exactInstanceString", String.class);
        HostAbi.RequestCapability genericRequest = new HostAbi.RequestCapability(
                exact, RequestJsonPolicy.ProviderKind.GENERIC);
        HostAbi abi = new HostAbi(null, null, genericRequest, null);

        require(!installable(null, "genericRequest"),
                "null ABI must not install a capability");
        require(installable(abi, "genericRequest"),
                "valid generic request must remain installable");
        require(!installable(abi, "menuLabels"),
                "missing menu labels must remain disabled");
        require(!installable(abi, "thinkingRequest"),
                "missing thinking request must remain disabled");
    }

    private static void assertCapabilityAttemptRunsIndependently() {
        final HookRegistry registry = new HookRegistry();
        final boolean[] secondExecuted = new boolean[]{false};
        try {
            require(!CapabilityAttempt.run(registry, new CapabilityAttempt.Action() {
                @Override
                public void install() {
                    throw new RuntimeException("first attempt failure");
                }
            }), "a throwing attempt must report no installed resource");

            require(CapabilityAttempt.run(registry, new CapabilityAttempt.Action() {
                @Override
                public void install() {
                    secondExecuted[0] = true;
                    registry.addForTest(new HookRegistry.TestHookHandle() {
                        @Override
                        public void unhook() {
                        }
                    });
                }
            }), "a later attempt must install after an earlier throw");
            require(secondExecuted[0],
                    "a later independent attempt must still execute after a throw");
            require(registry.resourceCount() == 1,
                    "the successful attempt must own its added resource");
            require(!CapabilityAttempt.run(registry, new CapabilityAttempt.Action() {
                @Override
                public void install() {
                }
            }), "a no-op attempt must report no installed resource");
        } finally {
            registry.close();
        }
    }

    private static void assertHookRegistryResourceCount() {
        HookRegistry registry = new HookRegistry();
        require(registry.resourceCount() == 0,
                "new registry must own no resources");

        HookRegistry.TestHookHandle handle = new HookRegistry.TestHookHandle() {
            @Override
            public void unhook() {
            }
        };
        registry.addForTest(handle);
        require(registry.resourceCount() == 1,
                "one owned hook must count as one resource");
        registry.addForTest(handle);
        require(registry.resourceCount() == 1,
                "duplicate hook identity must not increase the resource count");

        registry.close();
        require(registry.resourceCount() == 0,
                "closed registry must report no owned resources");
    }

    private static void assertFilteringRejectsWrongReturnType() {
        Method wrong = declaredMethod("wrongReturnType", String.class);
        Method exact = declaredMethod("exactInstanceString", String.class);
        List<Method> filtered = DexKitAbiScanner.filterMethods(
                Arrays.asList(wrong, exact), String.class, false, String.class);

        require(filtered.size() == 1,
                "wrong return type must be filtered before uniqueness");
        require(filtered.get(0) == exact,
                "exact method must remain after filtering");
        require(DexKitAbiScanner.chooseUnique("genericRequest", filtered) == exact,
                "filtered exact method must be selected");
    }

    private static void assertExactReasoningEnumContract() {
        require(DexKitAbiScanner.hasExactReasoningEnumContract(ValidReasoning.class),
                "valid reasoning enum must be accepted");
        require(!DexKitAbiScanner.hasExactReasoningEnumContract(ExtraReasoning.class),
                "extra reasoning enum value must be rejected");
        require(DexKitAbiScanner.hasExactReasoningEnumContract(MissingMethodReasoning.class),
                "enum shape must not depend on legacy helper method names");
    }

    private static void assertCacheKeyIdentity() {
        ClassLoader firstLoader = new ClassLoader() {
        };
        ClassLoader secondLoader = new ClassLoader() {
        };

        require(DexKitAbiScanner.cacheKeyMatches(
                        firstLoader, "abc", firstLoader, "abc"),
                "matching loader identity and fingerprint must hit the cache");
        require(!DexKitAbiScanner.cacheKeyMatches(
                        firstLoader, "abc", firstLoader, "def"),
                "different fingerprints must miss the cache");
        require(!DexKitAbiScanner.cacheKeyMatches(
                        firstLoader, "abc", secondLoader, "abc"),
                "different loader identities must miss the cache");
        require(!DexKitAbiScanner.cacheKeyMatches(
                        null, "abc", firstLoader, "abc"),
                "a missing cached loader must miss the cache");
    }

    private static void assertCacheSourceContract() {
        try {
            String source = readSource(
                    "app/src/main/java/com/lspilot/enhancer/DexKitAbiScanner.java");
            require(source.contains(
                            "static ScanResult resolveDetailed(ClassLoader loader, "
                                    + "List<String> sourcePaths)"),
                    "scanner must expose the list-based resolver entry point");
            require(source.contains("HostApkFingerprint.compute(sourcePaths)"),
                    "scanner must compute the host content fingerprint");
            require(source.contains("static void clearCache()"),
                    "scanner must expose cache lifecycle clearing");
            require(source.contains("contentFingerprint") && source.contains("cacheHit"),
                    "scan results must expose cache diagnostics");
        } catch (Exception exception) {
            throw new AssertionError("cache scanner contract failed", exception);
        }
    }

    private static void assertCurrentV12AbiContracts() {
        try {
            String source = readSource(
                    "app/src/main/java/com/lspilot/enhancer/DexKitAbiScanner.java");
            require(source.contains(
                            "private static final String TYPE_PROVIDER = \"vb\";"),
                    "scanner must use the current provider type");
            require(source.contains(
                            "private static final String TYPE_MENU_RESOLVER = \"n0b\";"),
                    "scanner must use the current menu resolver type");
            require(source.contains(
                            "private static final String TYPE_MENU_COMPOSER = \"id2\";"),
                    "scanner must use the current menu composer type");
            require(source.contains(
                            "private static final String TYPE_MENU_OWNER = \"y71\";"),
                    "scanner must use the current menu owner type");
            require(source.contains(
                            "private static final String TYPE_MENU_RESOURCES = \"u29\";"),
                    "scanner must resolve the current menu resource holder");
            require(source.contains(
                            "private static final String MENU_BUTTON_METHOD = \"A\";"),
                    "scanner must resolve the reasoning button caller");
            require(source.contains(
                            "private static final String MENU_BUTTON_RESOURCE_FIELD = \"S1\";"),
                    "scanner must resolve the OFF button resource");
            require(source.contains("resolveMenu(loader,"),
                    "scanner must pass the discovered enum prerequisite to the menu resolver");
            require(source.contains("new HostAbi(\n                reasoning.capability,"),
                    "scanner must construct the four-part ABI");
            String[] staleTypes = {"\"wb\"", "\"oi9\"", "\"q97\"", "\"lv9\"",
                    "\"hd7\"", "\"fe7\"", "\"ze2\"", "TYPE_MODIFIER", "TYPE_UNIT"};
            for (String staleType : staleTypes) {
                require(!source.contains(staleType),
                        "scanner must not retain removed/context ABI type: " + staleType);
            }
            String[] removedNames = {
                    "contextDisplay", "contextText", "longPressAdapter", "CONTEXT_",
                    "LONG_PRESS_ADAPTER", "DisplayCapability", "TextCapability"};
            for (String removedName : removedNames) {
                require(!source.contains(removedName),
                        "scanner must not retain removed context capability: " + removedName);
            }
        } catch (Exception exception) {
            throw new AssertionError("current v12 scanner contract failed", exception);
        }
    }

    private static void assertLifecycleSourceContract() {
        try {
            String source = readSource(
                    "app/src/main/java/com/lspilot/enhancer/LSPilotEnhancerModule.java");
            require(source.contains("import io.github.libxposed.api.XposedModule;"),
                    "module must import the API 102 XposedModule superclass");
            require(source.contains(
                            "import io.github.libxposed.api.XposedModuleInterface;"),
                    "module must import the API 102 callback interface");
            require(source.contains(
                            "public final class LSPilotEnhancerModule extends XposedModule"),
                    "module must extend XposedModule exactly");
            require(source.contains("public void onModuleLoaded("
                            + "XposedModuleInterface.ModuleLoadedParam param)"),
                    "module-loaded callback signature must be API 102 exact");
            require(source.contains("public void onPackageReady("
                            + "XposedModuleInterface.PackageReadyParam param)"),
                    "package-ready callback signature must be API 102 exact");
            require(source.contains("public boolean onHotReloading("
                            + "XposedModuleInterface.HotReloadingParam param)"),
                    "hot-reloading callback signature must be API 102 exact");
            require(source.contains("public void onHotReloaded("
                            + "XposedModuleInterface.HotReloadedParam param)"),
                    "hot-reloaded callback signature must be API 102 exact");
            require(!source.contains("getRemotePreferences(\"default\")"),
                    "host policy source must be read directly from the host");
            require(source.contains("new HookRegistry(this)"),
                    "accepted packages must create a hook registry");
            require(source.contains("CapabilityAttempt.run(currentRegistry, action)"),
                    "capability attempts must use the resource gate");

            int scanner = source.indexOf("DexKitAbiScanner.resolveDetailed");
            int packageCheck = source.indexOf(
                    "if (!TARGET_PACKAGE.equals(packageName))");
            int processCheck = source.indexOf(
                    "if (!TARGET_PACKAGE.equals(moduleProcessName))");
            require(scanner >= 0 && packageCheck >= 0 && processCheck >= 0
                            && packageCheck < scanner && processCheck < scanner,
                    "routing checks must precede resolver access");
            require(source.contains("applicationInfo.splitSourceDirs")
                            && source.contains("path.isFile()")
                            && source.contains("path.canRead()"),
                    "all host APK paths must be validated");
            require(source.contains("loader = param.getClassLoader()")
                            && source.contains("loader = param.getDefaultClassLoader()"),
                    "class-loader fallback must be retained");

            int predicateStart = source.indexOf("static boolean hasAnyCapability(HostAbi abi)");
            int predicateEnd = source.indexOf("\n    }", predicateStart);
            require(predicateStart >= 0 && predicateEnd > predicateStart,
                    "ABI capability predicate must be present");
            String predicateBody = source.substring(predicateStart, predicateEnd);
            for (String field : new String[]{"reasoningSource", "menuLabels",
                    "genericRequest", "thinkingRequest"}) {
                require(predicateBody.contains("abi." + field + " != null"),
                        "ABI predicate must inspect " + field);
            }
            for (String removed : new String[]{"contextDisplay", "contextText",
                    "longPressAdapter", "ContextLimit"}) {
                require(!source.contains(removed),
                        "module must not retain removed context behavior: " + removed);
            }

            String[] attempts = {
                    "attemptCapability(\"menuLabels\"",
                    "attemptCapability(\"genericRequest\"",
                    "attemptCapability(\"thinkingRequest\""};
            int previous = -1;
            for (String attempt : attempts) {
                int current = source.indexOf(attempt);
                require(current > previous,
                        "remaining capability attempts must stay independently ordered");
                previous = current;
            }
        } catch (Exception exception) {
            throw new AssertionError("lifecycle source contract failed", exception);
        }
    }

    private static void assertDebugLoggerSourceContract() {
        try {
            String source = readSource(
                    "app/src/main/java/com/lspilot/enhancer/DebugLogger.java");
            require(source.contains("public static void capability("
                            + "String capability, String event, Object... values)"),
                    "logger must expose the capability diagnostic contract");
            require(source.contains("getClass().getSimpleName()"),
                    "logger must use sanitized exception class names");
            require(!source.contains("getMessage") && !source.contains("printStackTrace"),
                    "logger must not emit exception details");
            require(!source.contains("contextDisplay") && !source.contains("contextText")
                            && !source.contains("longPressAdapter"),
                    "logger must not expose removed context capabilities");
        } catch (Exception exception) {
            throw new AssertionError("debug logger source contract failed", exception);
        }
    }

    static boolean installable(HostAbi abi, String capability) {
        if (abi == null || capability == null) {
            return false;
        }
        if ("reasoningSource".equals(capability)) {
            return abi.reasoningSource != null;
        }
        if ("menuLabels".equals(capability)) {
            return abi.menuLabels != null;
        }
        if ("genericRequest".equals(capability)) {
            return abi.genericRequest != null;
        }
        if ("thinkingRequest".equals(capability)) {
            return abi.thinkingRequest != null;
        }
        return false;
    }

    private static String readSource(String path) throws IOException {
        File current = new File(System.getProperty("user.dir", "."))
                .getAbsoluteFile();
        while (current != null) {
            File candidate = new File(current, path);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()),
                        StandardCharsets.UTF_8);
            }
            current = current.getParentFile();
        }
        throw new IOException("source file missing: " + path);
    }

    private static Method declaredMethod(String name, Class<?>... parameterTypes) {
        try {
            return Fixture.class.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    private enum ValidReasoning {
        OFF, AUTO, LOW, MEDIUM, HIGH, MAX;

        public Integer d() {
            return Integer.valueOf(ordinal());
        }

        public String m() {
            return name();
        }
    }

    private enum ExtraReasoning {
        OFF, AUTO, LOW, MEDIUM, HIGH, MAX, EXTRA;

        public Integer d() {
            return Integer.valueOf(ordinal());
        }

        public String m() {
            return name();
        }
    }

    private enum MissingMethodReasoning {
        OFF, AUTO, LOW, MEDIUM, HIGH, MAX;

        public Integer d() {
            return Integer.valueOf(ordinal());
        }
    }

    private static final class Fixture {
        private Fixture() {
        }

        String exactInstanceString(String value) {
            return value;
        }

        int wrongReturnType(String value) {
            return value == null ? 0 : value.length();
        }
    }
}
