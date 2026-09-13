package com.lspilot.enhancer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Applies the current host reasoning policy at one provider JSON seam. */
public final class RequestPolicyHook {
    private RequestPolicyHook() {
    }

    /** Installs one API 102 hook for the supplied provider capability. */
    public static void install(
            final HostAbi.RequestCapability capability,
            final HostAbi.ReasoningCapability reasoningCapability,
            HookRegistry registry) {
        if (capability == null || reasoningCapability == null
                || registry == null || registry.isClosed()) {
            return;
        }
        if (!isInstallable(capability) || !isReadable(reasoningCapability)) {
            return;
        }
        RequestPolicyHookApi.install(capability, reasoningCapability, registry);
    }

    static boolean isInstallable(HostAbi.RequestCapability capability) {
        if (capability == null || capability.method == null || capability.providerKind == null
                || capability.providerKind == RequestJsonPolicy.ProviderKind.UNKNOWN) {
            return false;
        }
        if (Modifier.isStatic(capability.method.getModifiers())
                || capability.method.getReturnType() != String.class) {
            return false;
        }

        String[] expected = capability.providerKind == RequestJsonPolicy.ProviderKind.GENERIC
                ? new String[]{
                        "vb", "java.util.List", "java.lang.String", "boolean",
                        "java.lang.String", "a69"}
                : new String[]{
                        "vb", "java.util.List", "java.lang.String", "a69"};
        Class<?>[] actual = capability.method.getParameterTypes();
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (!expected[i].equals(actual[i].getName())) {
                return false;
            }
        }
        return true;
    }

    static boolean isReadable(HostAbi.ReasoningCapability capability) {
        if (capability == null || capability.getter == null
                || capability.repositoryConstructor == null) {
            return false;
        }
        return !Modifier.isStatic(capability.getter.getModifiers())
                && capability.getter.getReturnType() == String.class
                && capability.getter.getParameterTypes().length == 0
                && capability.repositoryConstructor.getParameterTypes().length == 0
                && capability.getter.getDeclaringClass()
                .equals(capability.repositoryConstructor.getDeclaringClass());
    }

    /** Package-visible seam that keeps the focused check independent of API runtime state. */
    static Object intercept(
            Chain chain,
            HostAbi.ReasoningCapability reasoningCapability,
            RequestJsonPolicy.ProviderKind provider) throws Throwable {
        if (chain == null) {
            return null;
        }
        Object original = chain.proceed();
        if (!(original instanceof String) || !isReadable(reasoningCapability)) {
            return original;
        }
        try {
            String policy = readHostPolicy(reasoningCapability);
            if (policy == null) {
                return original;
            }
            return RequestJsonPolicy.rewrite((String) original, provider, policy);
        } catch (Throwable ignored) {
            return original;
        }
    }

    private static String readHostPolicy(HostAbi.ReasoningCapability capability)
            throws Throwable {
        Constructor<?> constructor = capability.repositoryConstructor;
        constructor.setAccessible(true);
        Object repository = constructor.newInstance();

        Method getter = capability.getter;
        getter.setAccessible(true);
        Object value = getter.invoke(repository);
        return ReasoningPolicy.fromHostEnumName(value instanceof String ? (String) value : null);
    }

    interface Chain {
        Object proceed() throws Throwable;
    }
}

/** API 102 linkage is kept behind the pure request-policy test seam. */
final class RequestPolicyHookApi {
    private RequestPolicyHookApi() {
    }

    static void install(
            final HostAbi.RequestCapability capability,
            final HostAbi.ReasoningCapability reasoningCapability,
            HookRegistry registry) {
        String id = "request-policy-" + capability.providerKind.name();
        registry.installHook(capability.method, id,
                new io.github.libxposed.api.XposedInterface.Hooker() {
                    @Override
                    public Object intercept(
                            final io.github.libxposed.api.XposedInterface.Chain chain)
                            throws Throwable {
                        return RequestPolicyHook.intercept(new RequestPolicyHook.Chain() {
                            @Override
                            public Object proceed() throws Throwable {
                                return chain.proceed();
                            }
                        }, reasoningCapability, capability.providerKind);
                    }
                });
    }
}
