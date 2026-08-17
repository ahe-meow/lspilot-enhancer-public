package com.lspilot.enhancer;

import dalvik.system.DexFile;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DexAbiScanner {
    private DexAbiScanner() {}

    static HostAbi resolve(ClassLoader loader, String[] dexPaths) throws Exception {
        return resolveClassNames(loader, dexClassNames(dexPaths), true, null, null);
    }

    static HostAbi resolveCandidates(ClassLoader loader, Iterable<String> candidateNames)
            throws Exception {
        return resolveCandidates(loader, candidateNames, null);
    }

    static HostAbi resolveCandidates(ClassLoader loader, Iterable<String> candidateNames,
            Map<String, ViewModelMethodNames> methodNames) throws Exception {
        return resolveCandidates(loader, candidateNames, methodNames, null);
    }

    static HostAbi resolveCandidates(ClassLoader loader, Iterable<String> candidateNames,
            Map<String, ViewModelMethodNames> methodNames, AccessorNames accessorNames) throws Exception {
        Set<String> uniqueNames = new LinkedHashSet<>();
        if (candidateNames != null) {
            for (String name : candidateNames) {
                if (name != null && !name.isEmpty()) uniqueNames.add(name);
            }
        }
        return resolveClassNames(loader, new ArrayList<>(uniqueNames), false, methodNames, accessorNames);
    }

    private static HostAbi resolveClassNames(ClassLoader loader, List<String> classNames,
            boolean filterNames, Map<String, ViewModelMethodNames> methodNames,
            AccessorNames accessorNames) throws Exception {
        List<Class<?>> classes = loadCandidateClasses(loader, classNames, filterNames);
        List<BuildRequestCandidate> buildRequests = findBuildRequests(classes);
        Class<?> function1Class = Class.forName("kotlin.jvm.functions.Function1", false, loader);
        List<RequestCandidate> requestMatches = new ArrayList<>();
        Throwable lastRequestError = null;
        for (BuildRequestCandidate request : buildRequests) {
            try {
                requestMatches.add(new RequestCandidate(request,
                        findSseData(request.owner, function1Class)));
            } catch (Throwable error) {
                lastRequestError = error;
            }
        }
        if (requestMatches.size() != 1) {
            NoSuchMethodException error = requestMatches.isEmpty()
                    ? new NoSuchMethodException(
                            "No coherent request/SSE ABI candidate found; buildRequestCandidates="
                                    + buildRequests.size() + " loadedClasses=" + classes.size())
                    : new AmbiguousRequestException(
                            "Ambiguous request/SSE ABI candidates=" + requestMatches.size());
            if (lastRequestError != null) error.initCause(lastRequestError);
            throw error;
        }

        RequestCandidate request = requestMatches.get(0);
        try {
            Class<?> messageClass = findMessageClass(classes);
            RepoCandidate repository = findRepositoryAdd(classes, messageClass);
            StreamCandidate stream = findStreamMessages(
                    classes, request.request.configClass, function1Class);
            StateCandidate state = findState(
                    classes, stream.owner, request.request.configClass, messageClass);
            ViewModelMethodNames names = methodNames == null
                    ? null : methodNames.get(stream.owner.getName());
            Method sendMessage = findHintedNoArgVoid(stream.owner,
                    names == null ? null : names.sendMessage);
            Method retryResponse = findHintedNoArgVoid(stream.owner,
                    names == null ? null : names.retryResponse);
            Method stopGeneration = findHintedNoArgVoid(stream.owner,
                    names == null ? null : names.stopGeneration);
            HostAbi match = HostAbi.minifiedFromDex(
                    request.request.owner, request.request.configClass, stream.owner, messageClass,
                    repository.owner, request.request.method, request.scanSseData, stream.method,
                    sendMessage, retryResponse, stopGeneration, repository.method,
                    accessorNames, state.stateClass, state.messagesMethod,
                    state.sessionMethod, state.sessionIdMethod);
            DebugLogger.w("DEX ABI scan matched provider=" + match.providerClass.getName()
                    + " config=" + match.configClass.getName()
                    + " viewModel=" + match.viewModelClass.getName()
                    + " message=" + match.messageClass.getName()
                    + " repository=" + match.repositoryClass.getName()
                    + " send=" + methodName(match.sendMessageMethod)
                    + " retry=" + methodName(match.retryResponseMethod)
                    + " stop=" + methodName(match.stopGenerationMethod));
            return match;
        } catch (Throwable retryError) {
            DebugLogger.w("DEX retry ABI unavailable; request/SSE retained: "
                    + shortError(retryError));
            return HostAbi.minifiedRequestFromDex(
                    request.request.owner, request.request.configClass,
                    request.request.method, request.scanSseData);
        }
    }

    private static Method findHintedNoArgVoid(Class<?> owner, String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            Method method = owner.getDeclaredMethod(name);
            if (method.getReturnType() == void.class
                    && !Modifier.isStatic(method.getModifiers())) {
                return accessible(method);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String methodName(Method method) {
        return method == null ? "fallback" : method.getName();
    }

    private static String shortError(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return error == null ? "unknown" : error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
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
                    if (!Modifier.isStatic(method.getModifiers())
                            && method.getReturnType() == String.class
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

    private static StreamCandidate findStreamMessages(
            List<Class<?>> classes, Class<?> configClass, Class<?> function1Class)
            throws NoSuchMethodException {
        List<StreamCandidate> matches = new ArrayList<>();
        for (Class<?> owner : classes) {
            for (Method method : declaredMethods(owner)) {
                try {
                    Class<?>[] types = method.getParameterTypes();
                    if (!Modifier.isStatic(method.getModifiers())
                            && method.getReturnType() == void.class
                            && types.length == 3
                            && types[0] == configClass
                            && types[1] == List.class
                            && types[2] == function1Class) {
                        matches.add(new StreamCandidate(owner, accessible(method)));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (matches.size() != 1) {
            throw new NoSuchMethodException("stream candidate count=" + matches.size()
                    + " for " + configClass.getName());
        }
        return matches.get(0);
    }

    private static Class<?> findMessageClass(List<Class<?>> classes) throws NoSuchMethodException {
        List<Class<?>> matches = new ArrayList<>();
        for (Class<?> type : classes) {
            if (messageConstructor(type) != null) matches.add(type);
        }
        if (matches.size() != 1) {
            throw new NoSuchMethodException("message class candidate count=" + matches.size());
        }
        return matches.get(0);
    }

    private static RepoCandidate findRepositoryAdd(List<Class<?>> classes, Class<?> messageClass)
            throws NoSuchMethodException {
        List<RepoCandidate> preferred = new ArrayList<>();
        List<RepoCandidate> fallback = new ArrayList<>();
        for (Class<?> owner : classes) {
            for (Method method : declaredMethods(owner)) {
                try {
                    Class<?>[] types = method.getParameterTypes();
                    if (!Modifier.isStatic(method.getModifiers())
                            && method.getReturnType() == void.class
                            && types.length == 2
                            && types[0] == String.class
                            && types[1] == messageClass) {
                        RepoCandidate candidate = new RepoCandidate(owner, accessible(method));
                        (hasSingletonField(owner) ? preferred : fallback).add(candidate);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        List<RepoCandidate> matches = preferred.isEmpty() ? fallback : preferred;
        if (matches.size() != 1) {
            throw new NoSuchMethodException("repository candidate count=" + matches.size());
        }
        return matches.get(0);
    }

    private static StateCandidate findState(
            List<Class<?>> classes, Class<?> viewModelClass, Class<?> configClass,
            Class<?> messageClass)
            throws NoSuchMethodException {
        List<StateCandidate> matches = new ArrayList<>();
        for (Class<?> stateClass : classes) {
            try {
                if (!hasStateFlowField(viewModelClass, stateClass)) continue;
                Method messages = findUniqueMessageListMethod(stateClass, messageClass);
                Method session = findSessionMethod(stateClass, configClass);
                Method sessionId = session == null ? null
                        : findSessionIdMethod(session.getReturnType());
                if (messages != null && session != null && sessionId != null) {
                    matches.add(new StateCandidate(stateClass, messages, session, sessionId));
                }
            } catch (Throwable ignored) {
            }
        }
        if (matches.size() != 1) {
            throw new NoSuchMethodException("state candidate count=" + matches.size());
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

    private static Method findSessionMethod(Class<?> stateClass, Class<?> configClass) {
        List<Method> matches = new ArrayList<>();
        for (Method method : declaredMethods(stateClass)) {
            if (!isNoArg(method)) continue;
            Class<?> type = method.getReturnType();
            if (type == void.class || type.isPrimitive() || type == String.class
                    || type == List.class || type == configClass
                    || type.getName().startsWith("java.")) {
                continue;
            }
            if (findUniqueNoArgReturnExact(type, String.class) != null) {
                matches.add(accessible(method));
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static Method findUniqueMessageListMethod(Class<?> owner, Class<?> messageClass) {
        List<Method> matches = new ArrayList<>();
        for (Method method : declaredMethods(owner)) {
            if (!isNoArg(method) || !List.class.isAssignableFrom(method.getReturnType())) continue;
            Type generic = method.getGenericReturnType();
            if (!(generic instanceof ParameterizedType)) continue;
            Type[] arguments = ((ParameterizedType) generic).getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] == messageClass) {
                matches.add(accessible(method));
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

        private static Method findSessionIdMethod(Class<?> sessionClass) {
            Method exact = findUniqueNoArgReturnExact(sessionClass, String.class);
            if (exact != null) return exact;
            // Kotlin data classes keep the first string getter as the session id. Prefer the
            // verified current-host getter name, but remain fail-closed for other layouts.
            Method preferred = null;
            for (Method method : declaredMethods(sessionClass)) {
                if (isNoArgReturn(method, String.class) && "d".equals(method.getName())) {
                    if (preferred != null) return null;
                    preferred = accessible(method);
                }
            }
            return preferred;
        }

        private static Method findUniqueNoArgReturnExact(Class<?> owner, Class<?> returnType) {
        List<Method> matches = new ArrayList<>();
        for (Method method : declaredMethods(owner)) {
            if (isNoArgReturn(method, returnType)) matches.add(accessible(method));
        }
        return matches.size() == 1 ? matches.get(0) : null;
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

    public static void main(String[] args) throws Exception {
        check(isAbiCandidateName("vj8"), "short minified ABI class should be accepted");
        check(isAbiCandidateName("abcdefgh"), "longer minified ABI class should be accepted");
        check(!isAbiCandidateName("android.app.Activity"), "framework class should be rejected");
        check(isArrowPreferenceCandidateName("fx"),
                "minified ArrowPreference class should be accepted");
        if (args != null && args.length > 0) {
            HostAbi abi = resolve(DexAbiScanner.class.getClassLoader(), args);
            check(abi.buildRequestMethod != null, "build request method missing");
            check(abi.sendMessageMethod != null, "send message method missing");
            check(abi.retryResponseMethod != null, "retry response method missing");
            check(abi.stopGenerationMethod != null, "stop generation method missing");
            check(abi.hasRetryAccessors(), "retry message accessors missing");
            abi.validateAccessorBindings();
            System.out.println("DEX ABI resolved provider=" + abi.providerClass.getName()
                    + " viewModel=" + abi.viewModelClass.getName()
                    + " send=" + abi.sendMessageMethod.getName()
                    + " retry=" + abi.retryResponseMethod.getName()
                    + " stop=" + abi.stopGenerationMethod.getName()
                    + " accessors=" + accessorNames(abi));
        }
    }

    private static String accessorNames(HostAbi abi) {
        return "messageId=" + methodName(abi.accessors.messageId)
                + ",role=" + methodName(abi.accessors.messageRole)
                + ",content=" + methodName(abi.accessors.messageContent);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static final class AmbiguousRequestException extends NoSuchMethodException {
        AmbiguousRequestException(String message) {
            super(message);
        }
    }

    static final class AccessorNames {
        final String messageId;
        final String messageRole;
        final String messageContent;

        AccessorNames(String messageId, String messageRole, String messageContent) {
            this.messageId = emptyToNull(messageId);
            this.messageRole = emptyToNull(messageRole);
            this.messageContent = emptyToNull(messageContent);
        }

        private static String emptyToNull(String value) {
            return value == null || value.isEmpty() ? null : value;
        }
    }

    static final class ViewModelMethodNames {
        final String sendMessage;
        final String retryResponse;
        final String stopGeneration;

        ViewModelMethodNames(String sendMessage, String retryResponse, String stopGeneration) {
            this.sendMessage = sendMessage;
            this.retryResponse = retryResponse;
            this.stopGeneration = stopGeneration;
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
        final Method sessionMethod;
        final Method sessionIdMethod;

        StateCandidate(Class<?> stateClass, Method messagesMethod,
                Method sessionMethod, Method sessionIdMethod) {
            this.stateClass = stateClass;
            this.messagesMethod = messagesMethod;
            this.sessionMethod = sessionMethod;
            this.sessionIdMethod = sessionIdMethod;
        }
    }

}