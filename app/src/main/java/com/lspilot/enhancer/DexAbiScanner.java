package com.lspilot.enhancer;

import dalvik.system.DexFile;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Discovers only the request/SSE and settings-entry ABIs used by the module. */
final class DexAbiScanner {
    private DexAbiScanner() {}

    static HostAbi resolve(ClassLoader loader, String[] dexPaths) throws Exception {
        return resolveClassNames(loader, dexClassNames(dexPaths), true);
    }

    static HostAbi resolveCandidates(ClassLoader loader, Iterable<String> candidateNames)
            throws Exception {
        Set<String> uniqueNames = new LinkedHashSet<>();
        if (candidateNames != null) {
            for (String name : candidateNames) {
                if (name != null && !name.isEmpty()) uniqueNames.add(name);
            }
        }
        return resolveClassNames(loader, new ArrayList<>(uniqueNames), false);
    }

    private static HostAbi resolveClassNames(ClassLoader loader, List<String> classNames,
            boolean filterNames) throws Exception {
        List<Class<?>> classes = loadCandidateClasses(loader, classNames, filterNames);
        List<BuildRequestCandidate> buildRequests = findBuildRequests(classes);
        Class<?> function1Class = Class.forName(
                "kotlin.jvm.functions.Function1", false, loader);
        List<RequestCandidate> matches = new ArrayList<>();
        Throwable lastError = null;
        for (BuildRequestCandidate request : buildRequests) {
            try {
                matches.add(new RequestCandidate(request,
                        findSseData(request.owner, function1Class)));
            } catch (Throwable error) {
                lastError = error;
            }
        }
        if (matches.size() != 1) {
            NoSuchMethodException error = matches.isEmpty()
                    ? new NoSuchMethodException(
                            "No coherent request/SSE ABI candidate found; buildRequestCandidates="
                                    + buildRequests.size() + " loadedClasses=" + classes.size())
                    : new AmbiguousRequestException(
                            "Ambiguous request/SSE ABI candidates=" + matches.size());
            if (lastError != null) error.initCause(lastError);
            throw error;
        }

        RequestCandidate match = matches.get(0);
        HostAbi abi = HostAbi.minifiedRequestFromDex(
                match.request.owner, match.request.configClass,
                match.request.method, match.scanSseData);
        DebugLogger.w("DEX request ABI matched provider=" + abi.providerClass.getName()
                + " config=" + abi.configClass.getName());
        return abi;
    }

    static Class<?> findArrowPreferenceClass(ClassLoader loader, String[] dexPaths)
            throws Exception {
        for (String known : new String[]{
                "top.yukonga.miuix.kmp.preference.ArrowPreferenceKt", "fx", "ex"}) {
            Class<?> type = loadClass(loader, known);
            if (type != null && findArrowPreferenceMethod(type) != null) return type;
        }
        for (String className : dexClassNames(dexPaths)) {
            if (!isArrowPreferenceCandidateName(className)) continue;
            Class<?> type = loadClass(loader, className);
            if (type != null && findArrowPreferenceMethod(type) != null) return type;
        }
        throw new ClassNotFoundException("ArrowPreference ABI class not found by DEX scan");
    }

    private static List<String> dexClassNames(String[] dexPaths) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        if (dexPaths == null) return new ArrayList<>(result);
        for (String path : dexPaths) {
            if (path == null || path.trim().isEmpty()) continue;
            File file = new File(path);
            if (!file.isFile()) continue;
            DexFile dexFile = null;
            try {
                dexFile = new DexFile(file);
                Enumeration<String> entries = dexFile.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement();
                    if (name != null && !name.isEmpty()) result.add(name);
                }
            } finally {
                if (dexFile != null) {
                    try {
                        dexFile.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static List<Class<?>> loadCandidateClasses(ClassLoader loader, List<String> classNames,
            boolean filterNames) {
        List<Class<?>> result = new ArrayList<>();
        for (String className : classNames) {
            if (filterNames && !isAbiCandidateName(className)) continue;
            Class<?> type = loadClass(loader, className);
            if (type != null) result.add(type);
        }
        return result;
    }

    private static List<BuildRequestCandidate> findBuildRequests(List<Class<?>> classes)
            throws NoSuchMethodException {
        List<BuildRequestCandidate> result = new ArrayList<>();
        for (Class<?> owner : classes) {
            for (Method method : declaredMethods(owner)) {
                try {
                    Class<?>[] types = method.getParameterTypes();
                    if (!Modifier.isStatic(method.getModifiers())
                            && method.getReturnType() == String.class
                            && types.length == 4
                            && !types[0].isPrimitive()
                            && !types[0].getName().startsWith("java.")
                            && types[1] == List.class
                            && types[2] == String.class
                            && types[3] == boolean.class) {
                        result.add(new BuildRequestCandidate(
                                owner, accessible(method), types[0]));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (result.isEmpty()) {
            throw new NoSuchMethodException("OpenAI request body method not found by DEX scan");
        }
        return result;
    }

    private static Method findSseData(Class<?> owner, Class<?> function1Class)
            throws NoSuchMethodException {
        List<Method> matches = new ArrayList<>();
        for (Method method : declaredMethods(owner)) {
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == boolean.class
                    && types.length == 2
                    && types[0] == String.class
                    && types[1] == function1Class) {
                matches.add(accessible(method));
            }
        }
        if (matches.size() != 1) {
            throw new NoSuchMethodException("SSE parser candidate count=" + matches.size()
                    + " on " + owner.getName());
        }
        return matches.get(0);
    }

    private static Method findArrowPreferenceMethod(Class<?> owner) {
        for (Method method : declaredMethods(owner)) {
            if (matchesArrowPreferenceAbi(method)) return method;
        }
        return null;
    }

    private static boolean matchesArrowPreferenceAbi(Method method) {
        try {
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) {
                return false;
            }
            Class<?>[] types = method.getParameterTypes();
            return types.length == 16
                    && types[0] == String.class
                    && types[3] == String.class
                    && "kotlin.jvm.functions.Function2".equals(types[5].getName())
                    && "kotlin.jvm.functions.Function3".equals(types[6].getName())
                    && "kotlin.jvm.functions.Function2".equals(types[7].getName())
                    && "kotlin.jvm.functions.Function0".equals(types[9].getName())
                    && types[10] == boolean.class
                    && types[11] == boolean.class
                    && !types[12].isPrimitive()
                    && types[13] == int.class
                    && types[14] == int.class
                    && types[15] == int.class;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAbiCandidateName(String name) {
        if (name == null) return false;
        if (name.startsWith("me.yun.lspilot.data.")) return true;
        if (name.indexOf('.') >= 0 || name.indexOf('$') >= 0) return false;
        return name.length() <= 8 && isLowerMinifiedName(name);
    }

    private static boolean isArrowPreferenceCandidateName(String name) {
        if (name == null) return false;
        if (name.startsWith("top.yukonga.miuix.kmp.preference.")) return true;
        return name.indexOf('.') < 0 && name.indexOf('$') < 0
                && name.length() <= 5 && isLowerMinifiedName(name);
    }

    private static boolean isLowerMinifiedName(String value) {
        if (value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    private static Class<?> loadClass(ClassLoader loader, String name) {
        try {
            return loader.loadClass(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method[] declaredMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (Throwable ignored) {
            return new Method[0];
        }
    }

    private static Method accessible(Method method) {
        if (method != null) method.setAccessible(true);
        return method;
    }

    static final class AmbiguousRequestException extends NoSuchMethodException {
        AmbiguousRequestException(String message) {
            super(message);
        }
    }

    private static final class BuildRequestCandidate {
        final Class<?> owner;
        final Method method;
        final Class<?> configClass;

        BuildRequestCandidate(Class<?> owner, Method method, Class<?> configClass) {
            this.owner = owner;
            this.method = method;
            this.configClass = configClass;
        }
    }

    private static final class RequestCandidate {
        final BuildRequestCandidate request;
        final Method scanSseData;

        RequestCandidate(BuildRequestCandidate request, Method scanSseData) {
            this.request = request;
            this.scanSseData = scanSseData;
        }
    }
}
