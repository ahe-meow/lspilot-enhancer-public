package com.lspilot.enhancer;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Owns the process-local request lease for the host foreground service. */
public final class HostForegroundKeepAliveController {
    private static final String HOST_PACKAGE = "me.yun.lspilot";
    private static final String SESSION_SERVICE_CLASS =
            "com.rk.terminal.SessionService";
    private static final Object LOCK = new Object();
    private static final ReferenceCountPolicy REFERENCES = new ReferenceCountPolicy();
    private static final ConnectionRecoveryPolicy CONNECTIONS =
            new ConnectionRecoveryPolicy();
    private static Context bindingContext;
    private static ServiceConnection serviceConnection;

    private HostForegroundKeepAliveController() {
    }

    public static boolean acquire() {
        return acquire(resolveHostContext());
    }

    public static void release() {
        release(null);
    }

    static boolean acquire(Context hostContext) {
        if (hostContext == null) {
            return false;
        }
        synchronized (LOCK) {
            int previousCount = REFERENCES.count();
            if (previousCount != 0 && !CONNECTIONS.needsBinding()) {
                return REFERENCES.acquire();
            }
            Context startedContext = resolveServiceContext(hostContext);
            if (startedContext == null || !startBindingLocked(startedContext)) {
                return false;
            }
            if (!REFERENCES.acquire()) {
                releaseBindingLocked();
                return false;
            }
            return true;
        }
    }

    static void release(Context hostContext) {
        synchronized (LOCK) {
            if (!REFERENCES.release()) {
                return;
            }
            releaseBindingLocked();
        }
    }

    private static boolean startBindingLocked(Context startedContext) {
        ServiceConnection startedConnection = new HostServiceConnection();
        boolean bound;
        try {
            bound = startedContext.bindService(
                    serviceIntent(),
                    startedConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        } catch (Throwable ignored) {
            return false;
        }
        if (!bound) {
            return false;
        }
        bindingContext = startedContext;
        serviceConnection = startedConnection;
        CONNECTIONS.bindingStarted();
        return true;
    }

    private static void handleBindingLost(ServiceConnection lostConnection) {
        synchronized (LOCK) {
            if (lostConnection == null || lostConnection != serviceConnection) {
                return;
            }
            Context retryContext = bindingContext;
            unbindQuietly(retryContext, lostConnection);
            bindingContext = null;
            serviceConnection = null;
            boolean retry = CONNECTIONS.bindingLost(REFERENCES.count());
            if (retry && retryContext != null) {
                startBindingLocked(retryContext);
            }
        }
    }

    private static void releaseBindingLocked() {
        Context unbindContext = bindingContext;
        ServiceConnection unbindConnection = serviceConnection;
        bindingContext = null;
        serviceConnection = null;
        CONNECTIONS.bindingReleased();
        if (unbindContext != null && unbindConnection != null) {
            unbindQuietly(unbindContext, unbindConnection);
        }
    }

    private static Intent serviceIntent() {
        return new Intent().setComponent(
                new ComponentName(HOST_PACKAGE, SESSION_SERVICE_CLASS));
    }

    private static void unbindQuietly(
            Context context,
            ServiceConnection connection) {
        if (context == null || connection == null) {
            return;
        }
        try {
            context.unbindService(connection);
        } catch (Throwable ignored) {
            // The host may have already removed a dead binding.
        }
    }

    private static Application resolveHostContext() {
        Application application = invokeApplication(
                "android.app.AppGlobals", "getInitialApplication");
        if (application != null) {
            return application;
        }
        return invokeApplication("android.app.ActivityThread", "currentApplication");
    }

    private static Application invokeApplication(String ownerName, String methodName) {
        try {
            Class<?> owner = Class.forName(ownerName);
            Method method;
            try {
                method = owner.getMethod(methodName);
            } catch (NoSuchMethodException ignored) {
                method = owner.getDeclaredMethod(methodName);
                if (!Modifier.isPublic(method.getModifiers())
                        || !Modifier.isPublic(owner.getModifiers())) {
                    method.setAccessible(true);
                }
            }
            Object value = method.invoke(null);
            return value instanceof Application ? (Application) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context resolveServiceContext(Context hostContext) {
        try {
            Context applicationContext = hostContext.getApplicationContext();
            if (applicationContext != null) {
                return applicationContext;
            }
        } catch (Throwable ignored) {
            // Use the Application fallback below.
        }
        return hostContext instanceof Application ? (Application) hostContext : null;
    }

    private static final class HostServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // The binding itself is the request-scoped foreground-service lease.
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Android retains this binding and reconnects it after a transient crash.
        }

        @Override
        public void onBindingDied(ComponentName name) {
            handleBindingLost(this);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            handleBindingLost(this);
        }
    }

    /** Pure binding-state policy for service-death recovery. */
    public static final class ConnectionRecoveryPolicy {
        private boolean bindingActive;

        public synchronized boolean needsBinding() {
            return !bindingActive;
        }

        public synchronized void bindingStarted() {
            bindingActive = true;
        }

        public synchronized void bindingReleased() {
            bindingActive = false;
        }

        public synchronized boolean bindingLost(int activeReferences) {
            bindingActive = false;
            return activeReferences > 0;
        }
    }

    /** Pure process-local reference counter; true means the release was accepted. */
    public static final class ReferenceCountPolicy {
        private int references;

        public synchronized boolean acquire() {
            if (references == Integer.MAX_VALUE) {
                return false;
            }
            references++;
            return true;
        }

        public synchronized boolean release() {
            if (references == 0) {
                return false;
            }
            references--;
            return references == 0;
        }

        public synchronized int count() {
            return references;
        }
    }
}
