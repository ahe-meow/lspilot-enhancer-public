package com.lspilot.enhancer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;

/** Keeps the host streaming read timeout above its short provider default. */
public final class NetworkStabilityHook {
    static final long DEFAULT_MINIMUM_TIMEOUT_SECONDS = 600L;

    private NetworkStabilityHook() {
    }

    static InstallOutcome install(
            final HostAbi.NetworkCapability capability,
            HookRegistry registry) {
        if (capability == null || registry == null) {
            return new InstallOutcome(false, false);
        }

        final NetworkStabilityPolicy.InitializationScope scope =
                new NetworkStabilityPolicy.InitializationScope();
        boolean initializerInstalled =
                installInitializerHook(capability.initializerClass, scope, registry);
        XposedInterface.HookHandle setterHandle = registry.installHook(
                capability.readTimeoutSetter,
                "network-stream-read-timeout",
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(final XposedInterface.Chain chain) throws Throwable {
                        return NetworkStabilityPolicy.intercept(
                                new NetworkStabilityPolicy.Chain() {
                                    @Override
                                    public List<Object> getArgs() {
                                        return chain.getArgs();
                                    }

                                    @Override
                                    public Object proceed() throws Throwable {
                                        return chain.proceed();
                                    }

                                    @Override
                                    public Object proceed(Object[] args) throws Throwable {
                                        return chain.proceed(args);
                                    }
                                },
                                scope,
                                capability.minimumTimeout,
                                capability.expectedTimeout,
                                capability.expectedUnit);
                    }
                });
        boolean setterInstalled = setterHandle != null;
        DebugLogger.capability("network",
                initializerInstalled ? "initializer_installed" : "initializer_failed");
        DebugLogger.capability("network",
                setterInstalled ? "setter_installed" : "setter_failed");
        InstallOutcome outcome = new InstallOutcome(initializerInstalled, setterInstalled);
        DebugLogger.capability("network",
                protectionEvent(initializerInstalled, setterInstalled));
        return outcome;
    }

    static boolean hasFullProtection(boolean initializerInstalled, boolean setterInstalled) {
        return NetworkStabilityInstallPolicy.hasFullProtection(
                initializerInstalled, setterInstalled);
    }

    static String protectionEvent(boolean initializerInstalled, boolean setterInstalled) {
        return NetworkStabilityInstallPolicy.protectionEvent(initializerInstalled, setterInstalled);
    }

    static final class InstallOutcome {
        final boolean initializerInstalled;
        final boolean setterInstalled;

        InstallOutcome(boolean initializerInstalled, boolean setterInstalled) {
            this.initializerInstalled = initializerInstalled;
            this.setterInstalled = setterInstalled;
        }

        boolean isFullyProtected() {
            return NetworkStabilityInstallPolicy.hasFullProtection(
                    initializerInstalled, setterInstalled);
        }
    }

    private static boolean installInitializerHook(
            Class<?> initializerClass,
            final NetworkStabilityPolicy.InitializationScope scope,
            HookRegistry registry) {
        if (initializerClass == null || scope == null || registry == null) {
            return false;
        }
        XposedInterface xposed = registry.getXposedInterface();
        if (xposed == null) {
            return false;
        }
        try {
            XposedInterface.HookBuilder builder = xposed.hookClassInitializer(initializerClass);
            if (builder == null) {
                return false;
            }
            builder = builder.setId("network-initializer-scope");
            if (builder == null) {
                return false;
            }
            builder = builder.setExceptionMode(XposedInterface.ExceptionMode.DEFAULT);
            if (builder == null) {
                return false;
            }
            XposedInterface.HookHandle handle = builder.intercept(
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            scope.enter();
                            try {
                                return chain.proceed();
                            } finally {
                                scope.exit();
                            }
                        }
                    });
            if (handle == null) {
                return false;
            }
            registry.add(handle);
            return true;
        } catch (Throwable ignored) {
            // Fail open if the framework rejects this optional initializer hook.
            return false;
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
}

final class NetworkStabilityInstallPolicy {
    private NetworkStabilityInstallPolicy() {
    }

    static boolean hasFullProtection(boolean initializerInstalled, boolean setterInstalled) {
        return initializerInstalled && setterInstalled;
    }

    static String protectionEvent(boolean initializerInstalled, boolean setterInstalled) {
        if (hasFullProtection(initializerInstalled, setterInstalled)) {
            return "installed";
        }
        if (setterInstalled && !initializerInstalled) {
            return "scope_unavailable";
        }
        return "disabled";
    }
}
