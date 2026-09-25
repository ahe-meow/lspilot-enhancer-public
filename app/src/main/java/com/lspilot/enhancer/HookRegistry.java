package com.lspilot.enhancer;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/** Owns API 102 hook handles and lifecycle resources for one host process. */
public final class HookRegistry {
    private static final String LOG_TAG = "LSPilot";

    private final Object lock = new Object();
    private final XposedInterface xposedInterface;
    private final List<CloseResource> closeResources =
            new ArrayList<CloseResource>();
    private final IdentityHashMap<Object, CloseResource> closeResourcesByIdentity =
            new IdentityHashMap<Object, CloseResource>();
    private final List<HookResource> hookResources = new ArrayList<HookResource>();
    private final IdentityHashMap<Object, Boolean> hookIdentities =
            new IdentityHashMap<Object, Boolean>();
    private final IdentityHashMap<Object, HookResource> hookResourcesByIdentity =
            new IdentityHashMap<Object, HookResource>();
    private boolean closed;

    public HookRegistry() {
        this(null);
    }

    public HookRegistry(XposedInterface xposedInterface) {
        this.xposedInterface = xposedInterface;
    }

    XposedInterface getXposedInterface() {
        return xposedInterface;
    }

    /** Installs transparent, state-transition-only keep-alive diagnostics. */
    boolean installKeepAliveDiagnostics() {
        if (xposedInterface == null || isClosed()) {
            return false;
        }
        final KeepAliveDiagnostics diagnostics = new KeepAliveDiagnostics();
        boolean installed = false;
        try {
            installed |= installHook(
                    HostForegroundKeepAliveController.ReferenceCountPolicy.class
                            .getDeclaredMethod("acquire"),
                    "keep-alive-acquire",
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            diagnostics.acquired(chain.getThisObject(), result);
                            return result;
                        }
                    }) != null;
        } catch (Throwable ignored) {
            // Diagnostics must not affect capability installation.
        }
        try {
            installed |= installHook(
                    HostForegroundKeepAliveController.ReferenceCountPolicy.class
                            .getDeclaredMethod("release"),
                    "keep-alive-release",
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            diagnostics.released(result);
                            return result;
                        }
                    }) != null;
        } catch (Throwable ignored) {
            // Diagnostics must not affect capability installation.
        }
        try {
            installed |= installHook(
                    HostForegroundKeepAliveController.class.getDeclaredMethod(
                            "startBindingLocked", android.content.Context.class),
                    "keep-alive-binding",
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            diagnostics.bound(result);
                            return result;
                        }
                    }) != null;
        } catch (Throwable ignored) {
            // Diagnostics must not affect capability installation.
        }
        return installed;
    }

    /** Takes ownership of a framework hook handle. */
    public void add(final XposedInterface.HookHandle handle) {
        if (handle == null) {
            return;
        }
        addHookResource(handle, new HookResource() {
            @Override
            public void unhook() {
                handle.unhook();
            }
        });
    }

    /** Runs lifecycle work while the registry remains open. */
    boolean runIfOpen(Runnable action) {
        if (action == null) {
            return false;
        }
        synchronized (lock) {
            if (closed) {
                return false;
            }
            action.run();
            return !closed;
        }
    }

    /**
     * Adds work that must disable and drain state before hooks are removed.
     * Returns true only when the open registry takes ownership.
     */
    boolean addCloseResource(Object identity, Runnable closeAction) {
        if (identity == null || closeAction == null) {
            return false;
        }
        CloseResource resource = new CloseResource(closeAction);
        boolean closeImmediately = false;
        synchronized (lock) {
            if (closeResourcesByIdentity.containsKey(identity)) {
                return false;
            }
            if (closed) {
                closeImmediately = true;
            } else {
                closeResourcesByIdentity.put(identity, resource);
                closeResources.add(resource);
            }
        }
        if (closeImmediately) {
            closeQuietly(resource, "late-resource-close");
            return false;
        }
        return true;
    }

    /** Removes and closes one lifecycle resource during failed installation. */
    boolean removeCloseResource(Object identity) {
        if (identity == null) {
            return false;
        }
        CloseResource resource;
        synchronized (lock) {
            if (closed) {
                return false;
            }
            resource = closeResourcesByIdentity.remove(identity);
            if (resource == null) {
                return false;
            }
            closeResources.remove(resource);
        }
        closeQuietly(resource, "resource-close");
        return true;
    }

    /**
     * Installs one API 102 hook and records the returned handle. A null return
     * means the capability could not be installed in this process.
     */
    XposedInterface.HookHandle installHook(
            Method method, String id, XposedInterface.Hooker hooker) {
        if (method == null || hooker == null || isClosed()) {
            return null;
        }
        XposedInterface currentInterface = xposedInterface;
        if (currentInterface == null) {
            return null;
        }
        try {
            XposedInterface.HookBuilder builder = currentInterface.hook(method);
            if (builder == null) {
                return null;
            }
            if (id != null) {
                builder = builder.setId(id);
                if (builder == null) {
                    return null;
                }
            }
            builder = builder.setExceptionMode(XposedInterface.ExceptionMode.DEFAULT);
            if (builder == null) {
                return null;
            }
            XposedInterface.HookHandle handle = builder.intercept(hooker);
            if (handle != null) {
                add(handle);
            }
            return handle;
        } catch (Throwable exception) {
            logCleanupFailure("hook-install", exception);
            return null;
        }
    }

    private static final class KeepAliveDiagnostics {
        private boolean bindingObserved;

        synchronized void acquired(Object policy, Object result) {
            if (Boolean.TRUE.equals(result)
                    && policy instanceof HostForegroundKeepAliveController.ReferenceCountPolicy
                    && ((HostForegroundKeepAliveController.ReferenceCountPolicy) policy).count() == 1) {
                DebugLogger.capability("keepAlive", "acquire");
            }
        }

        synchronized void bound(Object result) {
            if (!Boolean.TRUE.equals(result)) {
                return;
            }
            String event = bindingObserved ? "rebind" : "bind";
            bindingObserved = true;
            DebugLogger.capability("keepAlive", event);
        }

        synchronized void released(Object result) {
            if (!Boolean.TRUE.equals(result)) {
                return;
            }
            bindingObserved = false;
            DebugLogger.capability("keepAlive", "release");
        }
    }

    /** Package-visible seam for JVM cleanup tests without the API AAR runtime. */
    void addForTest(final TestHookHandle handle) {
        if (handle == null) {
            return;
        }
        addHookResource(handle, new HookResource() {
            @Override
            public void unhook() {
                handle.unhook();
            }
        });
    }

    /** Package-visible seam for rolling back one capability installation. */
    boolean removeHook(Object identity) {
        if (identity == null) {
            return false;
        }
        HookResource resource;
        synchronized (lock) {
            if (closed) {
                return false;
            }
            resource = hookResourcesByIdentity.remove(identity);
            if (resource == null) {
                return false;
            }
            hookIdentities.remove(identity);
            hookResources.remove(resource);
        }
        unhookQuietly(resource, "hook-unhook");
        return true;
    }

    /** Disables/drains lifecycle resources, then releases every hook once. */
    public void close() {
        List<CloseResource> resources;
        List<HookResource> hooks;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            resources = new ArrayList<CloseResource>(closeResources);
            hooks = new ArrayList<HookResource>(hookResources);
        }

        for (CloseResource resource : resources) {
            closeQuietly(resource, "resource-close");
        }
        for (HookResource hook : hooks) {
            unhookQuietly(hook, "hook-unhook");
        }
        synchronized (lock) {
            closeResources.clear();
            closeResourcesByIdentity.clear();
            hookResources.clear();
            hookIdentities.clear();
            hookResourcesByIdentity.clear();
        }
    }

    boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    int resourceCount() {
        synchronized (lock) {
            return hookResources.size();
        }
    }

    private void addHookResource(Object identity, HookResource resource) {
        boolean releaseImmediately = false;
        synchronized (lock) {
            if (hookIdentities.containsKey(identity)) {
                return;
            }
            if (closed) {
                releaseImmediately = true;
            } else {
                hookIdentities.put(identity, Boolean.TRUE);
                hookResourcesByIdentity.put(identity, resource);
                hookResources.add(resource);
            }
        }
        if (releaseImmediately) {
            unhookQuietly(resource, "late-hook-unhook");
        }
    }

    private static void closeQuietly(CloseResource resource, String operation) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Throwable exception) {
            logCleanupFailure(operation, exception);
        }
    }

    private static void unhookQuietly(HookResource resource, String operation) {
        if (resource == null) {
            return;
        }
        try {
            resource.unhook();
        } catch (Throwable exception) {
            logCleanupFailure(operation, exception);
        }
    }

    private static void logCleanupFailure(String operation, Throwable exception) {
        String exceptionClass = exception == null
                ? "Unknown"
                : exception.getClass().getSimpleName();
        try {
            Log.w(LOG_TAG, operation + ":" + exceptionClass);
        } catch (Throwable ignored) {
            // Android logging can be unavailable in a local JVM test.
        }
    }

    private static final class CloseResource {
        private final Runnable closeAction;

        private CloseResource(Runnable closeAction) {
            this.closeAction = closeAction;
        }

        private void close() {
            closeAction.run();
        }
    }

    private interface HookResource {
        void unhook();
    }

    /** Package-visible test-only cleanup seam; it carries no host state. */
    interface TestHookHandle {
        void unhook();
    }
}
