package com.lspilot.enhancer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

/** Pure menu/button-label policy and the public install seam. */
public final class ReasoningMenuHook {
    private static final int UNKNOWN_LABEL_ORDINAL = -1;
    private static final int AMBIGUOUS_LABEL_ORDINAL = -2;

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
                || !hasSixLabels(capability.labels)
                || capability.menuMethod == null || capability.buttonMethod == null
                || capability.reasoningEnumClass == null
                || !capability.reasoningEnumClass.isEnum()) {
            return false;
        }
        Method resolver = capability.labelResolver;
        if (!Modifier.isStatic(resolver.getModifiers())
                || resolver.getReturnType() != String.class) {
            return false;
        }
        Class<?>[] resolverParameters = resolver.getParameterTypes();
        if (resolverParameters.length != 3
                || resolverParameters[0] != int.class
                || resolverParameters[2] != int.class
                || resolverParameters[1] == null
                || resolverParameters[1].isPrimitive()) {
            return false;
        }

        Method menu = capability.menuMethod;
        Method button = capability.buttonMethod;
        if (!Modifier.isStatic(menu.getModifiers())
                || !Modifier.isStatic(button.getModifiers())
                || button.getReturnType() != void.class
                || !hasExactlyOneEnumParameter(
                menu.getParameterTypes(), capability.reasoningEnumClass)
                || !hasExactlyOneEnumParameter(
                button.getParameterTypes(), capability.reasoningEnumClass)) {
            return false;
        }
        Class<?>[] buttonParameters = button.getParameterTypes();
        return capability.buttonEnumIndex >= 0
                && capability.buttonEnumIndex < buttonParameters.length
                && buttonParameters[capability.buttonEnumIndex]
                == capability.reasoningEnumClass;
    }

    private static boolean hasSixLabels(Map<Integer, String> labels) {
        if (labels == null || labels.size() != ReasoningPolicy.SUPPORTED.length) {
            return false;
        }
        for (String supported : ReasoningPolicy.SUPPORTED) {
            if (!labels.containsValue(supported)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasExactlyOneEnumParameter(
            Class<?>[] parameters, Class<?> enumClass) {
        if (parameters == null || enumClass == null) {
            return false;
        }
        int matches = 0;
        for (Class<?> parameter : parameters) {
            if (parameter == enumClass) {
                matches++;
            }
        }
        return matches == 1;
    }

    static String replacementFor(int resourceId, Map<Integer, String> labels) {
        if (labels == null) {
            return null;
        }
        return labels.get(Integer.valueOf(resourceId));
    }

    static boolean isTargetMenuCall(
            StackTraceElement[] trace, Method... targets) {
        if (trace == null || targets == null || targets.length == 0) {
            return false;
        }
        for (StackTraceElement element : trace) {
            if (element == null) {
                continue;
            }
            for (Method target : targets) {
                if (target != null
                        && target.getDeclaringClass() != null
                        && target.getDeclaringClass().getName().equals(
                        element.getClassName())
                        && target.getName().equals(element.getMethodName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Pure calibration transition for a single resolver call. */
    static CalibrationResult calibrate(
            String hostEnumName,
            int resourceId,
            String original,
            int ordinal,
            int learnedLabelOrdinal,
            Map<Integer, String> labels) {
        String replacement = replacementFor(resourceId, labels);
        int nextLabelOrdinal = learnedLabelOrdinal;
        boolean knownEnum = "OFF".equals(hostEnumName)
                || "AUTO".equals(hostEnumName)
                || "LOW".equals(hostEnumName)
                || "MEDIUM".equals(hostEnumName)
                || "HIGH".equals(hostEnumName)
                || "MAX".equals(hostEnumName);
        String expected = knownEnum
                ? ReasoningPolicy.fromHostEnumName(hostEnumName) : null;
        boolean off = "OFF".equals(hostEnumName);
        if (knownEnum && !off && ordinal >= 0 && replacement != null
                && expected.equals(replacement)) {
            if (learnedLabelOrdinal == UNKNOWN_LABEL_ORDINAL) {
                nextLabelOrdinal = ordinal;
            } else if (learnedLabelOrdinal >= 0
                    && learnedLabelOrdinal != ordinal) {
                nextLabelOrdinal = AMBIGUOUS_LABEL_ORDINAL;
            }
        }
        if (replacement == null && off
                && learnedLabelOrdinal >= 0
                && ordinal == learnedLabelOrdinal) {
            replacement = "off";
        }
        return new CalibrationResult(
                nextLabelOrdinal, replacement == null ? original : replacement);
    }

    static final class CalibrationResult {
        final int labelOrdinal;
        final String replacement;

        CalibrationResult(int labelOrdinal, String replacement) {
            this.labelOrdinal = labelOrdinal;
            this.replacement = replacement;
        }
    }
}

/** API 102 linkage is kept behind the pure menu/button-label test seam. */
final class ReasoningMenuHookApi {
    private ReasoningMenuHookApi() {
    }

    static void install(
            final HostAbi.MenuCapability capability,
            HookRegistry registry) {
        if (capability == null || registry == null) {
            return;
        }
        final CalibrationState state = new CalibrationState();
        io.github.libxposed.api.XposedInterface.HookHandle resolverHandle =
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
                                            capability.menuMethod, capability.buttonMethod)) {
                                        return original;
                                    }
                                    if (!(original instanceof String)) {
                                        return original;
                                    }
                                    String knownReplacement =
                                            ReasoningMenuHook.replacementFor(
                                                    ((Integer) resourceId).intValue(),
                                                    capability.labels);
                                    ButtonInvocationContext context =
                                            state.buttonContext.get();
                                    if (context == null) {
                                        return knownReplacement == null
                                                ? original : knownReplacement;
                                    }
                                    int ordinal = context.nextResolverOrdinal();
                                    ReasoningMenuHook.CalibrationResult result =
                                            ReasoningMenuHook.calibrate(
                                                    context.enumName,
                                                    ((Integer) resourceId).intValue(),
                                                    (String) original,
                                                    ordinal,
                                                    state.labelCallOrdinal,
                                                    capability.labels);
                                    state.labelCallOrdinal = result.labelOrdinal;
                                    return result.replacement;
                                } catch (Throwable ignored) {
                                    return original;
                                }
                            }
                        });
        if (resolverHandle == null) {
            return;
        }

        io.github.libxposed.api.XposedInterface.HookHandle buttonHandle =
                registry.installHook(capability.buttonMethod, "reasoning-menu-button",
                        new io.github.libxposed.api.XposedInterface.Hooker() {
                            @Override
                            public Object intercept(
                                    io.github.libxposed.api.XposedInterface.Chain chain)
                                    throws Throwable {
                                Object buttonEnum;
                                try {
                                    List<Object> args = chain.getArgs();
                                    if (args == null
                                            || capability.buttonEnumIndex < 0
                                            || capability.buttonEnumIndex >= args.size()) {
                                        return chain.proceed();
                                    }
                                    buttonEnum = args.get(capability.buttonEnumIndex);
                                } catch (Throwable ignored) {
                                    return chain.proceed();
                                }
                                if (!capability.reasoningEnumClass.isInstance(buttonEnum)) {
                                    return chain.proceed();
                                }

                                ButtonInvocationContext previous = state.buttonContext.get();
                                ButtonInvocationContext current =
                                        new ButtonInvocationContext(
                                                ((Enum<?>) buttonEnum).name());
                                state.buttonContext.set(current);
                                try {
                                    return chain.proceed();
                                } finally {
                                    if (previous == null) {
                                        state.buttonContext.remove();
                                    } else {
                                        state.buttonContext.set(previous);
                                    }
                                }
                            }
                        });
        if (buttonHandle == null) {
            registry.removeHook(resolverHandle);
        }
    }

    private static final class CalibrationState {
        volatile int labelCallOrdinal = -1;
        final ThreadLocal<ButtonInvocationContext> buttonContext =
                new ThreadLocal<ButtonInvocationContext>();
    }

    private static final class ButtonInvocationContext {
        final String enumName;
        private int resolverOrdinal = -1;

        ButtonInvocationContext(String enumName) {
            this.enumName = enumName;
        }

        int nextResolverOrdinal() {
            return ++resolverOrdinal;
        }
    }
}
