package com.lspilot.enhancer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable reflection-only handles for independently resolved host seams. */
public final class HostAbi {
    public final ReasoningCapability reasoningSource;
    public final MenuCapability menuLabels;
    public final RequestCapability genericRequest;
    public final RequestCapability thinkingRequest;

    public HostAbi(
            ReasoningCapability reasoningSource,
            MenuCapability menuLabels,
            RequestCapability genericRequest,
            RequestCapability thinkingRequest) {
        this.reasoningSource = reasoningSource;
        this.menuLabels = menuLabels;
        this.genericRequest = genericRequest;
        this.thinkingRequest = thinkingRequest;
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

    private static Method requireMethod(Method method, String name) {
        if (method == null) {
            throw new IllegalArgumentException(name);
        }
        return method;
    }
}
