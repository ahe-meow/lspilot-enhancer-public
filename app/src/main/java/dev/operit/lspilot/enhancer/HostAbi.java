package dev.operit.lspilot.enhancer;

import android.content.pm.ApplicationInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Host ABI resolver for named debug builds and minified release builds. */
final class HostAbi {
    private static final String NAMED_PROVIDER = "me.yun.lspilot.data.provider.OpenAiApiProvider";
    private static final String NAMED_CONFIG = "me.yun.lspilot.data.model.AiProviderConfig";
    private static final String NAMED_VIEW_MODEL = "me.yun.lspilot.ui.viewmodel.AiChatViewModel";
    private static final String NAMED_REPOSITORY = "me.yun.lspilot.data.repository.AiChatRepository";
    private static final String NAMED_MESSAGE = "me.yun.lspilot.data.model.AiChatMessage";

    // LSPilot 1.1.0(11) release APK: r8-map-id-2546d18ea02fa35bf26d0f869df16f1ff4c4106246dc837609bbbe09e1fa1588
    private static final String MINIFIED_PROVIDER = "ts8";
    private static final String MINIFIED_VIEW_MODEL = "cb";
    private static final String MINIFIED_CONFIG = "jb";
    private static final String MINIFIED_MESSAGE = "g8";
    private static final String MINIFIED_REPOSITORY = "me.yun.lspilot.data.repository.b";
    private static final String HOST_INDEX = "_lspilot_host_index";
    private static final String HOST_TOOL_CALLS = "_lspilot_tool_calls";
    private static final String HOST_TOOL_CALL_ID = "_lspilot_tool_call_id";

    final Class<?> providerClass;
    final Class<?> configClass;
    final Class<?> viewModelClass;
    final Class<?> messageClass;
    final Class<?> repositoryClass;
    final Class<?> aiChatRouteClass;
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
    final Constructor<?> messageConstructor;
    final Field viewModelStateField;
    final Method stateFlowValueMethod;
    final Method stateMessagesMethod;
    final Method stateConfigMethod;
    final Method stateSelectedModelMethod;
    final Method stateLoadingMethod;
    final Method stateSessionMethod;
    final Method sessionIdMethod;

    HostAbi(Class<?> providerClass, Class<?> configClass, Class<?> viewModelClass,
            Class<?> messageClass, Class<?> repositoryClass, Class<?> aiChatRouteClass,
            boolean minified,
            Method buildRequestMethod, Method scanSseDataMethod, Method streamMessagesMethod,
            Method loadSessionMethod, Method sendMessageMethod, Method retryResponseMethod,
            Method stopGenerationMethod, Method repositoryAddMessageMethod,
            Accessors accessors, Constructor<?> messageConstructor, Field viewModelStateField,
            Method stateFlowValueMethod, Method stateMessagesMethod, Method stateConfigMethod,
            Method stateSelectedModelMethod, Method stateLoadingMethod, Method stateSessionMethod,
            Method sessionIdMethod) {
        this.providerClass = providerClass;
        this.configClass = configClass;
        this.viewModelClass = viewModelClass;
        this.messageClass = messageClass;
        this.repositoryClass = repositoryClass;
        this.aiChatRouteClass = aiChatRouteClass;
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
        this.messageConstructor = messageConstructor;
        this.viewModelStateField = viewModelStateField;
        this.stateFlowValueMethod = stateFlowValueMethod;
        this.stateMessagesMethod = stateMessagesMethod;
        this.stateConfigMethod = stateConfigMethod;
        this.stateSelectedModelMethod = stateSelectedModelMethod;
        this.stateLoadingMethod = stateLoadingMethod;
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
        Throwable namedError;
        try {
            return resolveNamed(loader);
        } catch (Throwable error) {
            namedError = error;
        }

        Throwable dexKitError = null;
        Throwable dexScanError = null;
        if (dexPaths != null && dexPaths.length > 0) {
            try {
                HostAbi scanned = DexKitAbiScanner.resolve(loader, dexPaths);
                DebugLogger.w("named host ABI unavailable; using DexKit-assisted ABI: "
                        + shortError(namedError));
                return scanned;
            } catch (Throwable error) {
                dexKitError = error;
                DebugLogger.w("DexKit-assisted ABI unavailable; trying legacy DEX scan: "
                        + shortError(error));
            }
            try {
                HostAbi scanned = DexAbiScanner.resolve(loader, dexPaths);
                DebugLogger.w("DexKit-assisted ABI unavailable; using legacy DEX scan: "
                        + shortError(dexKitError));
                return scanned;
            } catch (Throwable error) {
                dexScanError = error;
            }
        }

        try {
            HostAbi minified = resolveMinified110(loader);
            DebugLogger.w("dynamic host ABI unavailable; using known minified ABI: dexkit="
                    + shortError(dexKitError) + " dex=" + shortError(dexScanError));
            return minified;
        } catch (Throwable minifiedError) {
            NoSuchMethodException combined = new NoSuchMethodException(
                    "host ABI unavailable; named=" + shortError(namedError)
                            + " dexkit=" + shortError(dexKitError)
                            + " dex=" + shortError(dexScanError)
                            + " minified=" + shortError(minifiedError));
            combined.initCause(dexScanError != null ? dexScanError
                    : (dexKitError != null ? dexKitError : minifiedError));
            throw combined;
        }
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
            throw new NoSuchMethodException("DexKit " + role + " hint is incompatible with "
                    + owner.getName());
        }
        return accessible(method);
    }

    void validateAccessorBindings() throws Exception {
        if (accessors == null || !accessors.hasCompressionAccessors()) {
            throw new NoSuchMethodException("config/message accessors are incomplete");
        }
        Object config = newConfigProbe();
        checkEquals("provider_probe", providerId(config), "providerId accessor");
        checkEquals("model_probe", modelName(config), "modelName accessor");
        checkEquals("key_probe", apiKey(config), "apiKey accessor");
        checkEquals("https://probe.local/v1", fullApiUrl(config), "fullApiUrl accessor");
        Object message = newStatusMessage("message_probe", "user", "content_probe", 1L);
        checkEquals("message_probe", messageId(message), "message id accessor");
        checkEquals("user", messageRole(message), "message role accessor");
        checkEquals("content_probe", messageContent(message), "message content accessor");
    }

    private Object newConfigProbe() throws Exception {
        Constructor<?> constructor = configClass.getDeclaredConstructor(
                String.class, String.class, String.class, String.class,
                String.class, String.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance("id_probe", "name_probe", "provider_probe", "key_probe",
                "https://probe.local", "/v1", Collections.singletonList("model_probe"));
    }

    private static void checkEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " returned " + actual);
        }
    }

    static final class Accessors {
        final Method configProviderId;
        final Method configModelName;
        final Method configApiKey;
        final Method configFullApiUrl;
        final Method messageRole;
        final Method messageContent;
        final Method messageId;
        final Method messageToolCalls;
        final Method messageToolCallId;

        Accessors(Method configProviderId, Method configModelName, Method configApiKey,
                Method configFullApiUrl, Method messageRole, Method messageContent,
                Method messageId, Method messageToolCalls, Method messageToolCallId) {
            this.configProviderId = configProviderId;
            this.configModelName = configModelName;
            this.configApiKey = configApiKey;
            this.configFullApiUrl = configFullApiUrl;
            this.messageRole = messageRole;
            this.messageContent = messageContent;
            this.messageId = messageId;
            this.messageToolCalls = messageToolCalls;
            this.messageToolCallId = messageToolCallId;
        }

        boolean hasCompressionAccessors() {
            return configModelName != null && configFullApiUrl != null
                    && messageRole != null && messageContent != null;
        }
    }

    Object newStatusMessage(String id, String role, String content, long timestamp) throws Exception {
        if (messageConstructor == null) throw new NoSuchMethodException("message constructor unavailable");
        return messageConstructor.newInstance(
                id, role, content, false, "", 0L, 0L, timestamp, 0,
                Collections.emptyList(), 0, Collections.emptyList(),
                Collections.emptyList(), "", Collections.singletonList("content"),
                false, 0, 0, 0, 0, 0L, 0, 0, 0L, 0);
    }

    Object copyConfigWithSingleModel(Object config, String selectedModel) throws Exception {
        if (config == null || selectedModel == null || selectedModel.trim().isEmpty()) return config;
        Method copyDefault = findConfigCopyDefault(config.getClass());
        return copyDefault.invoke(null, config, null, null, null, null, null,
                null, Collections.singletonList(selectedModel), 0x3f, null);
    }

    String providerId(Object config) throws Exception {
        return nonEmpty(invokeString(config, accessors.configProviderId), "providerId");
    }

    String modelName(Object config) throws Exception {
        return nonEmpty(invokeString(config, accessors.configModelName), "modelName");
    }

    String apiKey(Object config) throws Exception {
        return nonEmpty(invokeString(config, accessors.configApiKey), "apiKey");
    }

    String fullApiUrl(Object config) throws Exception {
        return nonEmpty(invokeString(config, accessors.configFullApiUrl), "fullApiUrl");
    }

    String providerSignature(Object config) throws Exception {
        return fullApiUrl(config) + "\n" + modelName(config);
    }

    boolean hasCompressionAccessors() {
        return accessors != null && accessors.hasCompressionAccessors();
    }

    String messageRole(Object message) {
        return invokeStringOrNull(message, accessors.messageRole);
    }

    String messageContent(Object message) {
        return invokeStringOrNull(message, accessors.messageContent);
    }

    String messageId(Object message) {
        return invokeStringOrNull(message, accessors.messageId);
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
        // g8's third data field is content. Clear only that default-mask bit.
        args[3] = content == null ? "" : content;
        mask &= ~(1 << 2);
        args[fieldCount + 1] = mask;
        args[fieldCount + 2] = null;
        return copyDefault.invoke(null, args);
    }


    private Object messageValue(Object message, Method method) {
        return invokeNoArgOrNull(message, method);
    }

    Object currentState(Object viewModel) throws Exception {
        if (!minified || viewModelStateField == null || stateFlowValueMethod == null) return null;
        Object stateFlow = viewModelStateField.get(viewModel);
        return stateFlow == null ? null : stateFlowValueMethod.invoke(stateFlow);
    }

    @SuppressWarnings("unchecked")
    List<?> stateMessages(Object state) throws Exception {
        return state == null ? null : (List<?>) stateMessagesMethod.invoke(state);
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

    Object stateConfig(Object state) throws Exception {
        return state == null ? null : stateConfigMethod.invoke(state);
    }

    String stateSelectedModel(Object state) throws Exception {
        if (state == null) return null;
        Object value = stateSelectedModelMethod.invoke(state);
        return value == null ? null : String.valueOf(value);
    }

    boolean stateLoading(Object state) throws Exception {
        return state != null && Boolean.TRUE.equals(stateLoadingMethod.invoke(state));
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

    JSONArray serializeMessages(List<?> messages) throws Exception {
        JSONArray result = new JSONArray();
        if (messages == null) return result;
        for (int index = 0; index < messages.size(); index++) {
            Object message = messages.get(index);
            String role = messageRole(message);
            String content = messageContent(message);
            if (role == null || content == null) continue;
            JSONObject serialized = new JSONObject().put("role", role).put("content", content)
                    .put(HOST_INDEX, index);
            Object toolCalls = messageValue(message, accessors.messageToolCalls);
            Object toolCallId = messageValue(message, accessors.messageToolCallId);
            if ("assistant".equals(role) && hasItems(toolCalls)) {
                serialized.put(HOST_TOOL_CALLS, String.valueOf(toolCalls));
            }
            if (toolCallId != null && !String.valueOf(toolCallId).isEmpty()) {
                serialized.put(HOST_TOOL_CALL_ID, String.valueOf(toolCallId));
            }
            result.put(serialized);
        }
        return result;
    }

    private static boolean hasItems(Object value) {
        if (value instanceof List) return !((List<?>) value).isEmpty();
        if (value instanceof JSONArray) return ((JSONArray) value).length() > 0;
        if (value instanceof String) {
            String text = ((String) value).trim();
            return !text.isEmpty() && !"[]".equals(text) && !"null".equals(text);
        }
        return value != null && !JSONObject.NULL.equals(value);
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
        Class<?> function1Class = Class.forName(
                "kotlin.jvm.functions.Function1", false, loader);
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
        Constructor<?> messageConstructor = findMessageConstructor(messageClass);
        Class<?> aiChatRouteClass = optionalClass(loader,
                "me.yun.lspilot.ui.navigation.Route$AiChat");
        HostAbi abi = new HostAbi(providerClass, configClass, viewModelClass, messageClass, repositoryClass,
                aiChatRouteClass, false, accessible(buildRequest), accessible(scanSseData),
                accessible(streamMessages), loadSession, sendMessage, retryResponse, stopGeneration,
                accessible(addMessage), bindAccessors(configClass, messageClass, null),
                messageConstructor, null, null, null, null, null, null, null, null);
        abi.validateAccessorBindings();
        return abi;
    }

    private static HostAbi resolveMinified110(ClassLoader loader) throws Exception {
        Class<?> providerClass = Class.forName(MINIFIED_PROVIDER, false, loader);
        Class<?> configClass = Class.forName(MINIFIED_CONFIG, false, loader);
        Class<?> viewModelClass = Class.forName(MINIFIED_VIEW_MODEL, false, loader);
        Class<?> messageClass = Class.forName(MINIFIED_MESSAGE, false, loader);
        Class<?> repositoryClass = Class.forName(MINIFIED_REPOSITORY, false, loader);
        Method buildRequest = providerClass.getDeclaredMethod("p",
                configClass, List.class, String.class, boolean.class);
        requireReturnType(buildRequest, String.class);
        Class<?> function1Class = Class.forName(
                "kotlin.jvm.functions.Function1", false, loader);
        Method scanSseData = providerClass.getDeclaredMethod("t", String.class, function1Class);
        requireReturnType(scanSseData, boolean.class);
        Method streamMessages = findMethod(viewModelClass, void.class,
                configClass, List.class, function1Class);
        Method loadSession = findLoadSessionMethod(viewModelClass,
                Class.forName("android.content.Context", false, loader));
        Method sendMessage = findSendMessageMethod(viewModelClass);
        Method retryResponse = findRetryResponseMethod(viewModelClass);
        Method stopGeneration = findStopGenerationMethod(viewModelClass);
        Method addMessage = repositoryClass.getMethod("c", String.class, messageClass);
        Constructor<?> messageConstructor = findMessageConstructor(messageClass);
        Class<?> stateClass = Class.forName("va", false, loader);
        Field viewModelState = findStateField(viewModelClass, stateClass);
        Method stateFlowValue = viewModelState.getType().getMethod("getValue");
        Method stateMessages = stateClass.getMethod("d");
        Method stateConfig = stateClass.getMethod("g");
        Method stateSelectedModel = stateClass.getMethod("f");
        Method stateLoading = stateClass.getMethod("i");
        Method stateSession = stateClass.getMethod("h");
        Class<?> sessionClass = Class.forName("ua", false, loader);
        Method sessionId = sessionClass.getMethod("d");
        Class<?> aiChatRouteClass = optionalClass(loader, "lka$b");
        HostAbi abi = new HostAbi(providerClass, configClass, viewModelClass, messageClass, repositoryClass,
                aiChatRouteClass, true, accessible(buildRequest), accessible(scanSseData),
                accessible(streamMessages), loadSession, sendMessage, retryResponse, stopGeneration,
                accessible(addMessage), bindAccessors(configClass, messageClass, null),
                messageConstructor, accessible(viewModelState),
                accessible(stateFlowValue), accessible(stateMessages), accessible(stateConfig),
                accessible(stateSelectedModel), accessible(stateLoading), accessible(stateSession),
                accessible(sessionId));
        abi.validateAccessorBindings();
        return abi;
    }

    static HostAbi minifiedFromDex(Class<?> providerClass, Class<?> configClass,
            Class<?> viewModelClass, Class<?> messageClass, Class<?> repositoryClass,
            Class<?> aiChatRouteClass, Method buildRequestMethod, Method scanSseDataMethod,
            Method streamMessagesMethod, Method sendMessageHint, Method retryResponseHint,
            Method stopGenerationHint, Method repositoryAddMessageMethod,
            DexAbiScanner.AccessorNames accessorNames, Class<?> stateClass,
            Method stateMessagesMethod, Method stateConfigMethod, Method stateSelectedModelMethod,
            Method stateLoadingMethod, Method stateSessionMethod, Method sessionIdMethod)
            throws Exception {
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
        Constructor<?> messageConstructor = findMessageConstructor(messageClass);
        Field viewModelState = findStateField(viewModelClass, stateClass);
        Method stateFlowValue = viewModelState.getType().getMethod("getValue");
        HostAbi abi = new HostAbi(providerClass, configClass, viewModelClass, messageClass, repositoryClass,
                aiChatRouteClass, true, accessible(buildRequestMethod), accessible(scanSseDataMethod),
                accessible(streamMessagesMethod), loadSession, sendMessage, retryResponse, stopGeneration,
                accessible(repositoryAddMessageMethod), bindAccessors(configClass, messageClass, accessorNames),
                messageConstructor, accessible(viewModelState),
                accessible(stateFlowValue), accessible(stateMessagesMethod), accessible(stateConfigMethod),
                accessible(stateSelectedModelMethod), accessible(stateLoadingMethod),
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
        return copyDefault.invoke(null, state, null, messages, null, false,
                null, null, null, null, 0xfd, null);
    }

    private Method findStateCopyDefault(Class<?> stateClass) throws NoSuchMethodException {
        for (Method method : stateClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != stateClass
                    || types.length != 11
                    || types[0] != stateClass
                    || types[2] != List.class
                    || types[3] != String.class
                    || types[4] != boolean.class
                    || types[5] != List.class
                    || types[6] != configClass
                    || types[7] != String.class
                    || types[8] != String.class
                    || types[9] != int.class
                    || types[10] != Object.class) {
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
        for (String name : new String[]{"replaceMessages", "saveMessages", "p"}) {
            Method method = optionalMethod(repositoryClass, name, String.class, List.class);
            if (method != null && method.getReturnType() == void.class) {
                return accessible(method);
            }
        }
        throw new NoSuchMethodException("repository message replacement method unavailable on "
                + repositoryClass.getName());
    }

    private static Accessors bindAccessors(Class<?> configClass, Class<?> messageClass,
            DexAbiScanner.AccessorNames names) throws NoSuchMethodException {
        return new Accessors(
                requiredStringAccessor(configClass, hint(names, "configProviderId"),
                        "getProviderId", "o"),
                requiredStringAccessor(configClass, hint(names, "configModelName"),
                        "getModelName", "k"),
                requiredStringAccessor(configClass, hint(names, "configApiKey"),
                        "getApiKey", "f"),
                requiredStringAccessor(configClass, hint(names, "configFullApiUrl"),
                        "getFullApiUrl", "i"),
                requiredStringAccessor(messageClass, hint(names, "messageRole"),
                        "getRole", "i"),
                requiredStringAccessor(messageClass, hint(names, "messageContent"),
                        "getContent", "c"),
                requiredStringAccessor(messageClass, hint(names, "messageId"),
                        "getId", "f"),
                optionalAccessor(messageClass, List.class, hint(names, "messageToolCalls"),
                        "getToolCalls", "p"),
                optionalAccessor(messageClass, String.class, hint(names, "messageToolCallId"),
                        "getToolCallId", "n"));
    }

    private static String hint(DexAbiScanner.AccessorNames names, String key) {
        if (names == null) return null;
        if ("configProviderId".equals(key)) return names.configProviderId;
        if ("configModelName".equals(key)) return names.configModelName;
        if ("configApiKey".equals(key)) return names.configApiKey;
        if ("configFullApiUrl".equals(key)) return names.configFullApiUrl;
        if ("messageRole".equals(key)) return names.messageRole;
        if ("messageContent".equals(key)) return names.messageContent;
        if ("messageId".equals(key)) return names.messageId;
        if ("messageToolCalls".equals(key)) return names.messageToolCalls;
        if ("messageToolCallId".equals(key)) return names.messageToolCallId;
        return null;
    }

    private static Method requiredStringAccessor(Class<?> owner, String hint, String... fallbacks)
            throws NoSuchMethodException {
        Method method = optionalAccessor(owner, String.class, hint, fallbacks);
        if (method == null) {
            throw new NoSuchMethodException("String accessor unavailable on " + owner.getName());
        }
        return method;
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

    private static Method findConfigCopyDefault(Class<?> configClass) throws Exception {
        Method named = optionalDeclaredMethod(configClass, "copy$default", configClass,
                String.class, String.class, String.class, String.class, String.class,
                String.class, List.class, int.class, Object.class);
        if (named != null) return accessible(named);
        for (Method method : configClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == configClass
                    && types.length == 10
                    && types[0] == configClass
                    && types[1] == String.class
                    && types[2] == String.class
                    && types[3] == String.class
                    && types[4] == String.class
                    && types[5] == String.class
                    && types[6] == String.class
                    && types[7] == List.class
                    && types[8] == int.class) {
                return accessible(method);
            }
        }
        throw new NoSuchMethodException("copy$default-compatible method not found on "
                + configClass.getName());
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

    private static Constructor<?> findMessageConstructor(Class<?> messageClass) throws Exception {
        Class<?>[] types = {
                String.class, String.class, String.class, boolean.class, String.class,
                long.class, long.class, long.class, int.class, List.class, int.class,
                List.class, List.class, String.class, List.class, boolean.class,
                int.class, int.class, int.class, int.class, long.class, int.class,
                int.class, long.class, int.class
        };
        Constructor<?> constructor = messageClass.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor;
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
            return Class.forName(name, false, loader);
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
        check(abi.hasCompressionAccessors(), "compression accessors missing");
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
