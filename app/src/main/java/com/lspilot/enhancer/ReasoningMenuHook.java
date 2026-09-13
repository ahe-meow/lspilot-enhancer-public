package com.lspilot.enhancer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/** Pure menu/button-label policy and the public install seam. */
public final class ReasoningMenuHook {
    private ReasoningMenuHook() {
    }

    public static void install(HostAbi.MenuCapability capability, HookRegistry registry) {
        if (!isInstallable(capability) || registry == null || registry.isClosed()) {
            return;
        }
        ReasoningMenuHookApi.install(capability, registry);
    }

    static boolean isInstallable(HostAbi.MenuCapability capability) {
        if (capability == null || capability.labelResolver == null
                || capability.labels == null
                || capability.labels.size() != ReasoningPolicy.SUPPORTED.length + 1
                || capability.buttonMethod == null
                || capability.buttonMethod.isEmpty()) {
            return false;
        }
        Method method = capability.labelResolver;
        if (!Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != String.class) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length == 3
                && parameters[0] == int.class
                && parameters[2] == int.class
                && "id2".equals(parameters[1].getName());
    }

    static String replacementFor(int resourceId, Map<Integer, String> labels) {
        if (labels == null) {
            return null;
        }
        return labels.get(Integer.valueOf(resourceId));
    }

    static boolean isTargetMenuCall(
            StackTraceElement[] trace, String owner, String... methods) {
        if (trace == null || owner == null || owner.isEmpty()
                || methods == null || methods.length == 0) {
            return false;
        }
        for (StackTraceElement element : trace) {
            if (element == null || !owner.equals(element.getClassName())) {
                continue;
            }
            for (String method : methods) {
                if (method != null && method.equals(element.getMethodName())) {
                    return true;
                }
            }
        }
        return false;
    }
}

/** API 102 linkage is kept behind the pure menu/button-label test seam. */
final class ReasoningMenuHookApi {
    private ReasoningMenuHookApi() {
    }

    static void install(
            final HostAbi.MenuCapability capability,
            HookRegistry registry) {
        registry.installHook(capability.labelResolver, "reasoning-menu-labels",
                new io.github.libxposed.api.XposedInterface.Hooker() {
                    @Override
                    public Object intercept(
                            io.github.libxposed.api.XposedInterface.Chain chain)
                            throws Throwable {
                        Object original = chain.proceed();
                        try {
                            Object resourceId = chain.getArg(0);
                            if (!(resourceId instanceof Integer)
                                    || !ReasoningMenuHook.isTargetMenuCall(
                                    Thread.currentThread().getStackTrace(),
                                    capability.menuOwner,
                                    capability.menuMethod,
                                    capability.buttonMethod)) {
                                return original;
                            }
                            String replacement = ReasoningMenuHook.replacementFor(
                                    ((Integer) resourceId).intValue(), capability.labels);
                            return replacement == null ? original : replacement;
                        } catch (Throwable ignored) {
                            return original;
                        }
                    }
                });
    }
}
