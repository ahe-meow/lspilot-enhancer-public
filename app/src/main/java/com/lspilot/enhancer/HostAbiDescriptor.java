package com.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Serializes the verified ABI so normal starts do not need a DEX scan. */
final class HostAbiDescriptor {
    private HostAbiDescriptor() {}

    static JSONObject encode(HostAbi abi) throws Exception {
        JSONObject result = new JSONObject();
        result.put("minified", abi.minified);
        putClass(result, "providerClass", abi.providerClass);
        putClass(result, "configClass", abi.configClass);
        putClass(result, "viewModelClass", abi.viewModelClass);
        putClass(result, "messageClass", abi.messageClass);
        putClass(result, "repositoryClass", abi.repositoryClass);
        putMethod(result, "buildRequestMethod", abi.buildRequestMethod);
        putMethod(result, "scanSseDataMethod", abi.scanSseDataMethod);
        putMethod(result, "streamMessagesMethod", abi.streamMessagesMethod);
        putMethod(result, "loadSessionMethod", abi.loadSessionMethod);
        putMethod(result, "sendMessageMethod", abi.sendMessageMethod);
        putMethod(result, "retryResponseMethod", abi.retryResponseMethod);
        putMethod(result, "stopGenerationMethod", abi.stopGenerationMethod);
        putMethod(result, "repositoryAddMessageMethod", abi.repositoryAddMessageMethod);
        putMethod(result, "messageRoleMethod",
                abi.accessors == null ? null : abi.accessors.messageRole);
        putMethod(result, "messageContentMethod",
                abi.accessors == null ? null : abi.accessors.messageContent);
        putMethod(result, "messageIdMethod",
                abi.accessors == null ? null : abi.accessors.messageId);
        putField(result, "viewModelStateField", abi.viewModelStateField);
        putMethod(result, "stateFlowValueMethod", abi.stateFlowValueMethod);
        putMethod(result, "stateMessagesMethod", abi.stateMessagesMethod);
        putMethod(result, "stateSessionMethod", abi.stateSessionMethod);
        putMethod(result, "sessionIdMethod", abi.sessionIdMethod);
        return result;
    }

    static HostAbi decode(ClassLoader loader, JSONObject value) throws Exception {
        Class<?> providerClass = readClass(loader, value, "providerClass", true);
        Class<?> configClass = readClass(loader, value, "configClass", true);
        Class<?> viewModelClass = readClass(loader, value, "viewModelClass", false);
        Class<?> messageClass = readClass(loader, value, "messageClass", false);
        Class<?> repositoryClass = readClass(loader, value, "repositoryClass", false);
        boolean minified = value.getBoolean("minified");

        HostAbi abi = new HostAbi(
                providerClass,
                configClass,
                viewModelClass,
                messageClass,
                repositoryClass,
                minified,
                readMethod(loader, value, "buildRequestMethod", true),
                readMethod(loader, value, "scanSseDataMethod", true),
                readMethod(loader, value, "streamMessagesMethod", false),
                readMethod(loader, value, "loadSessionMethod", false),
                readMethod(loader, value, "sendMessageMethod", false),
                readMethod(loader, value, "retryResponseMethod", false),
                readMethod(loader, value, "stopGenerationMethod", false),
                readMethod(loader, value, "repositoryAddMessageMethod", false),
                new HostAbi.Accessors(
                        readMethod(loader, value, "messageRoleMethod", false),
                        readMethod(loader, value, "messageContentMethod", false),
                        readMethod(loader, value, "messageIdMethod", false)),
                readField(loader, value, "viewModelStateField", false),
                readMethod(loader, value, "stateFlowValueMethod", false),
                readMethod(loader, value, "stateMessagesMethod", false),
                readMethod(loader, value, "stateSessionMethod", false),
                readMethod(loader, value, "sessionIdMethod", false));
        validate(abi);
        return abi;
    }

    private static void validate(HostAbi abi) throws Exception {
        ClassLoader loader = abi.providerClass.getClassLoader();
        Class<?> function1 = loadClass(loader, "kotlin.jvm.functions.Function1");
        requireMethod(abi.buildRequestMethod, abi.providerClass, String.class,
                abi.configClass, List.class, String.class, boolean.class);
        requireMethod(abi.scanSseDataMethod, abi.providerClass, boolean.class,
                String.class, function1);
        if (abi.streamMessagesMethod != null) {
            requireMethod(abi.streamMessagesMethod, abi.viewModelClass, void.class,
                    abi.configClass, List.class, function1);
        }
        if (abi.loadSessionMethod != null) {
            requireMethod(abi.loadSessionMethod, abi.viewModelClass, void.class,
                    String.class, String.class, loadClass(loader, "android.content.Context"));
        }
        requireOptionalMethod(abi.sendMessageMethod, abi.viewModelClass, void.class);
        requireOptionalMethod(abi.retryResponseMethod, abi.viewModelClass, void.class);
        requireOptionalMethod(abi.stopGenerationMethod, abi.viewModelClass, void.class);
        if (abi.repositoryAddMessageMethod != null) {
            requireMethod(abi.repositoryAddMessageMethod, abi.repositoryClass, void.class,
                    String.class, abi.messageClass);
        }
        validateAccessors(abi);
        if (abi.minified && abi.stateMessagesMethod != null
                && abi.stateSessionMethod != null && abi.sessionIdMethod != null
                && abi.viewModelStateField != null && abi.stateFlowValueMethod != null) {
            validateState(abi);
        }
    }

    private static void validateAccessors(HostAbi abi) throws Exception {
        if (abi.accessors == null || !abi.hasRetryAccessors()) return;
        requireMethod(abi.accessors.messageRole, abi.messageClass, String.class);
        requireMethod(abi.accessors.messageContent, abi.messageClass, String.class);
        requireMethod(abi.accessors.messageId, abi.messageClass, String.class);
        abi.validateAccessorBindings();
    }

    private static void validateState(HostAbi abi) throws Exception {
        Class<?> stateClass = abi.stateMessagesMethod.getDeclaringClass();
        requireMethod(abi.stateMessagesMethod, stateClass,
                abi.stateMessagesMethod.getReturnType());
        if (!List.class.isAssignableFrom(abi.stateMessagesMethod.getReturnType())) {
            throw new NoSuchMethodException("cached state messages method is not List-compatible");
        }
        requireMethod(abi.stateSessionMethod, stateClass,
                abi.stateSessionMethod.getReturnType());
        requireMethod(abi.sessionIdMethod, abi.stateSessionMethod.getReturnType(), String.class);
        if (!abi.viewModelStateField.getDeclaringClass().isAssignableFrom(abi.viewModelClass)) {
            throw new NoSuchFieldException("cached state field owner is incompatible");
        }
        requireMethod(abi.stateFlowValueMethod, abi.viewModelStateField.getType(),
                abi.stateFlowValueMethod.getReturnType());
    }

    private static void requireOptionalMethod(Method method, Class<?> expectedOwner,
            Class<?> returnType, Class<?>... params) throws NoSuchMethodException {
        if (method != null) requireMethod(method, expectedOwner, returnType, params);
    }

    private static void requireMethod(Method method, Class<?> expectedOwner, Class<?> returnType,
            Class<?>... params) throws NoSuchMethodException {
        if (method == null || expectedOwner == null
                || !method.getDeclaringClass().isAssignableFrom(expectedOwner)
                || method.getReturnType() != returnType) {
            throw new NoSuchMethodException("cached method owner/return type is incompatible");
        }
        Class<?>[] actual = method.getParameterTypes();
        if (actual.length != params.length) {
            throw new NoSuchMethodException("cached method parameter count is incompatible");
        }
        for (int index = 0; index < actual.length; index++) {
            if (actual[index] != params[index]) {
                throw new NoSuchMethodException("cached method parameter type is incompatible");
            }
        }
    }

    private static void putClass(JSONObject target, String key, Class<?> type) throws Exception {
        target.put(key, type == null ? JSONObject.NULL : type.getName());
    }

    private static void putMethod(JSONObject target, String key, Method method) throws Exception {
        if (method == null) {
            target.put(key, JSONObject.NULL);
            return;
        }
        JSONObject value = new JSONObject();
        value.put("owner", method.getDeclaringClass().getName());
        value.put("name", method.getName());
        value.put("returnType", method.getReturnType().getName());
        JSONArray params = new JSONArray();
        for (Class<?> type : method.getParameterTypes()) params.put(type.getName());
        value.put("params", params);
        target.put(key, value);
    }

    private static void putField(JSONObject target, String key, Field field) throws Exception {
        if (field == null) {
            target.put(key, JSONObject.NULL);
            return;
        }
        JSONObject value = new JSONObject();
        value.put("owner", field.getDeclaringClass().getName());
        value.put("name", field.getName());
        value.put("type", field.getType().getName());
        target.put(key, value);
    }

    private static Method readMethod(ClassLoader loader, JSONObject root, String key,
            boolean required) throws Exception {
        JSONObject value = root.optJSONObject(key);
        if (value == null) {
            if (required) throw new NoSuchMethodException("cached method missing: " + key);
            return null;
        }
        Class<?> owner = loadClass(loader, value.getString("owner"));
        JSONArray params = value.getJSONArray("params");
        Class<?>[] types = new Class<?>[params.length()];
        for (int index = 0; index < types.length; index++) {
            types[index] = loadClass(loader, params.getString(index));
        }
        Method method = owner.getDeclaredMethod(value.getString("name"), types);
        if (method.getReturnType() != loadClass(loader, value.getString("returnType"))) {
            throw new NoSuchMethodException("cached return type changed: " + key);
        }
        method.setAccessible(true);
        return method;
    }

    private static Field readField(ClassLoader loader, JSONObject root, String key,
            boolean required) throws Exception {
        JSONObject value = root.optJSONObject(key);
        if (value == null) {
            if (required) throw new NoSuchFieldException("cached field missing: " + key);
            return null;
        }
        Class<?> owner = loadClass(loader, value.getString("owner"));
        Field field = owner.getDeclaredField(value.getString("name"));
        if (field.getType() != loadClass(loader, value.getString("type"))) {
            throw new NoSuchFieldException("cached field type changed: " + key);
        }
        field.setAccessible(true);
        return field;
    }

    private static Class<?> readClass(ClassLoader loader, JSONObject root, String key,
            boolean required) throws Exception {
        Object value = root.opt(key);
        if (value == null || JSONObject.NULL.equals(value)) {
            if (required) throw new ClassNotFoundException("cached class missing: " + key);
            return null;
        }
        return loadClass(loader, String.valueOf(value));
    }

    private static Class<?> loadClass(ClassLoader loader, String name) throws Exception {
        if ("boolean".equals(name)) return boolean.class;
        if ("byte".equals(name)) return byte.class;
        if ("char".equals(name)) return char.class;
        if ("double".equals(name)) return double.class;
        if ("float".equals(name)) return float.class;
        if ("int".equals(name)) return int.class;
        if ("long".equals(name)) return long.class;
        if ("short".equals(name)) return short.class;
        if ("void".equals(name)) return void.class;
        return Class.forName(name, false, loader);
    }
}
