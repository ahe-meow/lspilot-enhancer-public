package com.lspilot.enhancer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/** Installs the request-scoped stream starter and cleanup hooks. */
public final class StreamLifecycleHook {
    private StreamLifecycleHook() {
    }

    public static void install(
            final HostAbi.StreamLifecycleCapability capability,
            final HookRegistry registry) {
        List<Method> order = StreamLifecyclePolicy.installationOrder(capability);
        if (registry == null || registry.isClosed() || order.size() != 3) {
            return;
        }

        final StreamLifecyclePolicy.AcquisitionLedger ledger =
                new StreamLifecyclePolicy.AcquisitionLedger();
        final Runnable releaseLease = new Runnable() {
            @Override
            public void run() {
                HostForegroundKeepAliveController.release();
            }
        };
        if (!registry.addCloseResource(ledger, new Runnable() {
            @Override
            public void run() {
                ledger.close(releaseLease);
            }
        })) {
            return;
        }

        List<XposedInterface.HookHandle> installed =
                new ArrayList<XposedInterface.HookHandle>();
        boolean installedCompletely = false;
        try {
            for (int index = 0; index < order.size() - 1; index++) {
                final Method cleanupMethod = order.get(index);
                XposedInterface.HookHandle cleanupHandle = registry.installHook(
                        cleanupMethod,
                        "stream-lifecycle-cleanup-" + index,
                        new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(final XposedInterface.Chain chain)
                                    throws Throwable {
                                return StreamLifecyclePolicy.interceptCleanup(
                                        chain.getThisObject(),
                                        new StreamLifecyclePolicy.Chain() {
                                            @Override
                                            public Object proceed() throws Throwable {
                                                return chain.proceed();
                                            }
                                        },
                                        ledger,
                                        releaseLease);
                            }
                        });
                if (cleanupHandle == null) {
                    return;
                }
                installed.add(cleanupHandle);
            }

            XposedInterface.HookHandle starterHandle = registry.installHook(
                    order.get(order.size() - 1),
                    "stream-lifecycle-starter",
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(final XposedInterface.Chain chain)
                                throws Throwable {
                            return StreamLifecyclePolicy.interceptStarter(
                                    chain.getThisObject(),
                                    new StreamLifecyclePolicy.Chain() {
                                        @Override
                                        public Object proceed() throws Throwable {
                                            return chain.proceed();
                                        }
                                    },
                                    new StreamLifecyclePolicy.Acquirer() {
                                        @Override
                                        public boolean acquire() {
                                            return HostForegroundKeepAliveController.acquire();
                                        }
                                    },
                                    releaseLease,
                                    ledger);
                        }
                    });
            if (starterHandle == null) {
                return;
            }
            installed.add(starterHandle);
            installedCompletely = true;
        } catch (Throwable ignored) {
            // The finally block closes the ledger and rolls back partial hooks.
        } finally {
            if (!installedCompletely) {
                registry.removeCloseResource(ledger);
                rollback(registry, installed);
            }
        }
    }

    private static void rollback(
            HookRegistry registry,
            List<XposedInterface.HookHandle> installed) {
        if (registry == null || installed == null) {
            return;
        }
        for (int index = installed.size() - 1; index >= 0; index--) {
            try {
                registry.removeHook(installed.get(index));
            } catch (Throwable ignored) {
                // Registry cleanup remains best effort during a failed install.
            }
        }
    }
}
