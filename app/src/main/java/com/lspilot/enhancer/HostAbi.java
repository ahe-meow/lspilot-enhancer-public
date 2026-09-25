package com.lspilot.enhancer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Immutable reflection-only handles for independently resolved host seams. */
public final class HostAbi {
    public final ReasoningCapability reasoningSource;
    public final MenuCapability menuLabels;
    public final RequestCapability genericRequest;
    public final RequestCapability thinkingRequest;
    public final NetworkCapability network;
    public final StreamLifecycleCapability streamLifecycle;

    public HostAbi(
            ReasoningCapability reasoningSource,
            MenuCapability menuLabels,
            RequestCapability genericRequest,
            RequestCapability thinkingRequest) {
        this(reasoningSource, menuLabels, genericRequest, thinkingRequest, null, null);
    }

    public HostAbi(
            ReasoningCapability reasoningSource,
            MenuCapability menuLabels,
            RequestCapability genericRequest,
            RequestCapability thinkingRequest,
            NetworkCapability network) {
        this(reasoningSource, menuLabels, genericRequest, thinkingRequest, network, null);
    }

    public HostAbi(
            ReasoningCapability reasoningSource,
            MenuCapability menuLabels,
            RequestCapability genericRequest,
            RequestCapability thinkingRequest,
            NetworkCapability network,
            StreamLifecycleCapability streamLifecycle) {
        this.reasoningSource = reasoningSource;
        this.menuLabels = menuLabels;
        this.genericRequest = genericRequest;
        this.thinkingRequest = thinkingRequest;
        this.network = network;
        this.streamLifecycle = streamLifecycle;
    }

    public static final class NetworkCapability {
        public final Class<?> initializerClass;
        public final Method readTimeoutSetter;
        public final long expectedTimeout;
        public final TimeUnit expectedUnit;
        public final long minimumTimeout;

        public NetworkCapability(
                Class<?> initializerClass,
                Method readTimeoutSetter,
                long expectedTimeout,
                TimeUnit expectedUnit,
                long minimumTimeout) {
            if (initializerClass == null || initializerClass.isPrimitive()) {
                throw new IllegalArgumentException("initializerClass");
            }
            if (!isReadTimeoutSetter(readTimeoutSetter)) {
                throw new IllegalArgumentException("readTimeoutSetter");
            }
            if (expectedTimeout <= 0 || expectedUnit == null
                    || minimumTimeout <= expectedTimeout) {
                throw new IllegalArgumentException("timeout");
            }
            this.initializerClass = initializerClass;
            this.readTimeoutSetter = readTimeoutSetter;
            this.expectedTimeout = expectedTimeout;
            this.expectedUnit = expectedUnit;
            this.minimumTimeout = minimumTimeout;
        }
    }

    static boolean isReadTimeoutSetter(Method method) {
        if (method == null || Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != method.getDeclaringClass()) {
            return false;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 2
                && parameterTypes[0] == long.class
                && parameterTypes[1] == TimeUnit.class;
    }

    public static final class RequestCapability {
        public final Method method;
        public final RequestJsonPolicy.ProviderKind providerKind;

        public RequestCapability(Method method, RequestJsonPolicy.ProviderKind providerKind) {
            this.method = requireMethod(method, "method");
            if (providerKind == null) {
                throw new IllegalArgumentException("providerKind");
            }
            this.providerKind = providerKind;
        }
    }

    public static final class ReasoningCapability {
        public final Method getter;
        public final Constructor<?> repositoryConstructor;
        public final Class<?> reasoningEnumClass;

        /**
         * Legacy test/runtime constructor retained for callers that do not
         * participate in adaptive enum discovery yet.
         */
        public ReasoningCapability(Method getter, Constructor<?> repositoryConstructor) {
            this(getter, repositoryConstructor, null);
        }

        public ReasoningCapability(
                Method getter,
                Constructor<?> repositoryConstructor,
                Class<?> reasoningEnumClass) {
            this.getter = requireMethod(getter, "getter");
            if (repositoryConstructor == null) {
                throw new IllegalArgumentException("repositoryConstructor");
            }
            this.repositoryConstructor = repositoryConstructor;
            this.reasoningEnumClass = reasoningEnumClass;
        }
    }

    public static final class MenuCapability {
        public final Method labelResolver;
        public final Map<Integer, String> labels;
        public final Method menuMethod;
        public final Method buttonMethod;
        public final Class<?> reasoningEnumClass;
        public final int buttonEnumIndex;

        public MenuCapability(
                Method labelResolver,
                Map<Integer, String> labels,
                Method menuMethod,
                Method buttonMethod,
                Class<?> reasoningEnumClass,
                int buttonEnumIndex) {
            this.labelResolver = requireMethod(labelResolver, "labelResolver");
            this.menuMethod = requireMethod(menuMethod, "menuMethod");
            this.buttonMethod = requireMethod(buttonMethod, "buttonMethod");
            if (labels == null
                    || labels.size() != ReasoningPolicy.SUPPORTED.length) {
                throw new IllegalArgumentException("labels");
            }
            for (Map.Entry<Integer, String> entry : labels.entrySet()) {
                if (entry.getKey() == null || !ReasoningPolicy.isSupported(entry.getValue())) {
                    throw new IllegalArgumentException("labels");
                }
            }
            for (String supported : ReasoningPolicy.SUPPORTED) {
                if (!labels.containsValue(supported)) {
                    throw new IllegalArgumentException("labels");
                }
            }
            if (reasoningEnumClass == null || !reasoningEnumClass.isEnum()) {
                throw new IllegalArgumentException("reasoningEnumClass");
            }
            Class<?>[] buttonParameters = buttonMethod.getParameterTypes();
            int buttonEnumMatches = 0;
            for (Class<?> parameter : buttonParameters) {
                if (parameter == reasoningEnumClass) {
                    buttonEnumMatches++;
                }
            }
            if (buttonEnumMatches != 1
                    || buttonEnumIndex < 0 || buttonEnumIndex >= buttonParameters.length
                    || buttonParameters[buttonEnumIndex] != reasoningEnumClass) {
                throw new IllegalArgumentException("buttonEnumIndex");
            }
            int menuEnumMatches = 0;
            for (Class<?> parameter : menuMethod.getParameterTypes()) {
                if (parameter == reasoningEnumClass) {
                    menuEnumMatches++;
                }
            }
            if (menuEnumMatches != 1) {
                throw new IllegalArgumentException("menuMethod");
            }
            this.labels = Collections.unmodifiableMap(
                    new HashMap<Integer, String>(labels));
            this.reasoningEnumClass = reasoningEnumClass;
            this.buttonEnumIndex = buttonEnumIndex;
        }
    }

    public static final class StreamLifecycleCapability {
        public final Method starter;
        public final List<Method> cleanupMethods;
        public final Class<?> callbackType;

        public StreamLifecycleCapability(
                Method starter,
                List<Method> cleanupMethods,
                Class<?> callbackType) {
            if (callbackType == null || callbackType.isPrimitive()
                    || !isStarter(starter, callbackType)) {
                throw new IllegalArgumentException("starter");
            }
            if (cleanupMethods == null || cleanupMethods.size() != 2) {
                throw new IllegalArgumentException("cleanupMethods");
            }
            List<Method> copy = new ArrayList<Method>(cleanupMethods.size());
            for (Method cleanupMethod : cleanupMethods) {
                if (!isCleanup(starter, cleanupMethod)
                        || containsMethodIdentity(copy, cleanupMethod)) {
                    throw new IllegalArgumentException("cleanupMethods");
                }
                copy.add(cleanupMethod);
            }
            this.starter = starter;
            this.cleanupMethods = Collections.unmodifiableList(copy);
            this.callbackType = callbackType;
        }

        private static boolean isStarter(Method method, Class<?> callbackType) {
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

        private static boolean isCleanup(Method starter, Method method) {
            return starter != null && method != null
                    && method.getDeclaringClass() == starter.getDeclaringClass()
                    && !Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == void.class
                    && method.getParameterTypes().length == 0;
        }

        private static boolean containsMethodIdentity(
                List<Method> methods, Method candidate) {
            for (Method method : methods) {
                if (method == candidate || (method != null && method.equals(candidate))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Method requireMethod(Method method, String name) {
        if (method == null) {
            throw new IllegalArgumentException(name);
        }
        return method;
    }
}
