package com.lspilot.enhancer;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/** Owns API 102 hook handles for one host process. */
public final class HookRegistry {
    private static final String LOG_TAG = "LSPilot";

    private final Object lock = new Object();
    private final XposedInterface xposedInterface;
    private final List<HookResource> hookResources = new ArrayList<HookResource>();
    private final IdentityHashMap<Object, Boolean> hookIdentities =
            new IdentityHashMap<Object, Boolean>();
    private boolean closed;

    public HookRegistry() {
        this(null);
    }

    public HookRegistry(XposedInterface xposedInterface) {
        this.xposedInterface = xposedInterface;
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

    /** Releases every owned hook exactly once. */
    public void close() {
        List<HookResource> hooks;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            hooks = new ArrayList<HookResource>(hookResources);
        }

        for (HookResource hook : hooks) {
            try {
                hook.unhook();
            } catch (Throwable exception) {
                logCleanupFailure("hook-unhook", exception);
            }
        }
        synchronized (lock) {
            hookResources.clear();
            hookIdentities.clear();
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
                hookResources.add(resource);
            }
        }
        if (releaseImmediately) {
            try {
                resource.unhook();
            } catch (Throwable exception) {
                logCleanupFailure("late-hook-unhook", exception);
            }
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

    private interface HookResource {
        void unhook();
    }

    /** Package-visible test-only cleanup seam; it carries no host state. */
    interface TestHookHandle {
        void unhook();
    }
}
