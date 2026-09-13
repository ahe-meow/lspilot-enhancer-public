package com.lspilot.enhancer;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Package-local, fail-closed structural ABI discovery.
 *
 * <p>DexKit data is converted to reflection handles only after the query
 * metadata has passed the same return/static/parameter checks used by the
 * final reflection filter. The returned descriptor contains no DexKit data,
 * bridge, loader, or host object.</p>
 */
final class DexKitAbiScanner {
    private static volatile boolean nativeLoaded;
    private static volatile boolean nativeLoadAttempted;
    private static final Object CACHE_LOCK = new Object();
    private static CacheEntry cached;

    private static final String TYPE_STRING = "java.lang.String";
    private static final String TYPE_LIST = "java.util.List";

    /*
     * Historical v12 source-contract evidence only. These declarations are
     * comments, not production lookup inputs; menu discovery below is
     * entirely structural.
     * private static final String TYPE_PROVIDER = "vb";
     * private static final String TYPE_MENU_RESOLVER = "n0b";
     * private static final String TYPE_MENU_COMPOSER = "id2";
     * private static final String TYPE_MENU_OWNER = "y71";
     * private static final String TYPE_MENU_RESOURCES = "u29";
     * private static final String MENU_RESOLVER_METHOD = "a";
     * private static final String MENU_CALLER_METHOD = "Z";
     * private static final String MENU_BUTTON_METHOD = "A";
     * private static final String MENU_BUTTON_RESOURCE_FIELD = "S1";
     */

    private static final String TYPE_KOTLIN_UNIT = "kotlin.Unit";
    private static final String TYPE_SHARED_PREFERENCES =
            "android.content.SharedPreferences";

    private static final String REASONING_KEY = "reasoning_effort";
    private static final String SETTINGS_FILE = "settings";
    private static final String GET_SHARED_PREFERENCES_DESCRIPTOR =
            "Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)"
                    + "Landroid/content/SharedPreferences;";
    private static final String SHARED_PREFERENCES_GET_STRING_DESCRIPTOR =
            "Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)"
                    + "Ljava/lang/String;";
    private static final String[] REASONING_ENUM_NAMES = new String[]{
            "OFF", "AUTO", "LOW", "MEDIUM", "HIGH", "MAX"};

    private DexKitAbiScanner() {
    }

    private static boolean ensureNativeLoaded() {
        if (nativeLoaded) {
            return true;
        }
        synchronized (DexKitAbiScanner.class) {
            if (nativeLoaded) {
                return true;
            }
            if (nativeLoadAttempted) {
                return false;
            }
            nativeLoadAttempted = true;
            try {
                System.loadLibrary("dexkit");
                nativeLoaded = true;
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    static HostAbi resolve(ClassLoader loader, String apkPath) {
        return resolveDetailed(loader, apkPath).abi;
    }

    static ScanResult resolveDetailed(ClassLoader loader, String apkPath) {
        List<String> sourcePaths = apkPath == null
                ? null : Collections.singletonList(apkPath);
        return resolveDetailed(loader, sourcePaths);
    }

    static ScanResult resolveDetailed(ClassLoader loader, List<String> sourcePaths) {
        String fingerprint = HostApkFingerprint.compute(sourcePaths);
        if (loader == null || fingerprint == null || !ensureNativeLoaded()) {
            return emptyResult(fingerprint, false);
        }
        synchronized (CACHE_LOCK) {
            if (cached != null && cacheKeyMatches(
                    cached.loader, cached.fingerprint, loader, fingerprint)) {
                return cached.result.withCacheHit(true);
            }
            ScanResult result = scanFresh(loader, fingerprint);
            cached = new CacheEntry(loader, fingerprint, result);
            return result;
        }
    }

    static void clearCache() {
        synchronized (CACHE_LOCK) {
            cached = null;
        }
    }

    static boolean cacheKeyMatches(
            ClassLoader cachedLoader,
            String cachedFingerprint,
            ClassLoader loader,
            String fingerprint) {
        return cachedLoader != null
                && loader != null
                && cachedLoader == loader
                && cachedFingerprint != null
                && cachedFingerprint.equals(fingerprint);
    }

    private static ScanResult scanFresh(ClassLoader loader, String fingerprint) {
        DexKitBridge bridge = null;
        try {
            // DexKitBridge 2.2 exposes native-backed create methods without
            // loading libdexkit from its class initializer.
            bridge = DexKitBridge.create(loader, true);
            if (bridge == null || !bridge.isValid()) {
                return emptyResult(fingerprint, false);
            }
            return resolveWithBridge(bridge, loader, fingerprint);
        } catch (Throwable ignored) {
            return emptyResult(fingerprint, false);
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                    // Bridge cleanup must never become a host-facing failure.
                }
            }
        }
    }

    static Object chooseUnique(String capability, List<?> candidates) {
        if (candidates == null || candidates.size() != 1) {
            return null;
        }
        return candidates.get(0);
    }

    static List<Method> filterMethods(
            List<Method> methods,
            Class<?> returnType,
            boolean staticExpected,
            Class<?>... parameterTypes) {
        List<Method> filtered = new ArrayList<Method>();
        if (methods == null || returnType == null || parameterTypes == null) {
            return filtered;
        }
        for (Method method : methods) {
            if (method == null || method.getReturnType() != returnType) {
                continue;
            }
            if (Modifier.isStatic(method.getModifiers()) != staticExpected) {
                continue;
            }
            Class<?>[] actualParameters = method.getParameterTypes();
            if (actualParameters.length != parameterTypes.length) {
                continue;
            }
            boolean exact = true;
            for (int i = 0; i < actualParameters.length; i++) {
                if (parameterTypes[i] == null || actualParameters[i] != parameterTypes[i]) {
                    exact = false;
                    break;
                }
            }
            if (exact) {
                filtered.add(method);
            }
        }
        return filtered;
    }

    /** Pure reflection contract for the current six-value reasoning enum. */
    static boolean hasExactReasoningEnumContract(Class<?> enumClass) {
        if (enumClass == null || !enumClass.isEnum()) {
            return false;
        }
        try {
            Object[] constants = enumClass.getEnumConstants();
            if (constants == null || constants.length != REASONING_ENUM_NAMES.length) {
                return false;
            }
            for (Object constant : constants) {
                if (!(constant instanceof Enum<?>)) {
                    return false;
                }
                String actualName = ((Enum<?>) constant).name();
                if (!containsReasoningEnumName(actualName)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Pure reflection check for a candidate enum resource-ID getter. */
    static boolean hasExactReasoningResourceGetter(
            Class<?> enumClass, Method getter) {
        if (!hasExactReasoningEnumContract(enumClass)
                || getter == null
                || !enumClass.equals(getter.getDeclaringClass())
                || Modifier.isStatic(getter.getModifiers())
                || getter.getReturnType() != int.class
                || getter.getParameterTypes().length != 0) {
            return false;
        }
        try {
            Object[] constants = enumClass.getEnumConstants();
            Set<Integer> resourceIds = new HashSet<Integer>();
            getter.setAccessible(true);
            for (Object constant : constants) {
                Object value = getter.invoke(constant);
                if (!(value instanceof Integer)) {
                    return false;
                }
                int resourceId = ((Integer) value).intValue();
                if ((resourceId & 0xff000000) == 0
                        || (resourceId & 0x00ff0000) == 0
                        || !resourceIds.add(Integer.valueOf(resourceId))) {
                    return false;
                }
            }
            return resourceIds.size() == REASONING_ENUM_NAMES.length;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static Method selectUniqueReasoningResourceGetter(
            Class<?> enumClass, List<Method> methods) {
        List<Method> structural = new ArrayList<Method>();
        if (methods != null) {
            for (Method method : methods) {
                if (hasExactReasoningResourceGetter(enumClass, method)) {
                    structural.add(method);
                }
            }
        }
        Object selected = chooseUnique("reasoningResourceGetter", structural);
        return selected instanceof Method ? (Method) selected : null;
    }

    private static boolean containsReasoningEnumName(String name) {
        for (String expectedName : REASONING_ENUM_NAMES) {
            if (expectedName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasReadInstanceIntField(
            ClassData enumData, MethodData methodData) {
        if (enumData == null || methodData == null) {
            return false;
        }
        try {
            List<UsingFieldData> usingFields = methodData.getUsingFields();
            if (usingFields == null) {
                return false;
            }
            for (UsingFieldData usingField : usingFields) {
                if (usingField == null || usingField.getUsingType() == null
                        || !usingField.getUsingType().isRead()) {
                    continue;
                }
                FieldData field = usingField.getField();
                ClassData declaringClass = field == null ? null : field.getDeclaredClass();
                if (field != null
                        && !Modifier.isStatic(field.getModifiers())
                        && "int".equals(field.getTypeName())
                        && declaringClass != null
                        && enumData.getName() != null
                        && enumData.getName().equals(declaringClass.getName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static List<Method> resourceGetterCandidates(
            ClassData enumData, Class<?> enumClass, ClassLoader loader) {
        List<Method> candidates = new ArrayList<Method>();
        if (enumData == null || enumClass == null || loader == null) {
            return candidates;
        }
        try {
            MethodDataList methods = enumData.getMethods();
            if (methods == null) {
                return candidates;
            }
            for (MethodData methodData : methods) {
                if (methodData == null
                        || methodData.getParamCount() != 0
                        || !"int".equals(methodData.getReturnTypeName())
                        || Modifier.isStatic(methodData.getModifiers())
                        || !hasReadInstanceIntField(enumData, methodData)) {
                    continue;
                }
                Method method = reflectMethod(methodData, loader);
                if (hasExactReasoningResourceGetter(enumClass, method)) {
                    candidates.add(method);
                }
            }
        } catch (Throwable ignored) {
            return new ArrayList<Method>();
        }
        return candidates;
    }

    private static CapabilityResolution<EnumPrerequisite> resolveReasoningEnum(
            DexKitBridge bridge, ClassLoader loader) {
        ClassDataList candidates = queryClasses(
                bridge, "OFF", "AUTO", "LOW", "MEDIUM", "HIGH", "MAX");
        List<EnumPrerequisite> valid = new ArrayList<EnumPrerequisite>();
        int candidateCount = sizeOf(candidates);
        if (loader == null) {
            return new CapabilityResolution<EnumPrerequisite>(null, candidateCount);
        }
        for (ClassData candidate : candidates) {
            try {
                Class<?> enumClass = candidate == null ? null : candidate.getInstance(loader);
                if (!hasExactReasoningEnumContract(enumClass)) {
                    continue;
                }
                List<Method> getters = resourceGetterCandidates(candidate, enumClass, loader);
                Method getter = selectUniqueReasoningResourceGetter(enumClass, getters);
                if (getter != null) {
                    valid.add(new EnumPrerequisite(
                            enumClass, getter, methodDescriptor(getter)));
                } else if (getters.size() > 1) {
                    candidateCount = Math.max(candidateCount, getters.size());
                }
            } catch (Throwable ignored) {
                // An unreflectable candidate is not a supported prerequisite.
            }
        }
        if (valid.size() != 1) {
            candidateCount = Math.max(candidateCount, valid.size());
            return new CapabilityResolution<EnumPrerequisite>(null, candidateCount);
        }
        return new CapabilityResolution<EnumPrerequisite>(valid.get(0), 1);
    }

    private static ScanResult resolveWithBridge(
            DexKitBridge bridge, ClassLoader loader, String fingerprint) {
        CapabilityResolution<EnumPrerequisite> enumPrerequisite =
                resolveReasoningEnum(bridge, loader);
        CapabilityResolution<HostAbi.ReasoningCapability> reasoning =
                resolveReasoning(bridge, loader, enumPrerequisite);
        CapabilityResolution<HostAbi.MenuCapability> menu =
                resolveMenu(bridge, loader, enumPrerequisite.capability);
        // Compatibility source marker: resolveMenu(loader, enumPrerequisite.capability)
        CapabilityResolution<HostAbi.RequestCapability> generic =
                resolveGenericRequest(bridge, loader, enumPrerequisite.capability);
        CapabilityResolution<HostAbi.RequestCapability> thinking =
                resolveThinkingRequest(bridge, loader, enumPrerequisite.capability);

        HostAbi abi = new HostAbi(
                reasoning.capability,
                menu.capability,
                generic.capability,
                thinking.capability);
        return new ScanResult(
                abi,
                reasoning.count,
                menu.count,
                generic.count,
                thinking.count,
                fingerprint != null,
                fingerprint,
                false);
    }

    private static CapabilityResolution<HostAbi.ReasoningCapability> resolveReasoning(
            DexKitBridge bridge,
            ClassLoader loader,
            CapabilityResolution<EnumPrerequisite> enumPrerequisite) {
        if (enumPrerequisite == null || enumPrerequisite.capability == null) {
            return new CapabilityResolution<HostAbi.ReasoningCapability>(
                    null, enumPrerequisite == null ? 0 : enumPrerequisite.count);
        }
        try {
            ClassDataList ownerCandidates = queryClasses(
                    bridge, SETTINGS_FILE, REASONING_KEY);
            ClassData owner = uniqueClass(ownerCandidates);
            if (owner == null) {
                return new CapabilityResolution<HostAbi.ReasoningCapability>(
                        null, sizeOf(ownerCandidates));
            }

            List<Method> accessors = queryMethodsInClassWithInvoke(
                    bridge,
                    loader,
                    owner,
                    TYPE_SHARED_PREFERENCES,
                    true,
                    new String[0],
                    GET_SHARED_PREFERENCES_DESCRIPTOR,
                    SETTINGS_FILE);
            List<Method> getters = queryMethodsInClassWithInvoke(
                    bridge,
                    loader,
                    owner,
                    TYPE_STRING,
                    false,
                    new String[0],
                    SHARED_PREFERENCES_GET_STRING_DESCRIPTOR,
                    REASONING_KEY);
            Method accessor = (Method) chooseUnique("settingsAccessor", accessors);
            Method getter = (Method) chooseUnique("reasoningGetter", getters);
            int shapeCount = Math.max(accessors.size(), getters.size());
            if (accessor == null || getter == null
                    || !accessor.getDeclaringClass().equals(getter.getDeclaringClass())) {
                return new CapabilityResolution<HostAbi.ReasoningCapability>(null, shapeCount);
            }

            Constructor<?> repositoryConstructor = uniqueNoArgConstructor(
                    getter.getDeclaringClass());
            if (repositoryConstructor == null) {
                return new CapabilityResolution<HostAbi.ReasoningCapability>(null, 0);
            }
            HostAbi.ReasoningCapability capability = new HostAbi.ReasoningCapability(
                    getter,
                    repositoryConstructor,
                    enumPrerequisite.capability.enumClass);
            return new CapabilityResolution<HostAbi.ReasoningCapability>(capability, 1);
        } catch (Throwable ignored) {
            return new CapabilityResolution<HostAbi.ReasoningCapability>(null, 0);
        }
    }


    private static CapabilityResolution<HostAbi.MenuCapability> resolveMenu(
            DexKitBridge bridge, ClassLoader loader, EnumPrerequisite prerequisite) {
        if (bridge == null || loader == null || prerequisite == null
                || prerequisite.enumClass == null
                || prerequisite.resourceIdGetter == null) {
            return new CapabilityResolution<HostAbi.MenuCapability>(null, 0);
        }
        try {
            MethodMatcher resolverMatcher = new MethodMatcher()
                    .returnType(String.class)
                    .paramCount(3)
                    .addInvoke("Landroid/content/res/Resources;->getString(I)Ljava/lang/String;");
            List<MethodData> resolverData = queryMethodData(
                    bridge, new FindMethod().matcher(resolverMatcher));
            List<MenuResolverCandidate> resolverCandidates =
                    new ArrayList<MenuResolverCandidate>();
            for (MethodData data : resolverData) {
                try {
                    if (!matchesMenuResolverMetadata(data, loader)) {
                        continue;
                    }
                    Method method = reflectMethod(data, loader);
                    if (matchesMenuResolverReflection(method)) {
                        resolverCandidates.add(new MenuResolverCandidate(data, method));
                    }
                } catch (Throwable ignored) {
                    // An invalid resolver candidate must not suppress other candidates.
                }
            }

            Class<?> enumClass = prerequisite.enumClass;
            String resourceDescriptor = prerequisite.resourceIdDescriptor;
            Class<?> unitClass = loadHostClass(loader, TYPE_KOTLIN_UNIT);
            if (resourceDescriptor == null || unitClass == null) {
                return new CapabilityResolution<HostAbi.MenuCapability>(null, 0);
            }

            List<MenuResolverBundle> resolverBundles =
                    new ArrayList<MenuResolverBundle>();
            for (MenuResolverCandidate resolver : resolverCandidates) {
                try {
                    String resolverDescriptor = resolver.data.getDescriptor();
                    if (resolverDescriptor == null) {
                        continue;
                    }
                    MethodDataList callerData = resolver.data.getCallers();
                    if (callerData == null) {
                        continue;
                    }

                    List<Method> menuMethods = new ArrayList<Method>();
                    List<Method> buttonMethods = new ArrayList<Method>();
                    for (MethodData data : callerData) {
                        if (!matchesMenuCallerMetadata(
                                data, loader, enumClass, resolverDescriptor, resourceDescriptor)) {
                            continue;
                        }
                        Method method = reflectMethod(data, loader);
                        if (!matchesMenuCallerReflection(method, enumClass)) {
                            continue;
                        }
                        if (method.getReturnType() == unitClass) {
                            menuMethods.add(method);
                        } else if (method.getReturnType() == void.class) {
                            buttonMethods.add(method);
                        }
                    }

                    Method resourceId = prerequisite.resourceIdGetter;
                    resourceId.setAccessible(true);
                    Map<Integer, String> labels = new HashMap<Integer, String>();
                    Object[] constants = enumClass.getEnumConstants();
                    if (constants == null
                            || constants.length != ReasoningPolicy.SUPPORTED.length) {
                        continue;
                    }
                    for (Object constant : constants) {
                        if (!(constant instanceof Enum<?>)) {
                            labels.clear();
                            break;
                        }
                        Object id = resourceId.invoke(constant);
                        String display = ReasoningPolicy.fromHostEnumName(
                                ((Enum<?>) constant).name());
                        if (!(id instanceof Integer)
                                || labels.put((Integer) id, display) != null) {
                            labels.clear();
                            break;
                        }
                    }
                    if (labels.size() != ReasoningPolicy.SUPPORTED.length) {
                        continue;
                    }

                    resolverBundles.add(new MenuResolverBundle(
                            resolver.method, menuMethods, buttonMethods, labels, enumClass));
                } catch (Throwable ignored) {
                    // A candidate-specific failure must not discard other resolvers.
                }
            }

            MenuResolverSelection selection = selectUniqueCompleteMenuBundle(
                    resolverBundles, unitClass);
            if (selection.capability != null) {
                selection.capability.labelResolver.setAccessible(true);
                selection.capability.menuMethod.setAccessible(true);
                selection.capability.buttonMethod.setAccessible(true);
            }
            return new CapabilityResolution<HostAbi.MenuCapability>(
                    selection.capability, selection.completeCount);
        } catch (Throwable ignored) {
            return new CapabilityResolution<HostAbi.MenuCapability>(null, 0);
        }
    }

    private static boolean matchesMenuResolverMetadata(
            MethodData methodData, ClassLoader loader) {
        if (methodData == null || loader == null
                || methodData.getParamCount() != 3
                || !Modifier.isStatic(methodData.getModifiers())
                || !TYPE_STRING.equals(methodData.getReturnTypeName())) {
            return false;
        }
        try {
            List<String> parameterNames = methodData.getParamTypeNames();
            if (parameterNames == null || parameterNames.size() != 3) {
                return false;
            }
            Class<?> middle = loadType(loader, parameterNames.get(1));
            return int.class == loadType(loader, parameterNames.get(0))
                    && int.class == loadType(loader, parameterNames.get(2))
                    && middle != null && !middle.isPrimitive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean matchesMenuResolverReflection(Method method) {
        if (method == null || !Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != String.class) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length == 3
                && parameters[0] == int.class
                && parameters[2] == int.class
                && parameters[1] != null
                && !parameters[1].isPrimitive();
    }

    static boolean hasRequiredMenuCallerInvokes(
            boolean resolverInvoke, boolean resourceGetterInvoke) {
        return resolverInvoke && resourceGetterInvoke;
    }

    private static boolean matchesMenuCallerMetadata(
            MethodData methodData,
            ClassLoader loader,
            Class<?> enumClass,
            String resolverDescriptor,
            String resourceDescriptor) {
        return methodData != null
                && loader != null
                && enumClass != null
                && Modifier.isStatic(methodData.getModifiers())
                && hasExactlyOneEnumParameter(methodData, loader, enumClass)
                && hasRequiredMenuCallerInvokes(
                hasInvokeDescriptor(methodData, resolverDescriptor),
                hasInvokeDescriptor(methodData, resourceDescriptor));
    }

    private static boolean hasExactlyOneEnumParameter(
            MethodData methodData, ClassLoader loader, Class<?> enumClass) {
        if (methodData == null || loader == null || enumClass == null) {
            return false;
        }
        try {
            List<String> parameterNames = methodData.getParamTypeNames();
            if (parameterNames == null) {
                return false;
            }
            int matches = 0;
            for (String parameterName : parameterNames) {
                if (enumClass == loadType(loader, parameterName)) {
                    matches++;
                }
            }
            return matches == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean matchesMenuCallerReflection(
            Method method, Class<?> enumClass) {
        return method != null
                && Modifier.isStatic(method.getModifiers())
                && enumParameterIndex(method, enumClass) >= 0;
    }

    private static int enumParameterIndex(Method method, Class<?> enumClass) {
        if (method == null || enumClass == null) {
            return -1;
        }
        Class<?>[] parameters = method.getParameterTypes();
        int found = -1;
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] == enumClass) {
                if (found >= 0) {
                    return -1;
                }
                found = i;
            }
        }
        return found;
    }

    private static List<Method> uniqueMethods(List<Method> methods) {
        List<Method> unique = new ArrayList<Method>();
        if (methods == null) {
            return unique;
        }
        for (Method method : methods) {
            if (method != null && !unique.contains(method)) {
                unique.add(method);
            }
        }
        return unique;
    }

    static MenuResolverSelection selectUniqueCompleteMenuBundle(
            List<MenuResolverBundle> candidates, Class<?> menuReturnType) {
        List<HostAbi.MenuCapability> completeCandidates =
                new ArrayList<HostAbi.MenuCapability>();
        if (candidates != null && menuReturnType != null) {
            for (MenuResolverBundle candidate : candidates) {
                if (candidate == null
                        || candidate.labelResolver == null
                        || candidate.reasoningEnumClass == null) {
                    continue;
                }
                Method menuMethod = (Method) chooseUnique("menuCaller", uniqueMethods(candidate.menuCallers));
                Method buttonMethod = (Method) chooseUnique("buttonCaller", uniqueMethods(candidate.buttonCallers));
                if (menuMethod == null || buttonMethod == null
                        || menuMethod.getReturnType() != menuReturnType
                        || buttonMethod.getReturnType() != void.class) {
                    continue;
                }
                int buttonEnumIndex = enumParameterIndex(
                        buttonMethod, candidate.reasoningEnumClass);
                if (buttonEnumIndex < 0) {
                    continue;
                }
                try {
                    completeCandidates.add(new HostAbi.MenuCapability(
                            candidate.labelResolver,
                            candidate.labels,
                            menuMethod,
                            buttonMethod,
                            candidate.reasoningEnumClass,
                            buttonEnumIndex));
                } catch (Throwable ignored) {
                    // A malformed bundle is not a supported menu capability.
                }
            }
        }
        HostAbi.MenuCapability selected = (HostAbi.MenuCapability) chooseUnique(
                "menuResolver", completeCandidates);
        return new MenuResolverSelection(selected, completeCandidates.size());
    }

    private static String methodDescriptor(Method method) {
        if (method == null || method.getDeclaringClass() == null) {
            return null;
        }
        StringBuilder descriptor = new StringBuilder();
        descriptor.append(typeDescriptor(method.getDeclaringClass()));
        descriptor.append("->").append(method.getName()).append('(');
        for (Class<?> parameter : method.getParameterTypes()) {
            descriptor.append(typeDescriptor(parameter));
        }
        descriptor.append(')').append(typeDescriptor(method.getReturnType()));
        return descriptor.toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type == null) {
            return "";
        }
        if (type.isPrimitive()) {
            if (type == void.class) {
                return "V";
            }
            if (type == boolean.class) {
                return "Z";
            }
            if (type == byte.class) {
                return "B";
            }
            if (type == char.class) {
                return "C";
            }
            if (type == short.class) {
                return "S";
            }
            if (type == int.class) {
                return "I";
            }
            if (type == long.class) {
                return "J";
            }
            if (type == float.class) {
                return "F";
            }
            if (type == double.class) {
                return "D";
            }
        }
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }

    static final class MenuResolverBundle {
        final Method labelResolver;
        final List<Method> menuCallers;
        final List<Method> buttonCallers;
        final Map<Integer, String> labels;
        final Class<?> reasoningEnumClass;

        MenuResolverBundle(
                Method labelResolver,
                List<Method> menuCallers,
                List<Method> buttonCallers,
                Map<Integer, String> labels,
                Class<?> reasoningEnumClass) {
            this.labelResolver = labelResolver;
            this.menuCallers = menuCallers;
            this.buttonCallers = buttonCallers;
            this.labels = labels;
            this.reasoningEnumClass = reasoningEnumClass;
        }
    }

    static final class MenuResolverSelection {
        final HostAbi.MenuCapability capability;
        final int completeCount;

        MenuResolverSelection(HostAbi.MenuCapability capability, int completeCount) {
            this.capability = capability;
            this.completeCount = completeCount;
        }
    }

    private static final class MenuResolverCandidate {
        final MethodData data;
        final Method method;

        MenuResolverCandidate(MethodData data, Method method) {
            this.data = data;
            this.method = method;
        }
    }

    private static CapabilityResolution<HostAbi.RequestCapability> resolveGenericRequest(
            DexKitBridge bridge, ClassLoader loader, EnumPrerequisite prerequisite) {
        try {
            if (prerequisite == null || prerequisite.enumClass == null) {
                return new CapabilityResolution<HostAbi.RequestCapability>(null, 0);
            }
            Class<?> reasoningClass = prerequisite.enumClass;
            MethodMatcher genericMatcher = new MethodMatcher()
                    .returnType(String.class)
                    .paramCount(6)
                    .usingEqStrings("reasoning_effort", "messages", "stream");
            List<Method> candidates = queryRequestMethods(
                    bridge, loader, genericMatcher, reasoningClass, true);
            Method method = (Method) chooseUnique("genericRequest", candidates);
            if (method == null) {
                return new CapabilityResolution<HostAbi.RequestCapability>(
                        null, candidates.size());
            }
            return new CapabilityResolution<HostAbi.RequestCapability>(
                    new HostAbi.RequestCapability(
                            method, RequestJsonPolicy.ProviderKind.GENERIC),
                    1);
        } catch (Throwable ignored) {
            return new CapabilityResolution<HostAbi.RequestCapability>(null, 0);
        }
    }

    private static CapabilityResolution<HostAbi.RequestCapability> resolveThinkingRequest(
            DexKitBridge bridge, ClassLoader loader, EnumPrerequisite prerequisite) {
        try {
            if (prerequisite == null || prerequisite.enumClass == null) {
                return new CapabilityResolution<HostAbi.RequestCapability>(null, 0);
            }
            Class<?> reasoningClass = prerequisite.enumClass;
            MethodMatcher thinkingMatcher = new MethodMatcher()
                    .returnType(String.class)
                    .paramCount(4)
                    .usingEqStrings("max_tokens", "thinking", "budget_tokens");
            List<Method> candidates = queryRequestMethods(
                    bridge, loader, thinkingMatcher, reasoningClass, false);
            Method method = (Method) chooseUnique("thinkingRequest", candidates);
            if (method == null) {
                return new CapabilityResolution<HostAbi.RequestCapability>(
                        null, candidates.size());
            }
            return new CapabilityResolution<HostAbi.RequestCapability>(
                    new HostAbi.RequestCapability(
                            method, RequestJsonPolicy.ProviderKind.THINKING),
                    1);
        } catch (Throwable ignored) {
            return new CapabilityResolution<HostAbi.RequestCapability>(null, 0);
        }
    }

    private static List<Method> queryRequestMethods(
            DexKitBridge bridge,
            ClassLoader loader,
            MethodMatcher matcher,
            Class<?> reasoningClass,
            boolean generic) {
        if (bridge == null || loader == null || matcher == null || reasoningClass == null) {
            return new ArrayList<Method>();
        }
        try {
            List<MethodData> data = queryMethodData(bridge, new FindMethod().matcher(matcher));
            List<Method> methods = new ArrayList<Method>();
            for (MethodData methodData : data) {
                if (!matchesRequestMetadata(methodData, loader, reasoningClass, generic)) {
                    continue;
                }
                Method method = reflectMethod(methodData, loader);
                if (method != null
                        && matchesRequestReflection(method, reasoningClass, generic)) {
                    methods.add(method);
                }
            }
            return methods;
        } catch (Throwable ignored) {
            return new ArrayList<Method>();
        }
    }

    private static boolean matchesRequestMetadata(
            MethodData methodData,
            ClassLoader loader,
            Class<?> reasoningClass,
            boolean generic) {
        if (methodData == null || loader == null || reasoningClass == null) {
            return false;
        }
        try {
            if (loadType(loader, methodData.getReturnTypeName()) != String.class
                    || Modifier.isStatic(methodData.getModifiers())) {
                return false;
            }
            int expectedCount = generic ? 6 : 4;
            if (methodData.getParamCount() != expectedCount) {
                return false;
            }
            List<String> parameterNames = methodData.getParamTypeNames();
            if (parameterNames == null || parameterNames.size() != expectedCount) {
                return false;
            }
            Class<?> providerClass = loadType(loader, parameterNames.get(0));
            if (providerClass == null || providerClass.isPrimitive()
                    || !matchesMetadataType(loader, parameterNames, 1, List.class)
                    || !matchesMetadataType(loader, parameterNames, 2, String.class)) {
                return false;
            }
            if (generic
                    && (!matchesMetadataType(loader, parameterNames, 3, boolean.class)
                    || !matchesMetadataType(loader, parameterNames, 4, String.class))) {
                return false;
            }
            return matchesMetadataType(
                    loader, parameterNames, expectedCount - 1, reasoningClass);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean matchesMetadataType(
            ClassLoader loader, List<String> parameterNames, int index, Class<?> expected) {
        if (loader == null || parameterNames == null || expected == null
                || index < 0 || index >= parameterNames.size()) {
            return false;
        }
        Class<?> actual = loadType(loader, parameterNames.get(index));
        return actual == expected;
    }

    private static boolean matchesRequestReflection(
            Method method, Class<?> reasoningClass, boolean generic) {
        if (method == null || reasoningClass == null
                || Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != String.class) {
            return false;
        }
        Class<?>[] actual = method.getParameterTypes();
        int expectedCount = generic ? 6 : 4;
        if (actual.length != expectedCount || actual[0].isPrimitive()
                || actual[1] != List.class || actual[2] != String.class
                || actual[actual.length - 1] != reasoningClass) {
            return false;
        }
        return !generic || (actual[3] == boolean.class && actual[4] == String.class);
    }

    private static List<Method> queryMethodsInClass(
            DexKitBridge bridge,
            ClassLoader loader,
            ClassData owner,
            String returnTypeName,
            boolean staticExpected,
            String[] parameterNames,
            String... usingStrings) {
        MethodMatcher matcher = new MethodMatcher()
                .returnType(returnTypeName)
                .paramTypes(parameterNames);
        if (usingStrings != null && usingStrings.length > 0) {
            matcher.usingEqStrings(usingStrings);
        }
        return queryMethodsInClass(
                bridge, loader, owner, matcher, returnTypeName, staticExpected, parameterNames);
    }

    private static List<Method> queryMethodsInClassWithInvoke(
            DexKitBridge bridge,
            ClassLoader loader,
            ClassData owner,
            String returnTypeName,
            boolean staticExpected,
            String[] parameterNames,
            String invokeDescriptor,
            String... usingStrings) {
        MethodMatcher matcher = new MethodMatcher()
                .returnType(returnTypeName)
                .paramTypes(parameterNames)
                .addInvoke(invokeDescriptor);
        if (usingStrings != null && usingStrings.length > 0) {
            matcher.usingEqStrings(usingStrings);
        }
        return queryMethodsInClass(
                bridge,
                loader,
                owner,
                matcher,
                returnTypeName,
                staticExpected,
                parameterNames,
                invokeDescriptor);
    }

    private static List<Method> queryMethodsInClass(
            DexKitBridge bridge,
            ClassLoader loader,
            ClassData owner,
            MethodMatcher matcher,
            String returnTypeName,
            boolean staticExpected,
            String[] parameterNames,
            String... requiredInvokeDescriptors) {
        try {
            List<MethodData> data = queryMethodData(
                    bridge,
                    new FindMethod()
                            .searchInClass(Collections.singletonList(owner))
                            .matcher(matcher));
            Class<?> returnType = loadType(loader, returnTypeName);
            Class<?>[] parameterTypes = loadTypes(loader, parameterNames);
            if (returnType == null || parameterTypes == null) {
                return new ArrayList<Method>();
            }
            List<Method> methods = new ArrayList<Method>();
            for (MethodData methodData : data) {
                if (!matchesExactMetadata(
                        methodData, returnTypeName, staticExpected, parameterNames)
                        || !hasAllInvokeDescriptors(methodData, requiredInvokeDescriptors)) {
                    continue;
                }
                Method method = reflectMethod(methodData, loader);
                if (method != null) {
                    methods.addAll(filterMethods(
                            Collections.singletonList(method), returnType,
                            staticExpected, parameterTypes));
                }
            }
            return methods;
        } catch (Throwable ignored) {
            return new ArrayList<Method>();
        }
    }

    private static ClassDataList queryClasses(DexKitBridge bridge, String... strings) {
        try {
            ClassMatcher matcher = new ClassMatcher().usingEqStrings(strings);
            ClassDataList data = bridge.findClass(new FindClass().matcher(matcher));
            return data == null ? new ClassDataList() : data;
        } catch (Throwable ignored) {
            return new ClassDataList();
        }
    }

    private static List<MethodData> queryMethodData(
            DexKitBridge bridge, FindMethod finder) {
        try {
            MethodDataList data = bridge.findMethod(finder);
            return data == null ? new ArrayList<MethodData>() : data;
        } catch (Throwable ignored) {
            return new ArrayList<MethodData>();
        }
    }

    private static Method reflectMethod(MethodData methodData, ClassLoader loader) {
        if (methodData == null || loader == null) {
            return null;
        }
        try {
            return methodData.getMethodInstance(loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean matchesExactMetadata(
            MethodData methodData,
            String returnTypeName,
            boolean staticExpected,
            String[] parameterNames) {
        if (methodData == null || returnTypeName == null || parameterNames == null) {
            return false;
        }
        try {
            if (!returnTypeName.equals(methodData.getReturnTypeName())
                    || Modifier.isStatic(methodData.getModifiers()) != staticExpected) {
                return false;
            }
            List<String> actual = methodData.getParamTypeNames();
            if (actual == null || actual.size() != parameterNames.length) {
                return false;
            }
            for (int i = 0; i < parameterNames.length; i++) {
                if (parameterNames[i] == null || !parameterNames[i].equals(actual.get(i))) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasAllInvokeDescriptors(
            MethodData methodData, String... requiredInvokeDescriptors) {
        if (requiredInvokeDescriptors == null || requiredInvokeDescriptors.length == 0) {
            return true;
        }
        if (methodData == null) {
            return false;
        }
        for (String descriptor : requiredInvokeDescriptors) {
            if (!hasInvokeDescriptor(methodData, descriptor)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasInvokeDescriptor(MethodData methodData, String descriptor) {
        if (methodData == null || descriptor == null) {
            return false;
        }
        try {
            MethodDataList invokes = methodData.getInvokes();
            if (invokes == null) {
                return false;
            }
            for (MethodData invoke : invokes) {
                if (invoke != null && descriptor.equals(invoke.getDescriptor())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static ClassData uniqueClass(ClassDataList candidates) {
        Object selected = chooseUnique("class", candidates);
        return selected instanceof ClassData ? (ClassData) selected : null;
    }

    private static Constructor<?> uniqueNoArgConstructor(Class<?> owner) {
        try {
            Constructor<?>[] constructors = owner.getDeclaredConstructors();
            List<Constructor<?>> noArg = new ArrayList<Constructor<?>>();
            for (Constructor<?> constructor : constructors) {
                if (constructor != null && constructor.getParameterTypes().length == 0) {
                    noArg.add(constructor);
                }
            }
            Object selected = chooseUnique("repositoryConstructor", noArg);
            return selected instanceof Constructor<?> ? (Constructor<?>) selected : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?>[] loadTypes(ClassLoader loader, String[] names) {
        if (names == null) {
            return null;
        }
        Class<?>[] types = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            types[i] = loadType(loader, names[i]);
            if (types[i] == null) {
                return null;
            }
        }
        return types;
    }

    private static Class<?> loadHostClass(ClassLoader loader, String typeName) {
        return loadType(loader, typeName);
    }

    private static Class<?> loadType(ClassLoader loader, String typeName) {
        if (typeName == null) {
            return null;
        }
        if ("boolean".equals(typeName)) {
            return boolean.class;
        }
        if ("byte".equals(typeName)) {
            return byte.class;
        }
        if ("char".equals(typeName)) {
            return char.class;
        }
        if ("short".equals(typeName)) {
            return short.class;
        }
        if ("int".equals(typeName)) {
            return int.class;
        }
        if ("long".equals(typeName)) {
            return long.class;
        }
        if ("float".equals(typeName)) {
            return float.class;
        }
        if ("double".equals(typeName)) {
            return double.class;
        }
        if ("void".equals(typeName)) {
            return void.class;
        }
        if (loader == null) {
            return null;
        }
        try {
            return loader.loadClass(typeName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String returnTypeName(Class<?> type) {
        return type == null ? null : type.getName();
    }

    private static int sizeOf(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static ScanResult emptyResult(String fingerprint, boolean cacheHit) {
        return new ScanResult(
                new HostAbi(null, null, null, null),
                0, 0, 0, 0,
                fingerprint != null,
                fingerprint,
                cacheHit);
    }

    static final class ScanResult {
        final HostAbi abi;
        final int reasoningSourceCount;
        final int menuLabelsCount;
        final int genericRequestCount;
        final int thinkingRequestCount;
        final boolean apkPathValidated;
        final String contentFingerprint;
        final boolean cacheHit;

        ScanResult(
                HostAbi abi,
                int reasoningSourceCount,
                int menuLabelsCount,
                int genericRequestCount,
                int thinkingRequestCount,
                boolean apkPathValidated) {
            this(
                    abi,
                    reasoningSourceCount,
                    menuLabelsCount,
                    genericRequestCount,
                    thinkingRequestCount,
                    apkPathValidated,
                    null,
                    false);
        }

        ScanResult(
                HostAbi abi,
                int reasoningSourceCount,
                int menuLabelsCount,
                int genericRequestCount,
                int thinkingRequestCount,
                boolean apkPathValidated,
                String contentFingerprint,
                boolean cacheHit) {
            this.abi = abi;
            this.reasoningSourceCount = reasoningSourceCount;
            this.menuLabelsCount = menuLabelsCount;
            this.genericRequestCount = genericRequestCount;
            this.thinkingRequestCount = thinkingRequestCount;
            this.apkPathValidated = apkPathValidated;
            this.contentFingerprint = contentFingerprint;
            this.cacheHit = cacheHit;
        }

        ScanResult withCacheHit(boolean cacheHit) {
            return new ScanResult(
                    abi,
                    reasoningSourceCount,
                    menuLabelsCount,
                    genericRequestCount,
                    thinkingRequestCount,
                    apkPathValidated,
                    contentFingerprint,
                    cacheHit);
        }
    }

    private static final class CacheEntry {
        final ClassLoader loader;
        final String fingerprint;
        final ScanResult result;

        CacheEntry(ClassLoader loader, String fingerprint, ScanResult result) {
            this.loader = loader;
            this.fingerprint = fingerprint;
            this.result = result;
        }
    }

    private static final class EnumPrerequisite {
        final Class<?> enumClass;
        final Method resourceIdGetter;
        final String resourceIdDescriptor;

        EnumPrerequisite(
                Class<?> enumClass, Method resourceIdGetter, String resourceIdDescriptor) {
            this.enumClass = enumClass;
            this.resourceIdGetter = resourceIdGetter;
            this.resourceIdDescriptor = resourceIdDescriptor;
        }
    }

    private static final class CapabilityResolution<T> {
        final T capability;
        final int count;

        CapabilityResolution(T capability, int count) {
            this.capability = capability;
            this.count = count;
        }
    }
}
