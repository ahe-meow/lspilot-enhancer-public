package dev.operit.lspilot.enhancer;

import dalvik.system.DexFile;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        Class<?> function1Class = Class.forName("kotlin.jvm.functions.Function1", false, loader);
        Class<?> messageClass = findMessageClass(classes);
        RepoCandidate repository = findRepositoryAdd(classes, messageClass);
        Throwable lastError = null;

        for (BuildRequestCandidate request : buildRequests) {
            try {
                Method scanSseData = findSseData(request.owner, function1Class);
                StreamCandidate stream = findStreamMessages(classes, request.configClass, function1Class);
                StateCandidate state = findState(classes, stream.owner, request.configClass);
                Class<?> aiChatRouteClass = findAiChatRouteClass(loader, classNames);
                DebugLogger.w("DEX ABI scan matched provider=" + request.owner.getName()
                        + " config=" + request.configClass.getName()
                        + " viewModel=" + stream.owner.getName()
                        + " state=" + state.stateClass.getName()
                        + " message=" + messageClass.getName()
                        + " repository=" + repository.owner.getName());
                return HostAbi.minifiedFromDex(
                        request.owner, request.configClass, stream.owner, messageClass,
                        repository.owner, aiChatRouteClass, request.method, scanSseData,
                        stream.method, repository.method, state.stateClass, state.messagesMethod,
                        state.configMethod, state.selectedModelMethod, state.loadingMethod,
                        state.sessionMethod, state.sessionIdMethod);
            } catch (Throwable error) {
                lastError = error;
            }
        }

        NoSuchMethodException error = new NoSuchMethodException(
                "No coherent DEX ABI candidate found; buildRequestCandidates="
                        + buildRequests.size() + " loadedClasses=" + classes.size());
        if (lastError != null) error.initCause(lastError);
        throw error;
    }

    static Class<?> findArrowPreferenceClass(ClassLoader loader, String[] dexPaths) throws Exception {
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
                    if (method.getReturnType() == String.class
                            && types.length == 4
                            && types[0] != null
                            && !types[0].isPrimitive()
                            && !types[0].getName().startsWith("java.")
                            && types[1] == List.class
                            && types[2] == String.class
                            && types[3] == boolean.class) {
                        method.setAccessible(true);
                        result.add(new BuildRequestCandidate(owner, method, types[0]));
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
        for (Method method : declaredMethods(owner)) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getReturnType() == boolean.class
                    && types.length == 2
                    && types[0] == String.class
                    && types[1] == function1Class) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("SSE parser method not found on " + owner.getName());
    }

    private static StreamCandidate findStreamMessages(
            List<Class<?>> classes, Class<?> configClass, Class<?> function1Class)
            throws NoSuchMethodException {
        for (Class<?> owner : classes) {
            for (Method method : declaredMethods(owner)) {
                try {
                    Class<?>[] types = method.getParameterTypes();
                    if (method.getReturnType() == void.class
                            && types.length == 3
                            && types[0] == configClass
                            && types[1] == List.class
                            && types[2] == function1Class) {
                        method.setAccessible(true);
                        return new StreamCandidate(owner, method);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        throw new NoSuchMethodException("stream message method not found for " + configClass.getName());
    }

    private static Class<?> findMessageClass(List<Class<?>> classes) throws NoSuchMethodException {
        for (Class<?> type : classes) {
            if (messageConstructor(type) != null) return type;
        }
        throw new NoSuchMethodException("message constructor not found by DEX scan");
    }

    private static RepoCandidate findRepositoryAdd(List<Class<?>> classes, Class<?> messageClass)
            throws NoSuchMethodException {
        RepoCandidate fallback = null;
        for (Class<?> owner : classes) {
            for (Method method : declaredMethods(owner)) {
                try {
                    Class<?>[] types = method.getParameterTypes();
                    if (method.getReturnType() == void.class
                            && types.length == 2
                            && types[0] == String.class
                            && types[1] == messageClass) {
                        method.setAccessible(true);
                        RepoCandidate candidate = new RepoCandidate(owner, method);
                        if (hasSingletonField(owner)) return candidate;
                        if (fallback == null) fallback = candidate;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (fallback != null) return fallback;
        throw new NoSuchMethodException("repository add-message method not found by DEX scan");
    }

    private static StateCandidate findState(
            List<Class<?>> classes, Class<?> viewModelClass, Class<?> configClass)
            throws NoSuchMethodException {
        for (Class<?> stateClass : classes) {
            try {
                if (!hasStateFlowField(viewModelClass, stateClass)) continue;
                Method messages = findFirstNoArgReturnAssignable(stateClass, List.class);
                Method config = findFirstNoArgReturnExact(stateClass, configClass);
                Method loading = findFirstNoArgBoolean(stateClass);
                Method selectedModel = findSelectedModelMethod(stateClass);
                Method session = findSessionMethod(stateClass, configClass);
                Method sessionId = session == null ? null
                        : findFirstNoArgReturnExact(session.getReturnType(), String.class);
                if (messages != null && config != null && loading != null
                        && selectedModel != null && session != null && sessionId != null) {
                    return new StateCandidate(stateClass, messages, config, selectedModel,
                            loading, session, sessionId);
                }
            } catch (Throwable ignored) {
            }
        }
        throw new NoSuchMethodException("chat UI state class not found by DEX scan");
    }

    private static Class<?> findAiChatRouteClass(ClassLoader loader, List<String> classNames) {
        Class<?> known = loadClass(loader, "lka$b");
        if (known != null) return known;
        for (String className : classNames) {
            if (!isRouteCandidateName(className)) continue;
            Class<?> type = loadClass(loader, className);
            if (type != null && looksLikeAiChatRoute(type)) return type;
        }
        return null;
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

    private static Method findSelectedModelMethod(Class<?> stateClass) {
        Method preferred = findNamedNoArgReturnExact(stateClass, String.class,
                "getSelectedModel", "f");
        if (preferred != null) return preferred;
        Method fallback = null;
        for (Method method : declaredMethods(stateClass)) {
            if (isNoArgReturn(method, String.class)) fallback = method;
        }
        return accessible(fallback);
    }

    private static Method findSessionMethod(Class<?> stateClass, Class<?> configClass) {
        for (Method method : declaredMethods(stateClass)) {
            if (!isNoArg(method)) continue;
            Class<?> type = method.getReturnType();
            if (type == void.class || type.isPrimitive() || type == String.class || type == List.class
                    || type == configClass || type.getName().startsWith("java.")) {
                continue;
            }
            if (findFirstNoArgReturnExact(type, String.class) != null) return accessible(method);
        }
        return null;
    }

    private static Method findFirstNoArgReturnAssignable(Class<?> owner, Class<?> returnType) {
        for (Method method : declaredMethods(owner)) {
            if (isNoArg(method) && returnType.isAssignableFrom(method.getReturnType())) {
                return accessible(method);
            }
        }
        return null;
    }

    private static Method findFirstNoArgReturnExact(Class<?> owner, Class<?> returnType) {
        for (Method method : declaredMethods(owner)) {
            if (isNoArgReturn(method, returnType)) return accessible(method);
        }
        return null;
    }

    private static Method findNamedNoArgReturnExact(
            Class<?> owner, Class<?> returnType, String... names) {
        for (String name : names) {
            try {
                Method method = owner.getDeclaredMethod(name);
                if (method.getReturnType() == returnType) return accessible(method);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findFirstNoArgBoolean(Class<?> owner) {
        for (Method method : declaredMethods(owner)) {
            if (isNoArg(method)
                    && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                return accessible(method);
            }
        }
        return null;
    }

    private static boolean hasStateFlowField(Class<?> viewModelClass, Class<?> stateClass) {
        for (Field field : declaredFields(viewModelClass)) {
            Method getValue = findNamedNoArgReturnAny(field.getType(), "getValue");
            if (getValue != null
                    && (getValue.getReturnType() == Object.class || getValue.getReturnType() == stateClass)) {
                return true;
            }
        }
        return false;
    }

    private static Method findNamedNoArgReturnAny(Class<?> owner, String name) {
        try {
            Method method = owner.getMethod(name);
            return isNoArg(method) ? method : null;
        } catch (Throwable ignored) {
            try {
                Method method = owner.getDeclaredMethod(name);
                return isNoArg(method) ? method : null;
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static Constructor<?> messageConstructor(Class<?> type) {
        Class<?>[] params = {
                String.class, String.class, String.class, boolean.class, String.class,
                long.class, long.class, long.class, int.class, List.class, int.class,
                List.class, List.class, String.class, List.class, boolean.class,
                int.class, int.class, int.class, int.class, long.class, int.class,
                int.class, long.class, int.class
        };
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(params);
            constructor.setAccessible(true);
            return constructor;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean looksLikeAiChatRoute(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class);
            constructor.setAccessible(true);
            Object route = constructor.newInstance("pkg_probe", "chat_probe");
            String value = String.valueOf(route);
            return value.contains("AiChat(packageName=pkg_probe")
                    && value.contains("chatId=chat_probe");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasSingletonField(Class<?> type) {
        for (String name : new String[]{"INSTANCE", "a"}) {
            if (singletonField(type, name) != null) return true;
        }
        return false;
    }

    private static Field singletonField(Class<?> type, String name) {
        try {
            Field field = type.getField(name);
            if (Modifier.isStatic(field.getModifiers())) return field;
        } catch (Throwable ignored) {
        }
        try {
            Field field = type.getDeclaredField(name);
            if (Modifier.isStatic(field.getModifiers())) return field;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isAbiCandidateName(String name) {
        if (name == null) return false;
        if (name.startsWith("me.yun.lspilot.data.")
                || name.startsWith("me.yun.lspilot.ui.viewmodel.")) {
            return true;
        }
        if (name.indexOf('.') >= 0 || name.indexOf('$') >= 0) return false;
        return name.length() <= 8 && isLowerMinifiedName(name);
    }

    private static boolean isRouteCandidateName(String name) {
        if (name == null || name.indexOf('.') >= 0 || name.indexOf('$') < 0) return false;
        return name.length() <= 8 && isLowerMinifiedName(name.replace("$", ""));
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
            return Class.forName(name, false, loader);
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

    private static Field[] declaredFields(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (Throwable ignored) {
            return new Field[0];
        }
    }

    private static boolean isNoArg(Method method) {
        try {
            return method.getParameterTypes().length == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isNoArgReturn(Method method, Class<?> returnType) {
        return isNoArg(method) && method.getReturnType() == returnType;
    }

    private static Method accessible(Method method) {
        if (method != null) method.setAccessible(true);
        return method;
    }

    public static void main(String[] args) {
        check(isAbiCandidateName("ts8"), "short minified ABI class should be accepted");
        check(isAbiCandidateName("abcdefgh"), "longer minified ABI class should be accepted");
        check(!isAbiCandidateName("android.app.Activity"), "framework class should be rejected");
        check(isRouteCandidateName("lka$b"), "minified route class should be accepted");
        check(!isRouteCandidateName("lka"), "route class without nested marker should be rejected");
        check(isArrowPreferenceCandidateName("fx"), "minified ArrowPreference class should be accepted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
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

    private static final class StreamCandidate {
        final Class<?> owner;
        final Method method;

        StreamCandidate(Class<?> owner, Method method) {
            this.owner = owner;
            this.method = method;
        }
    }

    private static final class RepoCandidate {
        final Class<?> owner;
        final Method method;

        RepoCandidate(Class<?> owner, Method method) {
            this.owner = owner;
            this.method = method;
        }
    }

    private static final class StateCandidate {
        final Class<?> stateClass;
        final Method messagesMethod;
        final Method configMethod;
        final Method selectedModelMethod;
        final Method loadingMethod;
        final Method sessionMethod;
        final Method sessionIdMethod;

        StateCandidate(Class<?> stateClass, Method messagesMethod, Method configMethod,
                Method selectedModelMethod, Method loadingMethod, Method sessionMethod,
                Method sessionIdMethod) {
            this.stateClass = stateClass;
            this.messagesMethod = messagesMethod;
            this.configMethod = configMethod;
            this.selectedModelMethod = selectedModelMethod;
            this.loadingMethod = loadingMethod;
            this.sessionMethod = sessionMethod;
            this.sessionIdMethod = sessionIdMethod;
        }
    }
}