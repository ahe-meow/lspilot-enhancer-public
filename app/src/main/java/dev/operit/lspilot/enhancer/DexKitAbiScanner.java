package dev.operit.lspilot.enhancer;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Uses DexKit metadata queries to avoid loading every host class during ABI discovery. */
final class DexKitAbiScanner {
    private static volatile boolean nativeLoaded;

    private DexKitAbiScanner() {}

    static HostAbi resolve(ClassLoader loader, String[] dexPaths) throws Exception {
        ensureNativeLoaded();
        List<DexKitBridge> bridges = new ArrayList<>();
        Set<String> candidates = new LinkedHashSet<>();
        Set<String> configClasses = new LinkedHashSet<>();
        Throwable openError = null;
        try {
            if (dexPaths != null) {
                for (String path : dexPaths) {
                    if (path == null || path.trim().isEmpty() || !new File(path).isFile()) continue;
                    try {
                        DexKitBridge bridge = DexKitBridge.create(path);
                        bridge.setThreadNum(Math.max(1, Math.min(2,
                                Runtime.getRuntime().availableProcessors())));
                        bridges.add(bridge);
                    } catch (Throwable error) {
                        openError = error;
                    }
                }
            }
            if (bridges.isEmpty()) {
                IllegalStateException error = new IllegalStateException(
                        "DexKit could not open any host APK");
                if (openError != null) error.initCause(openError);
                throw error;
            }

            for (DexKitBridge bridge : bridges) {
                collectPrimaryCandidates(bridge, candidates, configClasses);
            }
            for (DexKitBridge bridge : bridges) {
                collectStateCandidates(bridge, candidates, configClasses);
            }

            DebugLogger.w("DexKit ABI query produced candidateClasses=" + candidates.size()
                    + " configClasses=" + configClasses.size());
            HostAbi abi = DexAbiScanner.resolveCandidates(loader, candidates);
            DebugLogger.w("DexKit-assisted ABI scan matched provider="
                    + abi.providerClass.getName() + " viewModel=" + abi.viewModelClass.getName());
            return abi;
        } finally {
            for (DexKitBridge bridge : bridges) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void collectPrimaryCandidates(DexKitBridge bridge, Set<String> candidates,
            Set<String> configClasses) {
        MethodDataList buildRequests = findMethods(bridge, MethodMatcher.create()
                .returnType("java.lang.String")
                .paramTypes(Arrays.asList(null, null, "java.lang.String", "boolean")));
        for (MethodData method : buildRequests) {
            addClass(candidates, method.getClassName());
            List<String> params = method.getParamTypeNames();
            if (params.size() == 4) {
                addClass(configClasses, params.get(0));
                addClass(candidates, params.get(0));
            }
        }

        addOwners(candidates, findMethods(bridge, MethodMatcher.create()
                .returnType("boolean")
                .paramTypes("java.lang.String", "kotlin.jvm.functions.Function1")));

        MethodDataList streams = findMethods(bridge, MethodMatcher.create()
                .returnType("void")
                .paramTypes(Arrays.asList(null, "java.util.List",
                        "kotlin.jvm.functions.Function1")));
        for (MethodData method : streams) {
            addClass(candidates, method.getClassName());
            List<String> params = method.getParamTypeNames();
            if (!params.isEmpty()) {
                addClass(configClasses, params.get(0));
                addClass(candidates, params.get(0));
            }
        }

        MethodDataList messageConstructors = findMethods(bridge, MethodMatcher.create()
                .name("<init>")
                .returnType("void")
                .paramTypes(messageConstructorParams()));
        addOwners(candidates, messageConstructors);

        MethodDataList repositoryMethods = findMethods(bridge, MethodMatcher.create()
                .returnType("void")
                .paramTypes(Arrays.asList("java.lang.String", null)));
        for (MethodData method : repositoryMethods) {
            addClass(candidates, method.getClassName());
            List<String> params = method.getParamTypeNames();
            if (params.size() == 2) addClass(candidates, params.get(1));
        }

        addOwners(candidates, findMethods(bridge, MethodMatcher.create()
                .returnType("java.lang.String")
                .paramTypes()
                .usingStrings("AiChat(packageName=")));
    }

    private static void collectStateCandidates(DexKitBridge bridge, Set<String> candidates,
            Set<String> configClasses) {
        for (String configClass : configClasses) {
            addOwners(candidates, findMethods(bridge, MethodMatcher.create()
                    .returnType(configClass)
                    .paramTypes()));
        }
    }

    private static MethodDataList findMethods(DexKitBridge bridge, MethodMatcher matcher) {
        return bridge.findMethod(FindMethod.create().matcher(matcher));
    }

    private static void addOwners(Set<String> candidates, Iterable<MethodData> methods) {
        for (MethodData method : methods) addClass(candidates, method.getClassName());
    }

    private static void addClass(Set<String> result, String className) {
        if (isAppClass(className)) result.add(className);
    }

    private static boolean isAppClass(String name) {
        if (name == null || name.isEmpty() || name.endsWith("[]")) return false;
        return !(name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("android.")
                || name.startsWith("androidx.")
                || name.startsWith("kotlin.")
                || name.startsWith("kotlinx."));
    }

    private static List<String> messageConstructorParams() {
        return Arrays.asList(
                "java.lang.String", "java.lang.String", "java.lang.String", "boolean",
                "java.lang.String", "long", "long", "long", "int", "java.util.List",
                "int", "java.util.List", "java.util.List", "java.lang.String",
                "java.util.List", "boolean", "int", "int", "int", "int", "long",
                "int", "int", "long", "int");
    }

    private static void ensureNativeLoaded() {
        if (nativeLoaded) return;
        synchronized (DexKitAbiScanner.class) {
            if (nativeLoaded) return;
            System.loadLibrary("dexkit");
            nativeLoaded = true;
        }
    }

    public static void main(String[] dexPaths) throws Exception {
        HostAbi abi = resolve(DexKitAbiScanner.class.getClassLoader(), dexPaths);
        assert abi.buildRequestMethod != null;
        assert abi.streamMessagesMethod != null;
        assert abi.repositoryAddMessageMethod != null;
        System.out.println("DexKit ABI resolved provider=" + abi.providerClass.getName()
                + " viewModel=" + abi.viewModelClass.getName());
    }
}