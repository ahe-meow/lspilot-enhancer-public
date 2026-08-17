package com.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;

/** Serializes the verified request/SSE ABI so normal starts do not need a DEX scan. */
final class HostAbiDescriptor {
    private HostAbiDescriptor() {}

    static JSONObject encode(HostAbi abi) throws Exception {
        JSONObject result = new JSONObject();
        result.put("minified", abi.minified);
        putClass(result, "providerClass", abi.providerClass);
        putClass(result, "configClass", abi.configClass);
        putMethod(result, "buildRequestMethod", abi.buildRequestMethod);
        putMethod(result, "scanSseDataMethod", abi.scanSseDataMethod);
        return result;
    }

    static HostAbi decode(ClassLoader loader, JSONObject value) throws Exception {
        HostAbi abi = new HostAbi(
                readClass(loader, value, "providerClass"),
                readClass(loader, value, "configClass"),
                value.getBoolean("minified"),
                readMethod(loader, value, "buildRequestMethod"),
                readMethod(loader, value, "scanSseDataMethod"));
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
        target.put(key, type.getName());
    }

    private static void putMethod(JSONObject target, String key, Method method) throws Exception {
        JSONObject value = new JSONObject();
        value.put("owner", method.getDeclaringClass().getName());
        value.put("name", method.getName());
        value.put("returnType", method.getReturnType().getName());
        JSONArray params = new JSONArray();
        for (Class<?> type : method.getParameterTypes()) params.put(type.getName());
        value.put("params", params);
        target.put(key, value);
    }

    private static Method readMethod(ClassLoader loader, JSONObject root, String key)
            throws Exception {
        JSONObject value = root.getJSONObject(key);
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

    private static Class<?> readClass(ClassLoader loader, JSONObject root, String key)
            throws Exception {
        return loadClass(loader, root.getString(key));
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
        return loader.loadClass(name);
    }
}
