package dev.operit.lspilot.enhancer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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
    final Method repositoryAddMessageMethod;
    final Constructor<?> messageConstructor;
    private final Field viewModelStateField;
    private final Method stateFlowValueMethod;
    private final Method stateMessagesMethod;
    private final Method stateConfigMethod;
    private final Method stateSelectedModelMethod;
    private final Method stateLoadingMethod;
    private final Method stateSessionMethod;
    private final Method sessionIdMethod;

    private HostAbi(Class<?> providerClass, Class<?> configClass, Class<?> viewModelClass,
            Class<?> messageClass, Class<?> repositoryClass, Class<?> aiChatRouteClass,
            boolean minified,
            Method buildRequestMethod, Method scanSseDataMethod, Method streamMessagesMethod,
            Method loadSessionMethod,
            Method sendMessageMethod, Method repositoryAddMessageMethod,
            Constructor<?> messageConstructor, Field viewModelStateField,
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
        this.repositoryAddMessageMethod = repositoryAddMessageMethod;
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
        try {
            return resolveNamed(loader);
        } catch (Throwable namedError) {
            try {
                HostAbi minified = resolveMinified110(loader);
                DebugLogger.w("named host ABI unavailable; using known minified ABI: "
                        + shortError(namedError));
                return minified;
            } catch (Throwable minifiedError) {
                if (dexPaths != null && dexPaths.length > 0) {
                    try {
                        HostAbi scanned = DexAbiScanner.resolve(loader, dexPaths);
                        DebugLogger.w("known minified ABI unavailable; using DEX-scanned ABI: "
                                + shortError(minifiedError));
                        return scanned;
                    } catch (Throwable scanError) {
                        NoSuchMethodException combined = new NoSuchMethodException(
                                "host ABI unavailable; named=" + shortError(namedError)
                                        + " minified=" + shortError(minifiedError)
                                        + " dex=" + shortError(scanError));
                        combined.initCause(scanError);
                        throw combined;
                    }
                }
                if (minifiedError instanceof Exception) throw (Exception) minifiedError;
                throw new Exception(minifiedError);
            }
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
        for (String name : new String[]{"sendMessage", "M", "G", "N", "x"}) {
            Method method = optionalDeclaredMethod(viewModelClass, name);
            if (method != null && method.getReturnType() == void.class) return accessible(method);
        }
        throw new NoSuchMethodException("sendMessage-compatible no-arg method not found on "
                + viewModelClass.getName());
    }

    static Method findRetryResponseMethod(Class<?> viewModelClass) {
        return findNamedVoidNoArg(viewModelClass,
                "retryLastResponse", "regenerateResponse", "retryResponse", "regenerate", "G");
    }

    static Method findStopGenerationMethod(Class<?> viewModelClass) {
        return findNamedVoidNoArg(viewModelClass,
                "stopGeneration", "stopResponse", "stop", "N");
    }

    private static Method findNamedVoidNoArg(Class<?> owner, String... names) {
        for (String name : names) {
            Method method = optionalDeclaredMethod(owner, name);
            if (method != null && method.getReturnType() == void.class) return accessible(method);
        }
        return null;
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
        return nonEmpty(readConfigString(config, "getProviderId", "o"), "providerId");
    }

    String modelName(Object config) throws Exception {
        return nonEmpty(readConfigString(config, "getModelName", "k"), "modelName");
    }

    String apiKey(Object config) throws Exception {
        return nonEmpty(readConfigString(config, "getApiKey", "f"), "apiKey");
    }

    String fullApiUrl(Object config) throws Exception {
        return nonEmpty(readConfigString(config, "getFullApiUrl", "i"), "fullApiUrl");
    }

    String providerSignature(Object config) throws Exception {
        return fullApiUrl(config) + "\n" + modelName(config);
    }

    boolean hasCompressionAccessors() {
        return hasAnyNoArg(configClass, "getModelName", "k")
                && hasAnyNoArg(configClass, "getFullApiUrl", "i")
                && hasAnyNoArg(messageClass, "getRole", "i")
                && hasAnyNoArg(messageClass, "getContent", "c");
    }

    String messageRole(Object message) {
        return readStringOrNull(message, "getRole", "i");
    }

    String messageContent(Object message) {
        return readStringOrNull(message, "getContent", "c");
    }

    private Object messageValue(Object message, String namedMethod, String minifiedMethod) {
        return invokeNoArgOrNull(message, namedMethod, minifiedMethod);
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
            Object toolCalls = messageValue(message, "getToolCalls", "p");
            Object toolCallId = messageValue(message, "getToolCallId", "n");
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

    List<Object> materializeMessages(JSONArray messages, List<?> originals) throws Exception {
        List<Object> result = new ArrayList<>();
        if (messages == null) return result;
        boolean[] used = originals == null ? new boolean[0] : new boolean[originals.size()];
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null) continue;
            String role = message.optString("role", "user");
            String content = message.optString("content", "");
            int originalIndex = message.optInt(HOST_INDEX, -1);
            if (!matchesOriginal(originals, used, originalIndex, role, content)) {
                originalIndex = findOriginalMessage(originals, used, role, content);
            }
            if (originalIndex >= 0) {
                used[originalIndex] = true;
                result.add(originals.get(originalIndex));
            } else {
                result.add(newStatusMessage("lspilot-enhancer-request-" + UUID.randomUUID(),
                        role, content, System.currentTimeMillis()));
            }
        }
        return result;
    }
    private boolean matchesOriginal(List<?> originals, boolean[] used, int index,
            String role, String content) {
        if (originals == null || index < 0 || index >= originals.size() || used[index]) return false;
        Object candidate = originals.get(index);
        return same(role, messageRole(candidate)) && same(content, messageContent(candidate));
    }

    private int findOriginalMessage(List<?> originals, boolean[] used, String role, String content) {
        if (originals == null) return -1;
        for (int index = 0; index < originals.size(); index++) {
            if (matchesOriginal(originals, used, index, role, content)) return index;
        }
        return -1;
    }

    private static boolean same(String first, String second) {
        return first == null ? second == null : first.equals(second);
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
        Method addMessage = repositoryClass.getMethod("addMessage", String.class, messageClass);
        Constructor<?> messageConstructor = findMessageConstructor(messageClass);
        Class<?> aiChatRouteClass = optionalClass(loader,
                "me.yun.lspilot.ui.navigation.Route$AiChat");
        return new HostAbi(providerClass, configClass, viewModelClass, messageClass, repositoryClass,
                aiChatRouteClass, false, accessible(buildRequest), accessible(scanSseData),
                accessible(streamMessages), loadSession, sendMessage,
                accessible(addMessage), messageConstructor, null, null, null, null, null, null, null, null);
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
        return new HostAbi(providerClass, configClass, viewModelClass, messageClass, repositoryClass,
                aiChatRouteClass, true, accessible(buildRequest), accessible(scanSseData),
                accessible(streamMessages), loadSession, sendMessage,
                accessible(addMessage), messageConstructor, accessible(viewModelState),
                accessible(stateFlowValue), accessible(stateMessages), accessible(stateConfig),
                accessible(stateSelectedModel), accessible(stateLoading), accessible(stateSession),
                accessible(sessionId));
    }

    static HostAbi minifiedFromDex(Class<?> providerClass, Class<?> configClass,
            Class<?> viewModelClass, Class<?> messageClass, Class<?> repositoryClass,
            Class<?> aiChatRouteClass, Method buildRequestMethod, Method scanSseDataMethod,
            Method streamMessagesMethod, Method repositoryAddMessageMethod, Class<?> stateClass,
            Method stateMessagesMethod, Method stateConfigMethod, Method stateSelectedModelMethod,
            Method stateLoadingMethod, Method stateSessionMethod, Method sessionIdMethod)
            throws Exception {
        requireReturnType(buildRequestMethod, String.class);
        requireReturnType(scanSseDataMethod, boolean.class);
        Method loadSession = findLoadSessionMethod(viewModelClass,
                Class.forName("android.content.Context", false, viewModelClass.getClassLoader()));
        Method sendMessage = findSendMessageMethod(viewModelClass);
        Constructor<?> messageConstructor = findMessageConstructor(messageClass);
        Field viewModelState = findStateField(viewModelClass, stateClass);
        Method stateFlowValue = viewModelState.getType().getMethod("getValue");
        return new HostAbi(providerClass, configClass, viewModelClass, messageClass, repositoryClass,
                aiChatRouteClass, true, accessible(buildRequestMethod), accessible(scanSseDataMethod),
                accessible(streamMessagesMethod), loadSession, sendMessage,
                accessible(repositoryAddMessageMethod), messageConstructor, accessible(viewModelState),
                accessible(stateFlowValue), accessible(stateMessagesMethod), accessible(stateConfigMethod),
                accessible(stateSelectedModelMethod), accessible(stateLoadingMethod),
                accessible(stateSessionMethod), accessible(sessionIdMethod));
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

    private static Constructor<?> findMessageConstructor(Class<?> messageClass) throws Exception {
        Class<?>[] types = {
                String.class, String.class, String.class, boolean.class, String.class,
                long.class, long.class, long.class, int.class, List.class, int.class,
                List.class, List.class, String.class, List.class, boolean.class,
                int.class, int.class, int.class, int.class, long.class, int.class,
                int.class, long.class, int.class
        };
        Constructor<?> constructor = messageClass.getConstructor(types);
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

    private String readConfigString(Object config, String namedMethod, String minifiedMethod) throws Exception {
        if (config == null) throw new NullPointerException("config");
        Method method = optionalNoArg(config.getClass(), namedMethod);
        if (method == null) method = optionalNoArg(config.getClass(), minifiedMethod);
        if (method == null) {
            throw new NoSuchMethodException(namedMethod + "/" + minifiedMethod
                    + " not found on " + config.getClass().getName());
        }
        Object value = method.invoke(config);
        return value == null ? null : String.valueOf(value);
    }

    private static String readStringOrNull(Object target, String namedMethod, String minifiedMethod) {
        Object value = invokeNoArgOrNull(target, namedMethod, minifiedMethod);
        return value == null ? null : String.valueOf(value);
    }

    private static Object invokeNoArgOrNull(Object target, String namedMethod,
            String minifiedMethod) {
        if (target == null) return null;
        try {
            Method method = optionalNoArg(target.getClass(), namedMethod);
            if (method == null) method = optionalNoArg(target.getClass(), minifiedMethod);
            return method == null ? null : method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasAnyNoArg(Class<?> owner, String... names) {
        for (String name : names) {
            if (optionalNoArg(owner, name) != null) return true;
        }
        return false;
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
        assert abi.streamMessagesMethod != null;
        assert findRetryResponseMethod(abi.viewModelClass) != null;
        assert findStopGenerationMethod(abi.viewModelClass) != null;
        assert abi.repositoryAddMessageMethod != null;
    }

    static Object singletonInstance(Class<?> type) throws Exception {
        for (String fieldName : new String[]{"INSTANCE", "a"}) {
            try {
                Field field = type.getField(fieldName);
                Object value = field.get(null);
                if (value != null) return value;
            } catch (Throwable ignored) {
            }
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