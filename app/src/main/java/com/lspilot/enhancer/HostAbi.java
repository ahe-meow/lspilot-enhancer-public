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

        public ReasoningCapability(Method getter, Constructor<?> repositoryConstructor) {
            this.getter = requireMethod(getter, "getter");
            if (repositoryConstructor == null) {
                throw new IllegalArgumentException("repositoryConstructor");
            }
            this.repositoryConstructor = repositoryConstructor;
        }
    }

    public static final class MenuCapability {
        public final Method labelResolver;
        public final Map<Integer, String> labels;
        public final String menuOwner;
        public final String menuMethod;
        public final String buttonMethod;

        public MenuCapability(
                Method labelResolver,
                Map<Integer, String> labels,
                String menuOwner,
                String menuMethod,
                String buttonMethod) {
            this.labelResolver = requireMethod(labelResolver, "labelResolver");
            if (labels == null
                    || labels.size() != ReasoningPolicy.SUPPORTED.length + 1) {
                throw new IllegalArgumentException("labels");
            }
            if (menuOwner == null || menuOwner.isEmpty()) {
                throw new IllegalArgumentException("menuOwner");
            }
            if (menuMethod == null || menuMethod.isEmpty()) {
                throw new IllegalArgumentException("menuMethod");
            }
            if (buttonMethod == null || buttonMethod.isEmpty()) {
                throw new IllegalArgumentException("buttonMethod");
            }
            this.labels = Collections.unmodifiableMap(
                    new HashMap<Integer, String>(labels));
            this.menuOwner = menuOwner;
            this.menuMethod = menuMethod;
            this.buttonMethod = buttonMethod;
        }
    }

    private static Method requireMethod(Method method, String name) {
        if (method == null) {
            throw new IllegalArgumentException(name);
        }
        return method;
    }
}
