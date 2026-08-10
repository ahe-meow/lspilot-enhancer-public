package dev.operit.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Serializes the final verified ABI so normal starts do not need a DEX scan. */
final class HostAbiDescriptor {
    private HostAbiDescriptor() {}

    static JSONObject encode(HostAbi abi) throws Exception {
        JSONObject result = new JSONObject();
        result.put("minified", abi.minified);
        result.put("providerClass", abi.providerClass.getName());
        result.put("configClass", abi.configClass.getName());
        result.put("viewModelClass", abi.viewModelClass.getName());
        result.put("messageClass", abi.messageClass.getName());
        result.put("repositoryClass", abi.repositoryClass.getName());
        putClass(result, "aiChatRouteClass", abi.aiChatRouteClass);
        putMethod(result, "buildRequestMethod", abi.buildRequestMethod);
        putMethod(result, "scanSseDataMethod", abi.scanSseDataMethod);
        putMethod(result, "streamMessagesMethod", abi.streamMessagesMethod);
        putMethod(result, "loadSessionMethod", abi.loadSessionMethod);
        putMethod(result, "sendMessageMethod", abi.sendMessageMethod);
        putMethod(result, "repositoryAddMessageMethod", abi.repositoryAddMessageMethod);
        putField(result, "viewModelStateField", abi.viewModelStateField);
        putMethod(result, "stateFlowValueMethod", abi.stateFlowValueMethod);
        putMethod(result, "stateMessagesMethod", abi.stateMessagesMethod);
        putMethod(result, "stateConfigMethod", abi.stateConfigMethod);
        putMethod(result, "stateSelectedModelMethod", abi.stateSelectedModelMethod);
        putMethod(result, "stateLoadingMethod", abi.stateLoadingMethod);
        putMethod(result, "stateSessionMethod", abi.stateSessionMethod);
        putMethod(result, "sessionIdMethod", abi.sessionIdMethod);
        return result;
    }

    static HostAbi decode(ClassLoader loader, JSONObject value) throws Exception {
        Class<?> providerClass = loadClass(loader, value.getString("providerClass"));
        Class<?> configClass = loadClass(loader, value.getString("configClass"));
        Class<?> viewModelClass = loadClass(loader, value.getString("viewModelClass"));
        Class<?> messageClass = loadClass(loader, value.getString("messageClass"));
        Class<?> repositoryClass = loadClass(loader, value.getString("repositoryClass"));
        Class<?> aiChatRouteClass = optionalClass(loader, value.optString("aiChatRouteClass", null));
        boolean minified = value.getBoolean("minified");

        HostAbi abi = new HostAbi(
                providerClass,
                configClass,
                viewModelClass,
                messageClass,
                repositoryClass,
                aiChatRouteClass,
                minified,
                readMethod(loader, value, "buildRequestMethod", true),
                readMethod(loader, value, "scanSseDataMethod", true),
                readMethod(loader, value, "streamMessagesMethod", true),
                readMethod(loader, value, "loadSessionMethod", true),
                readMethod(loader, value, "sendMessageMethod", true),
                readMethod(loader, value, "repositoryAddMessageMethod", true),
                findMessageConstructor(messageClass),
                readField(loader, value, "viewModelStateField", minified),
                readMethod(loader, value, "stateFlowValueMethod", minified),
                readMethod(loader, value, "stateMessagesMethod", minified),
                readMethod(loader, value, "stateConfigMethod", minified),
                readMethod(loader, value, "stateSelectedModelMethod", minified),
                readMethod(loader, value, "stateLoadingMethod", minified),
                readMethod(loader, value, "stateSessionMethod", minified),
                readMethod(loader, value, "sessionIdMethod", minified));
        validate(abi);
        return abi;
    }

    private static void validate(HostAbi abi) throws Exception {
        requireMethod(abi.buildRequestMethod, abi.providerClass, String.class,
                abi.configClass, List.class, String.class, boolean.class);
        requireMethod(abi.scanSseDataMethod, abi.providerClass, boolean.class,
                String.class, loadClass(abi.providerClass.getClassLoader(),
                        "kotlin.jvm.functions.Function1"));
        requireMethod(abi.streamMessagesMethod, abi.viewModelClass, void.class,
                abi.configClass, List.class, loadClass(abi.viewModelClass.getClassLoader(),
                        "kotlin.jvm.functions.Function1"));
        requireMethod(abi.repositoryAddMessageMethod, abi.repositoryClass, void.class,
                String.class, abi.messageClass);
        requireMethod(abi.sendMessageMethod, abi.viewModelClass, void.class);
        if (!abi.viewModelClass.isAssignableFrom(abi.loadSessionMethod.getDeclaringClass())
                && !abi.loadSessionMethod.getDeclaringClass().isAssignableFrom(abi.viewModelClass)) {
            throw new NoSuchMethodException("cached load-session owner is incompatible");
        }
        if (!abi.hasCompressionAccessors()) {
            throw new NoSuchMethodException("cached config/message accessors are incompatible");
        }
        if (abi.minified) validateState(abi);
    }

    private static void validateState(HostAbi abi) throws Exception {
        Class<?> stateClass = abi.stateMessagesMethod.getDeclaringClass();
        requireMethod(abi.stateMessagesMethod, stateClass, abi.stateMessagesMethod.getReturnType());
        if (!List.class.isAssignableFrom(abi.stateMessagesMethod.getReturnType())) {
            throw new NoSuchMethodException("cached state messages method is not List-compatible");
        }
        requireMethod(abi.stateConfigMethod, stateClass, abi.configClass);
        requireMethod(abi.stateSelectedModelMethod, stateClass, String.class);
        Class<?> loadingType = abi.stateLoadingMethod.getReturnType();
        if (abi.stateLoadingMethod.getParameterTypes().length != 0
                || (loadingType != boolean.class && loadingType != Boolean.class)) {
            throw new NoSuchMethodException("cached state loading method is incompatible");
        }
        requireMethod(abi.stateSessionMethod, stateClass, abi.stateSessionMethod.getReturnType());
        requireMethod(abi.sessionIdMethod, abi.stateSessionMethod.getReturnType(), String.class);
        if (!abi.viewModelStateField.getDeclaringClass().isAssignableFrom(abi.viewModelClass)) {
            throw new NoSuchFieldException("cached state field owner is incompatible");
        }
        requireMethod(abi.stateFlowValueMethod, abi.viewModelStateField.getType(),
                abi.stateFlowValueMethod.getReturnType());
    }

    private static void requireMethod(Method method, Class<?> expectedOwner, Class<?> returnType,
            Class<?>... params) throws NoSuchMethodException {
        if (method == null
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

    private static Class<?> optionalClass(ClassLoader loader, String name) throws Exception {
        return name == null || name.isEmpty() || "null".equals(name) ? null : loadClass(loader, name);
    }

    private static Class<?> loadClass(ClassLoader loader, String name) throws ClassNotFoundException {
        if ("boolean".equals(name)) return boolean.class;
        if ("byte".equals(name)) return byte.class;
        if ("char".equals(name)) return char.class;
        if ("short".equals(name)) return short.class;
        if ("int".equals(name)) return int.class;
        if ("long".equals(name)) return long.class;
        if ("float".equals(name)) return float.class;
        if ("double".equals(name)) return double.class;
        if ("void".equals(name)) return void.class;
        return Class.forName(name, false, loader);
    }
}