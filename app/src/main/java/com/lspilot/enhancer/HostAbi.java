package com.lspilot.enhancer;

import android.content.pm.ApplicationInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Host ABI resolver for named debug builds and minified release builds. */
final class HostAbi {
    private static final String NAMED_PROVIDER = "me.yun.lspilot.data.provider.OpenAiApiProvider";
    private static final String NAMED_CONFIG = "me.yun.lspilot.data.model.AiProviderConfig";
    private static final String NAMED_VIEW_MODEL = "me.yun.lspilot.ui.viewmodel.AiChatViewModel";
    private static final String NAMED_REPOSITORY = "me.yun.lspilot.data.repository.AiChatRepository";
    private static final String NAMED_MESSAGE = "me.yun.lspilot.data.model.AiChatMessage";

    // Known minified profiles; the update path still runs structural DEX discovery first.
    private static final String MINIFIED_PROVIDER = "vj8";
    private static final String ALTERNATE_MINIFIED_PROVIDER = "xj8";
    private static final String CURRENT_MINIFIED_PROVIDER = "zj8";
    private static final String MINIFIED_VIEW_MODEL = "va";
    private static final String MINIFIED_CONFIG = "cb";
    private static final String MINIFIED_MESSAGE = "u7";
    private static final String MINIFIED_REPOSITORY = "me.yun.lspilot.data.repository.b";
    private static final String LEGACY_STREAM_DONE = "lwb$b";
    private static final String LEGACY_STREAM_ERROR = "lwb$c";
    private static final String CURRENT_STREAM_DONE = "uob$b";
    private static final String CURRENT_STREAM_ERROR = "uob$c";

    final Class<?> providerClass;
    final Class<?> configClass;
    final Class<?> viewModelClass;
    final Class<?> messageClass;
    final Class<?> repositoryClass;
    final boolean minified;
    final Method buildRequestMethod;
    final Method scanSseDataMethod;
    final Method streamMessagesMethod;
    final Method loadSessionMethod;
    final Method sendMessageMethod;
    final Method retryResponseMethod;
    final Method stopGenerationMethod;
    final Method repositoryAddMessageMethod;
    final Accessors accessors;
    final Field viewModelStateField;
    final Method stateFlowValueMethod;
    final Method stateMessagesMethod;
    final Method stateSessionMethod;
    final Method sessionIdMethod;

    HostAbi(Class<?> providerClass, Class<?> configClass, Class<?> viewModelClass,
            Class<?> messageClass, Class<?> repositoryClass, boolean minified,
            Method buildRequestMethod, Method scanSseDataMethod, Method streamMessagesMethod,
            Method loadSessionMethod, Method sendMessageMethod, Method retryResponseMethod,
            Method stopGenerationMethod, Method repositoryAddMessageMethod, Accessors accessors,
            Field viewModelStateField, Method stateFlowValueMethod, Method stateMessagesMethod,
            Method stateSessionMethod, Method sessionIdMethod) {
        this.providerClass = providerClass;
        this.configClass = configClass;
        this.viewModelClass = viewModelClass;
        this.messageClass = messageClass;
        this.repositoryClass = repositoryClass;
        this.minified = minified;
        this.buildRequestMethod = buildRequestMethod;
        this.scanSseDataMethod = scanSseDataMethod;
        this.streamMessagesMethod = streamMessagesMethod;
        this.loadSessionMethod = loadSessionMethod;
        this.sendMessageMethod = sendMessageMethod;
        this.retryResponseMethod = retryResponseMethod;
        this.stopGenerationMethod = stopGenerationMethod;
        this.repositoryAddMessageMethod = repositoryAddMessageMethod;
        this.accessors = accessors;
        this.viewModelStateField = viewModelStateField;
        this.stateFlowValueMethod = stateFlowValueMethod;
        this.stateMessagesMethod = stateMessagesMethod;
        this.stateSessionMethod = stateSessionMethod;
        this.sessionIdMethod = sessionIdMethod;
    }

    static HostAbi resolve(ClassLoader loader) throws Exception {
        return resolve(loader, null);
    }

    static HostAbi resolve(ClassLoader loader, String[] dexPaths) throws Exception {
        return resolveFresh(loader, dexPaths);
    }

    static HostAbi resolve(ClassLoader loader, String[] dexPaths, ApplicationInfo appInfo)
            throws Exception {
        return HostAbiCache.resolve(loader, dexPaths, appInfo);
    }

    static HostAbi resolveFresh(ClassLoader loader, String[] dexPaths) throws Exception {
        return resolveFresh(loader, dexPaths, false);
    }

    static HostAbi resolveFresh(ClassLoader loader, String[] dexPaths,
            boolean preferDexScan) throws Exception {
        Throwable dexScanError = null;
        if (preferDexScan && dexPaths != null && dexPaths.length > 0) {
            try {
                HostAbi scanned = DexAbiScanner.resolve(loader, dexPaths);
                DebugLogger.i("host update DEX self-adaptation resolved provider="
                        + scanned.providerClass.getName());
                return scanned;
            } catch (DexAbiScanner.AmbiguousRequestException error) {
                throw error;
            } catch (Throwable error) {
                dexScanError = error;
                DebugLogger.w("host update DEX self-adaptation failed; trying known profiles: "
                        + shortError(error));
            }
        }

        Throwable namedError;
        try {
            return resolveNamed(loader);
        } catch (Throwable error) {
            namedError = error;
        }

        try {
            HostAbi minified = resolveMinified110(loader);
            DebugLogger.w("named host ABI unavailable; using known minified ABI: "
                    + shortError(namedError));
            return minified;
        } catch (Throwable minifiedFirstError) {
            if (dexScanError == null && dexPaths != null && dexPaths.length > 0) {
                try {
                    HostAbi scanned = DexAbiScanner.resolve(loader, dexPaths);
                    DebugLogger.w("named/minified ABI unavailable; using structural DEX scan: "
                            + shortError(minifiedFirstError));
                    return scanned;
                } catch (Throwable error) {
                    dexScanError = error;
                }
            }

            NoSuchMethodException combined = new NoSuchMethodException(
                    "host ABI unavailable; named=" + shortError(namedError)
                            + " minified=" + shortError(minifiedFirstError)
                            + " dex=" + shortError(dexScanError));
            combined.initCause(dexScanError != null ? dexScanError : minifiedFirstError);
            throw combined;
        }
    }

    boolean hasRequestAbi() {
        return buildRequestMethod != null && scanSseDataMethod != null;
    }

    boolean hasRetryAbi() {
        return streamMessagesMethod != null && loadSessionMethod != null
                && sendMessageMethod != null && retryResponseMethod != null
                && stopGenerationMethod != null && repositoryAddMessageMethod != null
                && accessors != null && accessors.hasRetryAccessors()
                && viewModelStateField != null && stateFlowValueMethod != null
                && stateMessagesMethod != null && stateSessionMethod != null
                && sessionIdMethod != null;
    }

    static Class<?> resolveViewModelClass(ClassLoader loader) throws ClassNotFoundException {
        Class<?> named = optionalClass(loader, NAMED_VIEW_MODEL);
        return named != null ? named : Class.forName(MINIFIED_VIEW_MODEL, false, loader);
    }


    static Method findLoadSessionMethod(Class<?> viewModelClass, Class<?> contextClass) throws Exception {
        Method named = optionalDeclaredMethod(viewModelClass, "loadSession",
                String.class, String.class, contextClass);
        if (named != null) return accessible(named);
        if ("bb".equals(viewModelClass.getName())) {
            Method minified = optionalDeclaredMethod(viewModelClass, "D",
                    String.class, String.class, contextClass);
            if (minified != null && minified.getReturnType() == void.class) {
                return accessible(minified);
            }
        }
        return findMethod(viewModelClass, void.class, String.class, String.class, contextClass);
    }

    private static Method findSendMessageMethod(Class<?> viewModelClass) throws Exception {
        Method named = optionalDeclaredMethod(viewModelClass, "sendMessage");
        if (named != null) return accessible(named);
        if ("bb".equals(viewModelClass.getName())) {
            Method method = optionalDeclaredMethod(viewModelClass, "M");
            if (method != null && method.getReturnType() == void.class) return accessible(method);
            throw new NoSuchMethodException("Verified LSPilot 1.1.0 send method M() missing");
        }
        for (String name : new String[]{"sendMessage", "O", "M", "G", "N", "x"}) {
            Method method = optionalDeclaredMethod(viewModelClass, name);
            if (method != null && method.getReturnType() == void.class) return accessible(method);
        }
        throw new NoSuchMethodException("sendMessage-compatible no-arg method not found on "
                + viewModelClass.getName());
    }

    static Method findRetryResponseMethod(Class<?> viewModelClass) {
        return findNamedVoidNoArg(viewModelClass,
                "retryLastResponse", "regenerateResponse", "retryResponse", "regenerate", "I", "G");
    }

    static Method findStopGenerationMethod(Class<?> viewModelClass) {
        return findNamedVoidNoArg(viewModelClass,
                "stopGeneration", "stopResponse", "stop", "P", "N");
    }

    private static Method findNamedVoidNoArg(Class<?> owner, String... names) {
        for (String name : names) {
            Method method = optionalDeclaredMethod(owner, name);
            if (method != null && method.getReturnType() == void.class) return accessible(method);
        }
        return null;
    }

    private static Method requireNoArgVoidHint(Class<?> owner, Method method, String role)
            throws NoSuchMethodException {
        if (method == null) return null;
        if (method.getDeclaringClass() != owner
                || method.getReturnType() != void.class
                || method.getParameterTypes().length != 0
                || Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException("structural " + role + " hint is incompatible with "
                    + owner.getName());
        }
        return accessible(method);
    }

    void validateAccessorBindings() throws Exception {
        if (accessors == null) return;
        if (!accessors.hasRetryAccessors()) return;
        validateStringAccessor(accessors.messageRole, "message role");
        validateStringAccessor(accessors.messageContent, "message content");
        validateStringAccessor(accessors.messageId, "message id");
    }

    private void validateStringAccessor(Method method, String label) throws NoSuchMethodException {
        if (method == null || method.getDeclaringClass() != messageClass
                || method.getReturnType() != String.class
                || method.getParameterTypes().length != 0
                || Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(label + " accessor is incompatible");
        }
    }

    static final class Accessors {
        final Method messageRole;
        final Method messageContent;
        final Method messageId;

        Accessors(Method messageRole, Method messageContent, Method messageId) {
            this.messageRole = messageRole;
            this.messageContent = messageContent;
            this.messageId = messageId;
        }

        boolean hasRetryAccessors() {
            return messageRole != null && messageContent != null && messageId != null;
        }
    }

    boolean hasRetryAccessors() {
        return accessors != null && accessors.hasRetryAccessors();
    }

    String messageRole(Object message) {
        return accessors == null ? null : invokeStringOrNull(message, accessors.messageRole);
    }

    String messageContent(Object message) {
        return accessors == null ? null : invokeStringOrNull(message, accessors.messageContent);
    }

    String messageId(Object message) {
        return accessors == null ? null : invokeStringOrNull(message, accessors.messageId);
    }

    Object copyMessageWithContent(Object message, String content) throws Exception {
        if (message == null) throw new NullPointerException("message");
        Method copyDefault = findMessageCopyDefault(message.getClass());
        Class<?>[] types = copyDefault.getParameterTypes();
        int fieldCount = types.length - 3;
        if (fieldCount < 3 || types[3] != String.class) {
            throw new NoSuchMethodException("message copy method has no content field");
        }
        Object[] args = new Object[types.length];
        args[0] = message;
        int mask = 0;
        for (int field = 0; field < fieldCount; field++) {
            args[field + 1] = primitiveDefault(types[field + 1]);
            mask |= 1 << field;
        }
        // u7's third data field is content. Clear only that default-mask bit.
        args[3] = content == null ? "" : content;
        mask &= ~(1 << 2);
        args[fieldCount + 1] = mask;
        args[fieldCount + 2] = null;
        return copyDefault.invoke(null, args);
    }


    Object currentState(Object viewModel) throws Exception {
        if (!minified || viewModelStateField == null || stateFlowValueMethod == null) return null;
        Object stateFlow = viewModelStateField.get(viewModel);
        return stateFlow == null ? null : stateFlowValueMethod.invoke(stateFlow);
    }

    @SuppressWarnings("unchecked")
    List<?> stateMessages(Object state) throws Exception {
        return state == null || stateMessagesMethod == null
                ? null : (List<?>) stateMessagesMethod.invoke(state);
    }

    boolean replaceStateMessages(Object viewModel, List<?> messages) throws Exception {
        if (!minified || viewModel == null || messages == null
                || viewModelStateField == null || stateFlowValueMethod == null) {
            return false;
        }
        Object stateFlow = viewModelStateField.get(viewModel);
        if (stateFlow == null) return false;
        Method compareAndSet = findStateFlowCompareAndSet(stateFlow.getClass());
        List<?> replacementMessages = new ArrayList<>(messages);
        for (int attempt = 0; attempt < 4; attempt++) {
            Object current = stateFlowValueMethod.invoke(stateFlow);
            if (current == null) return false;
            Object replacement = copyStateWithMessages(current, replacementMessages);
            if (Boolean.TRUE.equals(compareAndSet.invoke(stateFlow, current, replacement))) {
                return true;
            }
        }
        return false;
    }

    void persistMessages(String chatId, List<?> messages) throws Exception {
        if (!minified || chatId == null || chatId.trim().isEmpty() || messages == null) return;
        Object repository = singletonInstance(repositoryClass);
        if (repository == null) {
            throw new IllegalStateException("host repository singleton unavailable");
        }
        Method replaceMessages = findRepositoryReplaceMessages();
        replaceMessages.invoke(repository, chatId, new ArrayList<>(messages));
    }

    String currentChatId(Object viewModel) {
        try {
            Object state = currentState(viewModel);
            if (state == null || stateSessionMethod == null || sessionIdMethod == null) return null;
            Object session = stateSessionMethod.invoke(state);
            if (session == null) return null;
            Object value = sessionIdMethod.invoke(session);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static HostAbi resolveNamed(ClassLoader loader) throws Exception {
        Class<?> providerClass = Class.forName(NAMED_PROVIDER, false, loader);
        Class<?> configClass = Class.forName(NAMED_CONFIG, false, loader);
        Class<?> viewModelClass = Class.forName(NAMED_VIEW_MODEL, false, loader);
        Class<?> repositoryClass = Class.forName(NAMED_REPOSITORY, false, loader);
        Class<?> messageClass = Class.forName(NAMED_MESSAGE, false, loader);
        Method buildRequest = providerClass.getDeclaredMethod("buildOpenAiRequestBody",
                configClass, List.class, String.class, boolean.class);
        requireReturnType(buildRequest, String.class);
        Class<?> function1Class = Class.forName("kotlin.jvm.functions.Function1", false, loader);
        Method scanSseData = providerClass.getDeclaredMethod(
                "scanSseData", String.class, function1Class);
        requireReturnType(scanSseData, boolean.class);
        Method loadSession = findLoadSessionMethod(viewModelClass,
                Class.forName("android.content.Context", false, loader));
        Method streamMessages = findMethod(viewModelClass, void.class,
                configClass, List.class, function1Class);
        Method sendMessage = findSendMessageMethod(viewModelClass);
        Method retryResponse = findRetryResponseMethod(viewModelClass);
        Method stopGeneration = findStopGenerationMethod(viewModelClass);
        Method addMessage = repositoryClass.getMethod("addMessage", String.class, messageClass);
        HostAbi abi = new HostAbi(providerClass, configClass, viewModelClass, messageClass,
                repositoryClass, false, accessible(buildRequest), accessible(scanSseData),
                accessible(streamMessages), loadSession, sendMessage, retryResponse, stopGeneration,
                accessible(addMessage), bindAccessors(messageClass, null),
                null, null, null, null, null);
        abi.validateAccessorBindings();
        return abi;
    }

    private static HostAbi resolveMinified110(ClassLoader loader) throws Exception {
        Throwable firstError;
        try {
            return resolveMinifiedProfile(loader, MINIFIED_PROVIDER);
        } catch (Throwable error) {
            firstError = error;
        }
        Throwable alternateError;
        try {
            return resolveMinifiedProfile(loader, ALTERNATE_MINIFIED_PROVIDER);
        } catch (Throwable error) {
            alternateError = error;
        }
        try {
            return resolveMinifiedProfile(loader, CURRENT_MINIFIED_PROVIDER);
        } catch (Throwable currentError) {
            currentError.addSuppressed(firstError);
            currentError.addSuppressed(alternateError);
            if (currentError instanceof Exception) throw (Exception) currentError;
            throw new Exception(currentError);
        }
    }

    private static HostAbi resolveMinifiedProfile(ClassLoader loader, String providerName)
        throws Exception {
        Class<?> providerClass = loader.loadClass(providerName);
        Class<?> configClass = Class.forName(MINIFIED_CONFIG, false, loader);
        Class<?> viewModelClass = Class.forName(MINIFIED_VIEW_MODEL, false, loader);
        Class<?> messageClass = Class.forName(MINIFIED_MESSAGE, false, loader);
        Class<?> repositoryClass = Class.forName(MINIFIED_REPOSITORY, false, loader);
        Class<?> function1Class = Class.forName("kotlin.jvm.functions.Function1", false, loader);
        Class<?> contextClass = Class.forName("android.content.Context", false, loader);
        Method buildRequest = providerClass.getDeclaredMethod(
                "p", configClass, List.class, String.class, boolean.class);
        Method scanSseData = providerClass.getDeclaredMethod("t", String.class, function1Class);
        Method streamMessages = optionalDeclaredMethod(viewModelClass,
                "w", configClass, List.class, function1Class);
        Method loadSession = optionalDeclaredMethod(viewModelClass,
                "F", String.class, String.class, contextClass);
        Method sendMessage = optionalDeclaredMethod(viewModelClass, "P");
        Method retryResponse = optionalDeclaredMethod(viewModelClass, "J");
        Method stopGeneration = optionalDeclaredMethod(viewModelClass, "Q");
        Method addMessage = optionalMethod(repositoryClass, "c", String.class, messageClass);
        if (streamMessages != null && streamMessages.getReturnType() != void.class) streamMessages = null;
        if (loadSession != null && loadSession.getReturnType() != void.class) loadSession = null;
        if (sendMessage != null && sendMessage.getReturnType() != void.class) sendMessage = null;
        if (retryResponse != null && retryResponse.getReturnType() != void.class) retryResponse = null;
        if (stopGeneration != null && stopGeneration.getReturnType() != void.class) stopGeneration = null;
        if (addMessage != null && addMessage.getReturnType() != void.class) addMessage = null;

        Field viewModelState = null;
        Method stateFlowValue = null;
        Method stateMessages = null;
        Method stateSession = null;
        Method sessionId = null;
        Class<?> stateClass = optionalClass(loader, "oa");
        Class<?> sessionClass = optionalClass(loader, "na");
        if (stateClass != null && sessionClass != null) {
            try {
                viewModelState = findStateField(viewModelClass, stateClass);
                stateFlowValue = viewModelState.getType().getMethod("getValue");
                stateMessages = optionalDeclaredMethod(stateClass, "e");
                stateSession = optionalDeclaredMethod(stateClass, "j");
                sessionId = optionalDeclaredMethod(sessionClass, "d");
                if (stateMessages != null && !List.class.isAssignableFrom(stateMessages.getReturnType())) {
                    stateMessages = null;
                }
                if (stateSession != null && stateSession.getReturnType() != sessionClass) {
                    stateSession = null;
                }
                if (sessionId != null && sessionId.getReturnType() != String.class) sessionId = null;
            } catch (Throwable ignored) {
                viewModelState = null;
                stateFlowValue = null;
                stateMessages = null;
                stateSession = null;
                sessionId = null;
            }
        }
        HostAbi abi = new HostAbi(providerClass, configClass, viewModelClass, messageClass,
                repositoryClass, true, accessible(buildRequest), accessible(scanSseData),
                accessible(streamMessages), accessible(loadSession), accessible(sendMessage),
                accessible(retryResponse), accessible(stopGeneration), accessible(addMessage),
                bindAccessors(messageClass, null), accessible(viewModelState),
                accessible(stateFlowValue), accessible(stateMessages), accessible(stateSession),
                accessible(sessionId));
        abi.validateAccessorBindings();
        return abi;
    }

    static HostAbi minifiedRequestFromDex(Class<?> providerClass, Class<?> configClass,
            Method buildRequestMethod, Method scanSseDataMethod) throws Exception {
        requireReturnType(buildRequestMethod, String.class);
        requireReturnType(scanSseDataMethod, boolean.class);
        return new HostAbi(providerClass, configClass, null, null, null, true,
                accessible(buildRequestMethod), accessible(scanSseDataMethod),
                null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    static HostAbi minifiedFromDex(Class<?> providerClass, Class<?> configClass,
            Class<?> viewModelClass, Class<?> messageClass, Class<?> repositoryClass,
            Method buildRequestMethod, Method scanSseDataMethod, Method streamMessagesMethod,
            Method sendMessageHint, Method retryResponseHint, Method stopGenerationHint,
            Method repositoryAddMessageMethod, DexAbiScanner.AccessorNames accessorNames,
            Class<?> stateClass, Method stateMessagesMethod, Method stateSessionMethod,
            Method sessionIdMethod) throws Exception {
        requireReturnType(buildRequestMethod, String.class);
        requireReturnType(scanSseDataMethod, boolean.class);
        Method loadSession = findLoadSessionMethod(viewModelClass,
                Class.forName("android.content.Context", false, viewModelClass.getClassLoader()));
        Method sendMessage = requireNoArgVoidHint(viewModelClass, sendMessageHint, "sendMessage");
        if (sendMessage == null) sendMessage = findSendMessageMethod(viewModelClass);
        Method retryResponse = requireNoArgVoidHint(viewModelClass, retryResponseHint, "retryResponse");
        if (retryResponse == null) retryResponse = findRetryResponseMethod(viewModelClass);
        Method stopGeneration = requireNoArgVoidHint(viewModelClass, stopGenerationHint, "stopGeneration");
        if (stopGeneration == null) stopGeneration = findStopGenerationMethod(viewModelClass);
        Field viewModelState = findStateField(viewModelClass, stateClass);
        Method stateFlowValue = viewModelState.getType().getMethod("getValue");
        HostAbi abi = new HostAbi(providerClass, configClass, viewModelClass, messageClass,
                repositoryClass, true, accessible(buildRequestMethod), accessible(scanSseDataMethod),
                accessible(streamMessagesMethod), loadSession, sendMessage, retryResponse,
                stopGeneration, accessible(repositoryAddMessageMethod),
                bindAccessors(messageClass, accessorNames), accessible(viewModelState),
                accessible(stateFlowValue), accessible(stateMessagesMethod),
                accessible(stateSessionMethod), accessible(sessionIdMethod));
        abi.validateAccessorBindings();
        return abi;
    }

    private static Field findStateField(Class<?> viewModelClass, Class<?> stateClass)
            throws Exception {
        for (Field field : viewModelClass.getDeclaredFields()) {
            Method getter = optionalNoArg(field.getType(), "getValue");
            if (getter == null) continue;
            if (getter.getReturnType() == Object.class || getter.getReturnType() == stateClass) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException("StateFlow-compatible field not found on "
                + viewModelClass.getName());
    }

    private Object copyStateWithMessages(Object state, List<?> messages) throws Exception {
        Method copyDefault = findStateCopyDefault(state.getClass());
        Class<?>[] types = copyDefault.getParameterTypes();
        int fieldCount = types.length - 3;
        Object[] args = new Object[types.length];
        args[0] = state;
        int mask = 0;
        for (int field = 0; field < fieldCount; field++) {
            args[field + 1] = primitiveDefault(types[field + 1]);
            mask |= 1 << field;
        }
        // The host state contract places messages immediately after the session.
        args[2] = messages;
        mask &= ~(1 << 1);
        args[fieldCount + 1] = mask;
        args[fieldCount + 2] = null;
        return copyDefault.invoke(null, args);
    }

    private Method findStateCopyDefault(Class<?> stateClass) throws NoSuchMethodException {
        for (Method method : stateClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != stateClass
                    || types.length < 5
                    || types[0] != stateClass
                    || types[2] != List.class
                    || types[types.length - 2] != int.class
                    || types[types.length - 1] != Object.class) {
                continue;
            }
            return accessible(method);
        }
        throw new NoSuchMethodException("state copy$default-compatible method not found on "
                + stateClass.getName());
    }


    private Method findStateFlowCompareAndSet(Class<?> implementationClass)
            throws NoSuchMethodException {
        for (Class<?> owner : new Class<?>[]{viewModelStateField.getType(), implementationClass}) {
            for (String name : new String[]{"compareAndSet", "e"}) {
                Method method = optionalMethod(owner, name, Object.class, Object.class);
                if (method != null && (method.getReturnType() == boolean.class
                        || method.getReturnType() == Boolean.class)) {
                    return accessible(method);
                }
            }
            Method method = findBooleanTwoReferenceArgMethod(owner);
            if (method != null) return accessible(method);
        }
        throw new NoSuchMethodException("StateFlow compare-and-set method unavailable on "
                + implementationClass.getName());
    }

    private static Method findBooleanTwoReferenceArgMethod(Class<?> owner) {
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers())
                    || types.length != 2
                    || types[0].isPrimitive()
                    || types[1].isPrimitive()
                    || (method.getReturnType() != boolean.class
                            && method.getReturnType() != Boolean.class)) {
                continue;
            }
            return method;
        }
        return null;
    }

    private Method findRepositoryReplaceMessages() throws NoSuchMethodException {
        List<Method> matches = new ArrayList<>();
        for (Method method : repositoryClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
            && method.getReturnType() == void.class
            && types.length == 2
            && types[0] == String.class
            && types[1] == List.class) {
                matches.add(accessible(method));
            }
        }
        if (matches.size() != 1) {
            throw new NoSuchMethodException("repository message replacement candidate count="
            + matches.size() + " on " + repositoryClass.getName());
        }
        return matches.get(0);
    }

    private static Accessors bindAccessors(Class<?> messageClass,
            DexAbiScanner.AccessorNames names) throws NoSuchMethodException {
        return new Accessors(
                optionalAccessor(messageClass, String.class,
                        names == null ? null : names.messageRole, "getRole", "i"),
                optionalAccessor(messageClass, String.class,
                        names == null ? null : names.messageContent, "getContent", "c"),
                optionalAccessor(messageClass, String.class,
                        names == null ? null : names.messageId, "getId", "f"));
    }

    private static Method optionalAccessor(Class<?> owner, Class<?> returnType, String hint,
            String... fallbacks) {
        Method hinted = optionalAccessorByName(owner, returnType, hint);
        if (hinted != null) return hinted;
        for (String fallback : fallbacks) {
            Method method = optionalAccessorByName(owner, returnType, fallback);
            if (method != null) return method;
        }
        return null;
    }

    private static Method optionalAccessorByName(Class<?> owner, Class<?> returnType, String name) {
        if (name == null || name.isEmpty()) return null;
        Method method = optionalNoArg(owner, name);
        if (method == null || Modifier.isStatic(method.getModifiers())) return null;
        return returnType.isAssignableFrom(method.getReturnType()) ? accessible(method) : null;
    }

    private Method findMessageCopyDefault(Class<?> messageClass) throws NoSuchMethodException {
        for (Method method : messageClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != messageClass
                    || types.length < 6
                    || types[0] != messageClass
                    || types[types.length - 2] != int.class
                    || types[types.length - 1] != Object.class) {
                continue;
            }
            return accessible(method);
        }
        throw new NoSuchMethodException("message copy$default-compatible method not found on "
                + messageClass.getName());
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == char.class) return (char) 0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }

    private static void requireReturnType(Method method, Class<?> returnType)
            throws NoSuchMethodException {
        if (method.getReturnType() != returnType) {
            throw new NoSuchMethodException("unexpected return type for " + describe(method));
        }
    }

    private static Method findMethod(Class<?> owner, Class<?> returnType, Class<?>... paramTypes)
            throws NoSuchMethodException {
        List<String> candidates = new ArrayList<>();
        for (Method method : owner.getDeclaredMethods()) {
            candidates.add(describe(method));
            if (method.getReturnType() != returnType) continue;
            Class<?>[] actual = method.getParameterTypes();
            if (actual.length != paramTypes.length) continue;
            boolean match = true;
            for (int i = 0; i < actual.length; i++) {
                if (actual[i] != paramTypes[i]) {
                    match = false;
                    break;
                }
            }
            if (match) return accessible(method);
        }
        throw new NoSuchMethodException("method not found on " + owner.getName()
                + " return=" + returnType.getName() + " params=" + paramTypes.length
                + " candidates=" + candidates);
    }

    private static String invokeString(Object target, Method method) throws Exception {
        if (target == null) throw new NullPointerException("target");
        if (method == null) throw new NoSuchMethodException("bound accessor missing on "
                + target.getClass().getName());
        Object value = method.invoke(target);
        return value == null ? null : String.valueOf(value);
    }

    private static String invokeStringOrNull(Object target, Method method) {
        Object value = invokeNoArgOrNull(target, method);
        return value == null ? null : String.valueOf(value);
    }

    private static Object invokeNoArgOrNull(Object target, Method method) {
        if (target == null || method == null) return null;
        try {
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method optionalNoArg(Class<?> owner, String name) {
        try {
            return accessible(owner.getMethod(name));
        } catch (Throwable ignored) {
            try {
                return accessible(owner.getDeclaredMethod(name));
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static Method optionalMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (Throwable ignored) {
            return optionalDeclaredMethod(owner, name, params);
        }
    }

    private static Method optionalDeclaredMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getDeclaredMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> optionalClass(ClassLoader loader, String name) {
        try {
            return loader.loadClass(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String nonEmpty(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(label + " returned an empty value");
        }
        return value;
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }

    private static String describe(Method method) {
        StringBuilder result = new StringBuilder(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) result.append(',');
            result.append(params[i].getName());
        }
        return result.append("): ").append(method.getReturnType().getName()).toString();
    }

    private static String shortError(Throwable error) {
        if (error == null) return "none";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    public static void main(String[] args) throws Exception {
        HostAbi abi = resolve(HostAbi.class.getClassLoader(), args);
        check(abi.streamMessagesMethod != null, "stream method missing");
        check(abi.retryResponseMethod != null, "retry response method missing");
        check(abi.stopGenerationMethod != null, "stop generation method missing");
        check(abi.repositoryAddMessageMethod != null, "repository add-message method missing");
        check(abi.hasRetryAccessors(), "retry message accessors missing");
        abi.validateAccessorBindings();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static Object singletonInstance(Class<?> type) throws Exception {
        for (String fieldName : new String[]{"INSTANCE", "a"}) {
            Field field = optionalSingletonField(type, fieldName);
            if (field == null) continue;
            Object value = field.get(null);
            if (value != null) return value;
        }
        return null;
    }

    static boolean isStreamDoneEvent(String name) {
        return LEGACY_STREAM_DONE.equals(name) || CURRENT_STREAM_DONE.equals(name)
                || (name != null && (name.endsWith("$Done") || name.endsWith(".Done")));
    }

    static boolean isStreamErrorEvent(Object event, String name) {
        return event instanceof Throwable || LEGACY_STREAM_ERROR.equals(name)
                || CURRENT_STREAM_ERROR.equals(name)
                || (name != null && (name.endsWith("$Error") || name.endsWith(".Error")
                || name.endsWith("$Failure") || name.endsWith(".Failure")
                || name.endsWith("$Failed") || name.endsWith(".Failed")));
    }

    static String streamEventText(Object event) {
        Object chunk = readFirst(event, "getChunk", "chunk", "a");
        Object parts = readFirst(chunk, "getParts", "parts", "b");
        if (parts instanceof Iterable) {
            StringBuilder text = new StringBuilder();
            for (Object part : (Iterable<?>) parts) {
                if (part == null) continue;
                String name = part.getClass().getName();
                if (!"cb$b".equals(name) && !"xa$b".equals(name)
                        && !name.endsWith("$Text") && !name.endsWith(".Text")) continue;
                Object value = readFirst(part, "getText", "text", "c");
                if (value instanceof CharSequence) text.append(value);
            }
            return text.length() == 0 ? null : text.toString();
        }
        Object value = readFirst(event, "getContent", "content", "getText", "text",
                "getDelta", "delta", "getMessage", "message", "a", "b");
        if (!(value instanceof CharSequence)) return null;
        String text = value.toString();
        return text.isEmpty() || text.equals(event.getClass().getName()) ? null : text;
    }

    static boolean streamEventHasToolCall(Object event) {
        if (event == null) return false;
        String eventName = event.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (eventName.contains("tool")) return true;
        Object calls = readFirst(event, "getToolCalls", "toolCalls");
        Object id = readFirst(event, "getToolCallId", "toolCallId");
        if (hasValue(calls) || hasValue(id)) return true;
        Object chunk = readFirst(event, "getChunk", "chunk", "a");
        Object parts = readFirst(chunk, "getParts", "parts", "b");
        if (!(parts instanceof Iterable)) return false;
        for (Object part : (Iterable<?>) parts) {
            if (part == null) continue;
            String name = part.getClass().getName();
            if ("cb$c".equals(name) || "xa$c".equals(name)
                    || name.toLowerCase(java.util.Locale.ROOT).contains("tool")) {
                return true;
            }
        }
        return false;
    }

    private static Object readFirst(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            Object value = invokeNoArgOrNull(target, optionalNoArg(target.getClass(), name));
            if (hasValue(value)) return value;
        }
        return null;
    }

    private static boolean hasValue(Object value) {
        if (value == null) return false;
        if (value instanceof CharSequence) return ((CharSequence) value).length() > 0;
        if (value instanceof List) return !((List<?>) value).isEmpty();
        return true;
    }

    private static Field optionalSingletonField(Class<?> type, String fieldName) {
        try {
            Field field = type.getField(fieldName);
            if (Modifier.isStatic(field.getModifiers())) return accessible(field);
        } catch (Throwable ignored) {
        }
        try {
            Field field = type.getDeclaredField(fieldName);
            if (Modifier.isStatic(field.getModifiers())) return accessible(field);
        } catch (Throwable ignored) {
        }
        return null;
    }

    boolean isProvider(Object provider) {
        if (provider == null) return false;
        if (providerClass != null && providerClass.isInstance(provider)) return true;
        return minified && hasProviderStreamMethod(provider.getClass());
    }

    private boolean hasProviderStreamMethod(Class<?> providerClass) {
        for (Method method : providerClass.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 5
                    && types[0] == configClass
                    && types[1] == List.class
                    && types[2] == String.class
                    && "kotlin.jvm.functions.Function1".equals(types[3].getName())
                    && "kotlin.coroutines.Continuation".equals(types[4].getName())) {
                return true;
            }
        }
        return false;
    }
}
