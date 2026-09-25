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
import java.util.concurrent.TimeUnit;

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
    private static final String TYPE_TIME_UNIT = "java.util.concurrent.TimeUnit";
    // Exact obfuscated v14 setter names; resolveNetwork is gated by the exact APK fingerprint below.
    private static final String V14_CONNECT_TIMEOUT_SETTER_NAME = "c";
    private static final String V14_READ_TIMEOUT_SETTER_NAME = "M";
    private static final String V14_WRITE_TIMEOUT_SETTER_NAME = "P";
    private static final long HOST_DEFAULT_READ_TIMEOUT_SECONDS = 120L;
    private static final String VERIFIED_V14_NETWORK_FINGERPRINT =
            "6e3e12bd40f1c1156a1a967a57780fa62380d0cd78c41c16a06434b8b043a238";

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
    private static final String TYPE_KOTLIN_FUNCTION1 =
            "kotlin.jvm.functions.Function1";
    private static final String TYPE_ATOMIC_BOOLEAN =
            "java.util.concurrent.atomic.AtomicBoolean";
    private static final String TYPE_CANCELLATION_EXCEPTION =
            "java.util.concurrent.CancellationException";
    private static final String TYPE_CONCURRENT_HASH_MAP =
            "java.util.concurrent.ConcurrentHashMap";
    private static final String STREAM_CANCELLED_MARKER =
            "__ask_user_cancelled__";
    private static final String ATOMIC_BOOLEAN_SET_DESCRIPTOR =
            "Ljava/util/concurrent/atomic/AtomicBoolean;->set(Z)V";
    private static final String CONCURRENT_HASH_MAP_VALUES_DESCRIPTOR =
            "Ljava/util/concurrent/ConcurrentHashMap;->values()Ljava/util/Collection;";
    private static final String CONCURRENT_HASH_MAP_CLEAR_DESCRIPTOR =
            "Ljava/util/concurrent/ConcurrentHashMap;->clear()V";
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

    static boolean isVerifiedV14NetworkFingerprint(String fingerprint) {
        return VERIFIED_V14_NETWORK_FINGERPRINT.equals(fingerprint);
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
                if (field != null
                        && !Modifier.isStatic(field.getModifiers())
                        && "int".equals(field.getTypeName())
                        && field.getDeclaredClassName() != null
                        && enumData.getName() != null
                        && enumData.getName().equals(field.getDeclaredClassName())) {
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

    private static CapabilityResolution<HostAbi.StreamLifecycleCapability>
            resolveStreamLifecycle(DexKitBridge bridge, ClassLoader loader) {
        try {
            Class<?> callbackType = loadHostClass(loader, TYPE_KOTLIN_FUNCTION1);
            if (bridge == null || loader == null || callbackType == null) {
                return new CapabilityResolution<HostAbi.StreamLifecycleCapability>(null, 0);
            }
            MethodMatcher matcher = new MethodMatcher()
                    .returnType("void")
                    .paramCount(3);
            List<MethodData> data = queryMethodData(
                    bridge, new FindMethod().matcher(matcher));
            List<StreamLifecycleCandidate> candidates =
                    new ArrayList<StreamLifecycleCandidate>();
            for (MethodData methodData : data) {
                Method method = reflectMethod(methodData, loader);
                if (isStreamLifecycleStarterMetadata(
                        methodData, method, callbackType)) {
                    candidates.add(new StreamLifecycleCandidate(methodData, method));
                }
            }
            StreamLifecycleSelection selection =
                    selectUniqueStructuralStreamLifecycle(candidates, callbackType);
            if (selection.capability != null) {
                selection.capability.starter.setAccessible(true);
                for (Method cleanupMethod : selection.capability.cleanupMethods) {
                    cleanupMethod.setAccessible(true);
                }
            }
            return new CapabilityResolution<HostAbi.StreamLifecycleCapability>(
                    selection.capability, selection.candidateCount);
        } catch (Throwable ignored) {
            return new CapabilityResolution<HostAbi.StreamLifecycleCapability>(null, 0);
        }
    }

    /**
     * Selects the current v13 request reset and explicit cancellation paths by
     * owner-local field, invoke, and constant structure. Current v13 owners
     * may have unrelated no-arg methods, so a declared-method count is not a
     * lifecycle contract; any missing or ambiguous structural match fails
     * closed.
     */
    private static StreamLifecycleSelection selectUniqueStructuralStreamLifecycle(
            List<StreamLifecycleCandidate> candidates, Class<?> callbackType) {
        List<StreamLifecycleCandidate> structuralStarters =
                uniqueStructuralCandidates(candidates);
        int candidateCount = structuralStarters.size();
        if (structuralStarters.size() != 1) {
            return new StreamLifecycleSelection(null, candidateCount);
        }

        StructuralCleanupSelection cleanup = selectStructuralCleanupMethods(
                structuralStarters.get(0), callbackType);
        if (cleanup.methods.size() != 2) {
            return new StreamLifecycleSelection(
                    null, Math.max(candidateCount, cleanup.candidateCount));
        }
        try {
            HostAbi.StreamLifecycleCapability capability =
                    new HostAbi.StreamLifecycleCapability(
                            structuralStarters.get(0).method,
                            cleanup.methods,
                            callbackType);
            return new StreamLifecycleSelection(capability, 1);
        } catch (Throwable ignored) {
            return new StreamLifecycleSelection(null, cleanup.candidateCount);
        }
    }

    private static List<StreamLifecycleCandidate> uniqueStructuralCandidates(
            List<StreamLifecycleCandidate> candidates) {
        List<StreamLifecycleCandidate> unique =
                new ArrayList<StreamLifecycleCandidate>();
        if (candidates == null) {
            return unique;
        }
        for (StreamLifecycleCandidate candidate : candidates) {
            if (candidate == null || candidate.method == null) {
                continue;
            }
            boolean duplicate = false;
            for (StreamLifecycleCandidate existing : unique) {
                if (sameMethodIdentity(existing.method, candidate.method)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    private static StructuralCleanupSelection selectStructuralCleanupMethods(
            StreamLifecycleCandidate starter, Class<?> callbackType) {
        if (starter == null || starter.metadata == null
                || starter.method == null || callbackType == null) {
            return new StructuralCleanupSelection(
                    Collections.<Method>emptyList(), 0);
        }
        try {
            String ownerName = starter.metadata.getDeclaredClassName();
            ClassData ownerData = starter.metadata.getDeclaredClass();
            MethodDataList ownerMethods = ownerData == null
                    ? null : ownerData.getMethods();
            if (ownerName == null || ownerData == null || ownerMethods == null) {
                return new StructuralCleanupSelection(
                        Collections.<Method>emptyList(), 0);
            }

            List<Method> cleanupMethods = new ArrayList<Method>();
            List<Method> resetCandidates = new ArrayList<Method>();
            List<Method> cancellationCandidates = new ArrayList<Method>();
            for (MethodData methodData : ownerMethods) {
                if (!isStreamLifecycleCleanupMetadata(methodData, ownerName)) {
                    continue;
                }
                Method method = reflectMethod(methodData,
                        starter.method.getDeclaringClass().getClassLoader());
                if (!isStreamLifecycleCleanup(
                        starter.method.getDeclaringClass(), method)) {
                    continue;
                }
                addUniqueMethod(cleanupMethods, method);
                if (isRequestResetCleanup(methodData, ownerName)) {
                    addUniqueMethod(resetCandidates, method);
                }
                if (isExplicitCancellationCleanup(methodData, ownerName)) {
                    addUniqueMethod(cancellationCandidates, method);
                }
            }
            int cleanupCandidateCount = cleanupMethods.size();
            if (resetCandidates.size() != 1
                    || cancellationCandidates.size() != 1
                    || sameMethodIdentity(
                    resetCandidates.get(0), cancellationCandidates.get(0))) {
                return new StructuralCleanupSelection(
                        Collections.<Method>emptyList(), cleanupCandidateCount);
            }
            List<Method> selected = new ArrayList<Method>(2);
            selected.add(resetCandidates.get(0));
            selected.add(cancellationCandidates.get(0));
            return new StructuralCleanupSelection(selected, cleanupCandidateCount);
        } catch (Throwable ignored) {
            return new StructuralCleanupSelection(
                    Collections.<Method>emptyList(), 0);
        }
    }

    private static boolean isStreamLifecycleStarterMetadata(
            MethodData methodData, Method method, Class<?> callbackType) {
        if (methodData == null || method == null || callbackType == null
                || !"void".equals(methodData.getReturnTypeName())
                || methodData.getParamCount() != 3
                || Modifier.isStatic(methodData.getModifiers())
                || methodData.getDeclaredClassName() == null
                || !isStreamLifecycleStarter(method, callbackType)) {
            return false;
        }
        return method.getDeclaringClass().getName().equals(
                methodData.getDeclaredClassName());
    }

    private static boolean isStreamLifecycleCleanupMetadata(
            MethodData methodData, String ownerName) {
        return methodData != null && ownerName != null
                && ownerName.equals(methodData.getDeclaredClassName())
                && !Modifier.isStatic(methodData.getModifiers())
                && "void".equals(methodData.getReturnTypeName())
                && methodData.getParamCount() == 0;
    }

    private static boolean isRequestResetCleanup(
            MethodData methodData, String ownerName) {
        return hasOwnerField(methodData, ownerName, TYPE_ATOMIC_BOOLEAN, true)
                && hasOwnerField(methodData, ownerName, "long", false)
                && hasInvokeDescriptor(methodData, ATOMIC_BOOLEAN_SET_DESCRIPTOR)
                && hasCancellationInvoke(methodData);
    }

    private static boolean isExplicitCancellationCleanup(
            MethodData methodData, String ownerName) {
        return hasOwnerField(methodData, ownerName, TYPE_CONCURRENT_HASH_MAP, true)
                && hasUsingString(methodData, STREAM_CANCELLED_MARKER)
                && hasInvokeDescriptor(methodData, CONCURRENT_HASH_MAP_VALUES_DESCRIPTOR)
                && hasInvokeDescriptor(methodData, CONCURRENT_HASH_MAP_CLEAR_DESCRIPTOR)
                && hasInvokeWithParameterType(methodData, TYPE_STRING);
    }

    private static boolean hasOwnerField(
            MethodData methodData, String ownerName, String typeName, boolean read) {
        if (methodData == null || ownerName == null || typeName == null) {
            return false;
        }
        try {
            List<UsingFieldData> fields = methodData.getUsingFields();
            if (fields == null) {
                return false;
            }
            for (UsingFieldData usingField : fields) {
                if (usingField == null || usingField.getUsingType() == null) {
                    continue;
                }
                FieldData field = usingField.getField();
                if (field == null
                        || !ownerName.equals(field.getDeclaredClassName())
                        || !typeName.equals(field.getTypeName())
                        || (read
                        ? !usingField.getUsingType().isRead()
                        : !usingField.getUsingType().isWrite())) {
                    continue;
                }
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static boolean hasUsingString(MethodData methodData, String expected) {
        if (methodData == null || expected == null) {
            return false;
        }
        try {
            List<String> strings = methodData.getUsingStrings();
            return strings != null && strings.contains(expected);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasCancellationInvoke(MethodData methodData) {
        if (methodData == null) {
            return false;
        }
        try {
            MethodDataList invokes = methodData.getInvokes();
            if (invokes == null) {
                return false;
            }
            for (MethodData invoke : invokes) {
                if (invoke == null) {
                    continue;
                }
                List<String> parameters = invoke.getParamTypeNames();
                if (parameters != null
                        && parameters.contains(TYPE_CANCELLATION_EXCEPTION)) {
                    return true;
                }
                String descriptor = invoke.getDescriptor();
                if (descriptor != null && descriptor.contains(
                        "Ljava/util/concurrent/CancellationException;")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static boolean hasInvokeWithParameterType(
            MethodData methodData, String expected) {
        if (methodData == null || expected == null) {
            return false;
        }
        try {
            MethodDataList invokes = methodData.getInvokes();
            if (invokes == null) {
                return false;
            }
            for (MethodData invoke : invokes) {
                if (invoke != null && invoke.getParamTypeNames() != null
                        && invoke.getParamTypeNames().contains(expected)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static void addUniqueMethod(List<Method> methods, Method candidate) {
        if (methods == null || candidate == null) {
            return;
        }
        for (Method existing : methods) {
            if (sameMethodIdentity(existing, candidate)) {
                return;
            }
        }
        methods.add(candidate);
    }

    private static boolean sameMethodIdentity(Method left, Method right) {
        return left == right || (left != null && left.equals(right));
    }

    static StreamLifecycleSelection selectUniqueStreamLifecycle(
            List<Method> methods, Class<?> callbackType) {
        List<Method> structuralStarters = new ArrayList<Method>();
        if (methods != null) {
            for (Method method : methods) {
                if (isStreamLifecycleStarter(method, callbackType)) {
                    structuralStarters.add(method);
                }
            }
        }
        structuralStarters = uniqueMethods(structuralStarters);

        List<HostAbi.StreamLifecycleCapability> valid =
                new ArrayList<HostAbi.StreamLifecycleCapability>();
        int candidateCount = structuralStarters.size();
        int ambiguousCleanupCount = 0;
        for (Method starter : structuralStarters) {
            List<Method> cleanupMethods = new ArrayList<Method>();
            try {
                Method[] declaredMethods = starter.getDeclaringClass().getDeclaredMethods();
                for (Method declaredMethod : declaredMethods) {
                    if (isStreamLifecycleCleanup(
                            starter.getDeclaringClass(), declaredMethod)) {
                        cleanupMethods.add(declaredMethod);
                    }
                }
            } catch (Throwable ignored) {
                cleanupMethods.clear();
            }
            cleanupMethods = uniqueMethods(cleanupMethods);
            if (cleanupMethods.size() != 2) {
                ambiguousCleanupCount = Math.max(ambiguousCleanupCount, cleanupMethods.size());
                continue;
            }
            try {
                valid.add(new HostAbi.StreamLifecycleCapability(
                        starter, cleanupMethods, callbackType));
            } catch (Throwable ignored) {
                // A malformed owner is not a supported lifecycle capability.
            }
        }
        candidateCount = Math.max(candidateCount, ambiguousCleanupCount);
        HostAbi.StreamLifecycleCapability selected = structuralStarters.size() == 1
                ? (HostAbi.StreamLifecycleCapability) chooseUnique(
                        "streamLifecycle", valid)
                : null;
        return new StreamLifecycleSelection(selected, candidateCount);
    }

    private static boolean isStreamLifecycleStarter(
            Method method, Class<?> callbackType) {
        if (method == null || callbackType == null
                || Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != void.class) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length == 3
                && !parameters[0].isPrimitive()
                && parameters[1] == List.class
                && parameters[2] == callbackType;
    }

    private static boolean isStreamLifecycleCleanup(Class<?> owner, Method method) {
        return owner != null && method != null
                && method.getDeclaringClass() == owner
                && !Modifier.isStatic(method.getModifiers())
                && method.getReturnType() == void.class
                && method.getParameterTypes().length == 0;
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
        CapabilityResolution<HostAbi.StreamLifecycleCapability> stream =
                resolveStreamLifecycle(bridge, loader);
        CapabilityResolution<HostAbi.NetworkCapability> network =
                isVerifiedV14NetworkFingerprint(fingerprint)
                        ? resolveNetwork(bridge, loader)
                        : new CapabilityResolution<HostAbi.NetworkCapability>(null, 0);
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
                thinking.capability,
                network.capability,
                stream.capability);
        return new ScanResult(
                abi,
                reasoning.count,
                menu.count,
                generic.count,
                thinking.count,
                stream.count,
                fingerprint != null,
                fingerprint,
                false);
    }

    private static CapabilityResolution<HostAbi.NetworkCapability> resolveNetwork(
            DexKitBridge bridge, ClassLoader loader) {
        try {
            MethodMatcher matcher = new MethodMatcher()
                    .paramTypes("long", TYPE_TIME_UNIT);
            List<MethodData> setterData = queryMethodData(
                    bridge, new FindMethod().matcher(matcher));
            List<NetworkCandidate> selected = new ArrayList<NetworkCandidate>();
            for (MethodData setter : setterData) {
                if (!isNetworkSetterMetadata(setter)) {
                    continue;
                }
                Method reflectedSetter = reflectMethod(setter, loader);
                if (!NetworkStabilityHook.isReadTimeoutSetter(reflectedSetter)) {
                    continue;
                }
                MethodDataList callers = setter.getCallers();
                if (callers == null) {
                    continue;
                }
                for (MethodData caller : callers) {
                    if (caller == null || !caller.isStaticInitializer()) {
                        continue;
                    }
                    List<MethodData> timeoutSetters =
                            networkSetterInvokes(caller, loader);
                    String readSetterSign = v14ReadTimeoutSetterSign(timeoutSetters);
                    if (readSetterSign == null
                            || !readSetterSign.equals(setter.getMethodSign())) {
                        continue;
                    }
                    Class<?> initializerClass = loadHostClass(
                            loader, caller.getDeclaredClassName());
                    if (initializerClass == null) {
                        continue;
                    }
                    selected.add(new NetworkCandidate(reflectedSetter, initializerClass));
                }
            }
            List<NetworkCandidate> unique = uniqueNetworkCandidates(selected);
            if (unique.size() != 1) {
                return new CapabilityResolution<HostAbi.NetworkCapability>(
                        null, unique.size());
            }
            NetworkCandidate candidate = unique.get(0);
            return new CapabilityResolution<HostAbi.NetworkCapability>(
                    new HostAbi.NetworkCapability(
                            candidate.initializerClass,
                            candidate.setter,
                            HOST_DEFAULT_READ_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS,
                            NetworkStabilityHook.DEFAULT_MINIMUM_TIMEOUT_SECONDS),
                    1);
        } catch (Throwable ignored) {
            return new CapabilityResolution<HostAbi.NetworkCapability>(null, 0);
        }
    }

    private static String v14ReadTimeoutSetterSign(List<MethodData> setters) {
        if (setters == null || setters.size() != 3) {
            return null;
        }
        List<NetworkSetterEvidence> evidence =
                new ArrayList<NetworkSetterEvidence>(setters.size());
        for (MethodData setter : setters) {
            NetworkSetterEvidence item = networkSetterEvidence(setter);
            if (item == null) {
                return null;
            }
            evidence.add(item);
        }
        return selectV14ReadTimeoutSetterSign(evidence);
    }

    private static NetworkSetterEvidence networkSetterEvidence(MethodData setter) {
        if (setter == null) {
            return null;
        }
        try {
            List<UsingFieldData> usingFields = setter.getUsingFields();
            if (usingFields == null) {
                return null;
            }
            String writtenField = null;
            for (UsingFieldData usingField : usingFields) {
                if (usingField == null || usingField.getUsingType() == null) {
                    return null;
                }
                if (!usingField.getUsingType().isWrite()) {
                    continue;
                }
                FieldData field = usingField.getField();
                String descriptor = field == null ? null : field.getDescriptor();
                if (descriptor == null || descriptor.length() == 0
                        || (writtenField != null && !writtenField.equals(descriptor))) {
                    return null;
                }
                writtenField = descriptor;
            }
            String methodName = setter.getMethodName();
            String methodSign = setter.getMethodSign();
            if (writtenField == null || methodName == null || methodSign == null) {
                return null;
            }
            return new NetworkSetterEvidence(methodName, methodSign, writtenField);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String selectV14ReadTimeoutSetterSign(
            List<NetworkSetterEvidence> setters) {
        if (setters == null || setters.size() != 3) {
            return null;
        }
        Set<String> methodNames = new HashSet<String>();
        Set<String> methodSigns = new HashSet<String>();
        Set<String> writtenFields = new HashSet<String>();
        String readSetterSign = null;
        for (NetworkSetterEvidence setter : setters) {
            if (setter == null || setter.methodName == null || setter.methodSign == null
                    || setter.writtenFieldDescriptor == null
                    || !methodNames.add(setter.methodName)
                    || !methodSigns.add(setter.methodSign)
                    || !writtenFields.add(setter.writtenFieldDescriptor)) {
                return null;
            }
            if (V14_READ_TIMEOUT_SETTER_NAME.equals(setter.methodName)) {
                readSetterSign = setter.methodSign;
            }
        }
        return methodNames.contains(V14_CONNECT_TIMEOUT_SETTER_NAME)
                && methodNames.contains(V14_READ_TIMEOUT_SETTER_NAME)
                && methodNames.contains(V14_WRITE_TIMEOUT_SETTER_NAME)
                ? readSetterSign : null;
    }

    static final class NetworkSetterEvidence {
        final String methodName;
        final String methodSign;
        final String writtenFieldDescriptor;

        NetworkSetterEvidence(
                String methodName, String methodSign, String writtenFieldDescriptor) {
            this.methodName = methodName;
            this.methodSign = methodSign;
            this.writtenFieldDescriptor = writtenFieldDescriptor;
        }
    }

    private static List<NetworkCandidate> uniqueNetworkCandidates(
            List<NetworkCandidate> candidates) {
        List<NetworkCandidate> unique = new ArrayList<NetworkCandidate>();
        if (candidates == null) {
            return unique;
        }
        for (NetworkCandidate candidate : candidates) {
            if (candidate == null || candidate.setter == null
                    || candidate.initializerClass == null) {
                continue;
            }
            boolean duplicate = false;
            for (NetworkCandidate existing : unique) {
                if (existing.setter.equals(candidate.setter)
                        && existing.initializerClass == candidate.initializerClass) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    private static boolean isNetworkSetterMetadata(MethodData methodData) {
        if (methodData == null || Modifier.isStatic(methodData.getModifiers())
                || methodData.getParamCount() != 2) {
            return false;
        }
        try {
            List<String> params = methodData.getParamTypeNames();
            return params != null && params.size() == 2
                    && "long".equals(params.get(0))
                    && TYPE_TIME_UNIT.equals(params.get(1));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<MethodData> networkSetterInvokes(
            MethodData caller, ClassLoader loader) {
        List<MethodData> setters = new ArrayList<MethodData>();
        try {
            MethodDataList invokes = caller.getInvokes();
            if (invokes == null) {
                return setters;
            }
            for (MethodData invoke : invokes) {
                if (isNetworkSetterMetadata(invoke)
                        && NetworkStabilityHook.isReadTimeoutSetter(
                                reflectMethod(invoke, loader))) {
                    setters.add(invoke);
                }
            }
        } catch (Throwable ignored) {
            setters.clear();
        }
        return setters;
    }

    private static boolean sameMethodData(MethodData left, MethodData right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            String leftSign = left.getMethodSign();
            String rightSign = right.getMethodSign();
            return leftSign != null && leftSign.equals(rightSign);
        } catch (Throwable ignored) {
            return false;
        }
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

    private static final class NetworkCandidate {
        final Method setter;
        final Class<?> initializerClass;

        NetworkCandidate(Method setter, Class<?> initializerClass) {
            this.setter = setter;
            this.initializerClass = initializerClass;
        }
    }

    private static final class StreamLifecycleCandidate {
        final MethodData metadata;
        final Method method;

        StreamLifecycleCandidate(MethodData metadata, Method method) {
            this.metadata = metadata;
            this.method = method;
        }
    }

    private static final class StructuralCleanupSelection {
        final List<Method> methods;
        final int candidateCount;

        StructuralCleanupSelection(List<Method> methods, int candidateCount) {
            this.methods = methods;
            this.candidateCount = candidateCount;
        }
    }

    static final class StreamLifecycleSelection {
        final HostAbi.StreamLifecycleCapability capability;
        final int candidateCount;

        StreamLifecycleSelection(
                HostAbi.StreamLifecycleCapability capability,
                int candidateCount) {
            this.capability = capability;
            this.candidateCount = candidateCount;
        }
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
        final int streamLifecycleCount;
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
                    0,
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
                int streamLifecycleCount,
                boolean apkPathValidated) {
            this(
                    abi,
                    reasoningSourceCount,
                    menuLabelsCount,
                    genericRequestCount,
                    thinkingRequestCount,
                    streamLifecycleCount,
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
            this(
                    abi,
                    reasoningSourceCount,
                    menuLabelsCount,
                    genericRequestCount,
                    thinkingRequestCount,
                    0,
                    apkPathValidated,
                    contentFingerprint,
                    cacheHit);
        }

        ScanResult(
                HostAbi abi,
                int reasoningSourceCount,
                int menuLabelsCount,
                int genericRequestCount,
                int thinkingRequestCount,
                int streamLifecycleCount,
                boolean apkPathValidated,
                String contentFingerprint,
                boolean cacheHit) {
            this.abi = abi;
            this.reasoningSourceCount = reasoningSourceCount;
            this.menuLabelsCount = menuLabelsCount;
            this.genericRequestCount = genericRequestCount;
            this.thinkingRequestCount = thinkingRequestCount;
            this.streamLifecycleCount = streamLifecycleCount;
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
                    streamLifecycleCount,
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
