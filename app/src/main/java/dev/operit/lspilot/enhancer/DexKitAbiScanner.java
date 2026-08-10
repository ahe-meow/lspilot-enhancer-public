package dev.operit.lspilot.enhancer;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.UsingFieldData;

import java.io.File;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        Map<String, MethodHintBuilder> methodHints = new LinkedHashMap<>();
        AccessorHintBuilder accessorHints = new AccessorHintBuilder();
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
                collectViewModelMethodHints(bridge, methodHints);
                collectAccessorHints(bridge, accessorHints);
            }

            Map<String, DexAbiScanner.ViewModelMethodNames> resolvedHints = resolveMethodHints(methodHints);
            if (!accessorHints.hasRequired()) {
                throw new NoSuchMethodException("DexKit accessor hints incomplete; bound="
                        + accessorHints.boundCount());
            }
            DexAbiScanner.AccessorNames resolvedAccessors = accessorHints.toNames();
            DebugLogger.w("DexKit ABI query produced candidateClasses=" + candidates.size()
                    + " configClasses=" + configClasses.size()
                    + " viewModelMethodHints=" + resolvedHints.size()
                    + " accessorHints=" + accessorHints.boundCount());
            HostAbi abi = DexAbiScanner.resolveCandidates(loader, candidates, resolvedHints,
                    resolvedAccessors);
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

    private static void collectViewModelMethodHints(DexKitBridge bridge,
            Map<String, MethodHintBuilder> result) {
        addMethodHints(bridge, result, "send", MethodMatcher.create()
                .returnType("void").paramTypes().usingEqStrings("user"));
        addMethodHints(bridge, result, "retry", MethodMatcher.create()
                .returnType("void").paramTypes().usingEqStrings("assistant", "user"));
        addMethodHints(bridge, result, "stop", MethodMatcher.create()
                .returnType("void").paramTypes().usingEqStrings("assistant", "tool"));
    }

    private static void addMethodHints(DexKitBridge bridge, Map<String, MethodHintBuilder> result,
            String role, MethodMatcher matcher) {
        for (MethodData method : findMethods(bridge, matcher)) {
            if (!isAppClass(method.getClassName()) || method.getParamCount() != 0
                    || Modifier.isStatic(method.getModifiers())) continue;
            List<String> strings = method.getUsingStrings();
            if ("send".equals(role) && strings.contains("assistant")) continue;
            MethodHintBuilder builder = result.get(method.getClassName());
            if (builder == null) {
                builder = new MethodHintBuilder();
                result.put(method.getClassName(), builder);
            }
            builder.add(role, method.getMethodName());
        }
    }

    private static Map<String, DexAbiScanner.ViewModelMethodNames> resolveMethodHints(
            Map<String, MethodHintBuilder> builders) {
        Map<String, DexAbiScanner.ViewModelMethodNames> result = new LinkedHashMap<>();
        for (Map.Entry<String, MethodHintBuilder> entry : builders.entrySet()) {
            MethodHintBuilder builder = entry.getValue();
            if (!builder.isUnique(builder.send)) continue;
            result.put(entry.getKey(), new DexAbiScanner.ViewModelMethodNames(
                    builder.send, builder.unique(builder.retry), builder.unique(builder.stop)));
        }
        return result;
    }

    private static final class AccessorHintBuilder {
        String configProviderId;
        String configModelName;
        String configApiKey;
        String configFullApiUrl;
        String messageId;
        String messageRole;
        String messageContent;
        String messageToolCalls;
        String messageToolCallId;

        void addConfig(String providerId, String modelName, String apiKey, String fullApiUrl) {
            configProviderId = mergeUnique(configProviderId, providerId);
            configModelName = mergeUnique(configModelName, modelName);
            configApiKey = mergeUnique(configApiKey, apiKey);
            configFullApiUrl = mergeUnique(configFullApiUrl, fullApiUrl);
        }

        void addMessage(String id, String role, String content, String toolCalls, String toolCallId) {
            messageId = mergeUnique(messageId, id);
            messageRole = mergeUnique(messageRole, role);
            messageContent = mergeUnique(messageContent, content);
            messageToolCalls = mergeUnique(messageToolCalls, toolCalls);
            messageToolCallId = mergeUnique(messageToolCallId, toolCallId);
        }

        DexAbiScanner.AccessorNames toNames() {
            return new DexAbiScanner.AccessorNames(unique(configProviderId), unique(configModelName),
                    unique(configApiKey), unique(configFullApiUrl), unique(messageId),
                    unique(messageRole), unique(messageContent), unique(messageToolCalls),
                    unique(messageToolCallId));
        }

        int boundCount() {
            int count = 0;
            if (unique(configProviderId) != null) count++;
            if (unique(configModelName) != null) count++;
            if (unique(configApiKey) != null) count++;
            if (unique(configFullApiUrl) != null) count++;
            if (unique(messageId) != null) count++;
            if (unique(messageRole) != null) count++;
            if (unique(messageContent) != null) count++;
            if (unique(messageToolCalls) != null) count++;
            if (unique(messageToolCallId) != null) count++;
            return count;
        }

        boolean hasRequired() {
            return unique(configProviderId) != null
                    && unique(configModelName) != null
                    && unique(configApiKey) != null
                    && unique(configFullApiUrl) != null
                    && unique(messageId) != null
                    && unique(messageRole) != null
                    && unique(messageContent) != null;
        }
    }

    private static final class MethodHintBuilder {
        String send;
        String retry;
        String stop;

        void add(String role, String methodName) {
            if ("send".equals(role)) send = merge(send, methodName);
            else if ("retry".equals(role)) retry = merge(retry, methodName);
            else stop = merge(stop, methodName);
        }

        boolean isUnique(String value) {
            return value != null && !value.isEmpty();
        }

        String unique(String value) {
            return isUnique(value) ? value : null;
        }

        private String merge(String current, String next) {
            if (current == null) return next;
            if (current.isEmpty() || current.equals(next)) return current;
            return "";
        }
    }

    private static void collectAccessorHints(DexKitBridge bridge, AccessorHintBuilder result) {
        for (MethodData method : findMethods(bridge, MethodMatcher.create()
                .returnType("java.lang.String")
                .paramTypes()
                .usingStrings("AiProviderConfig(id=", ", providerId=", ", apiKey=",
                        ", baseUrl=", ", apiPath=", ", modelNames="))) {
            List<FieldData> fields = usedFields(method);
            if (fields.size() < 7) continue;
            String owner = method.getClassName();
            result.addConfig(
                    getterName(fields.get(2), owner, "java.lang.String"),
                    getterName(fields.get(6), owner, "java.lang.String"),
                    getterName(fields.get(3), owner, "java.lang.String"),
                    sharedGetterName(fields.get(4), fields.get(5), owner, "java.lang.String"));
        }
        for (MethodData method : findMethods(bridge, MethodMatcher.create()
                .returnType("java.lang.String")
                .paramTypes()
                .usingStrings("AiChatMessage(id=", ", role=", ", content=",
                        ", toolCalls=", ", toolCallId="))) {
            List<FieldData> fields = usedFields(method);
            if (fields.size() < 14) continue;
            String owner = method.getClassName();
            result.addMessage(
                    getterName(fields.get(0), owner, "java.lang.String"),
                    getterName(fields.get(1), owner, "java.lang.String"),
                    getterName(fields.get(2), owner, "java.lang.String"),
                    getterName(fields.get(11), owner, "java.util.List"),
                    getterName(fields.get(13), owner, "java.lang.String"));
        }
    }

    private static List<FieldData> usedFields(MethodData method) {
        List<FieldData> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (UsingFieldData using : method.getUsingFields()) {
            FieldData field = using.getField();
            if (field == null || !method.getClassName().equals(field.getDeclaredClassName())) continue;
            String key = field.getDeclaredClassName() + '#' + field.getFieldName();
            if (seen.add(key)) result.add(field);
        }
        return result;
    }

    private static String sharedGetterName(FieldData first, FieldData second, String owner,
            String returnType) {
        Set<String> firstNames = getterNames(first, owner, returnType);
        String result = null;
        for (String name : getterNames(second, owner, returnType)) {
            if (firstNames.contains(name)) result = mergeUnique(result, name);
        }
        return unique(result);
    }

    private static String getterName(FieldData field, String owner, String returnType) {
        String result = null;
        for (String name : getterNames(field, owner, returnType)) {
            result = mergeUnique(result, name);
        }
        return unique(result);
    }

    private static Set<String> getterNames(FieldData field, String owner, String returnType) {
        Set<String> result = new LinkedHashSet<>();
        if (field == null) return result;
        for (MethodData reader : field.getReaders()) {
            if (!owner.equals(reader.getClassName())
                    || reader.getParamCount() != 0
                    || !returnType.equals(reader.getReturnTypeName())
                    || "toString".equals(reader.getMethodName())
                    || Modifier.isStatic(reader.getModifiers())) {
                continue;
            }
            result.add(reader.getMethodName());
        }
        return result;
    }

    private static String mergeUnique(String current, String next) {
        if (next == null || next.isEmpty()) return current;
        if (current == null) return next;
        return current.equals(next) ? current : "";
    }

    private static String unique(String value) {
        return value == null || value.isEmpty() ? null : value;
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
        check(abi.buildRequestMethod != null, "build request method missing");
        check(abi.streamMessagesMethod != null, "stream method missing");
        check(abi.sendMessageMethod != null, "send message method missing");
        check(abi.retryResponseMethod != null, "retry response method missing");
        check(abi.stopGenerationMethod != null, "stop generation method missing");
        check(abi.repositoryAddMessageMethod != null, "repository add-message method missing");
        check(abi.hasCompressionAccessors(), "compression accessors missing");
        abi.validateAccessorBindings();
        System.out.println("DexKit ABI resolved provider=" + abi.providerClass.getName()
                + " viewModel=" + abi.viewModelClass.getName()
                + " send=" + abi.sendMessageMethod.getName()
                + " retry=" + methodName(abi.retryResponseMethod)
                + " stop=" + methodName(abi.stopGenerationMethod)
                + " accessors=" + accessorNames(abi));
    }

    private static String accessorNames(HostAbi abi) {
        return "providerId=" + methodName(abi.accessors.configProviderId)
                + ",model=" + methodName(abi.accessors.configModelName)
                + ",apiKey=" + methodName(abi.accessors.configApiKey)
                + ",url=" + methodName(abi.accessors.configFullApiUrl)
                + ",messageId=" + methodName(abi.accessors.messageId)
                + ",role=" + methodName(abi.accessors.messageRole)
                + ",content=" + methodName(abi.accessors.messageContent);
    }

    private static String methodName(java.lang.reflect.Method method) {
        return method == null ? "missing" : method.getName();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}