package com.lspilot.enhancer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/** Pure exception/return-value policy for request-scoped stream lifecycle hooks. */
final class StreamLifecyclePolicy {
    private StreamLifecyclePolicy() {
    }

    static List<Method> installationOrder(
            HostAbi.StreamLifecycleCapability capability) {
        if (capability == null || capability.starter == null
                || capability.cleanupMethods == null
                || capability.cleanupMethods.size() != 2) {
            return Collections.emptyList();
        }
        List<Method> order = new ArrayList<Method>(3);
        order.addAll(capability.cleanupMethods);
        order.add(capability.starter);
        return Collections.unmodifiableList(order);
    }

    static Object interceptStarter(
            Chain chain,
            Acquirer acquirer,
            Runnable release) throws Throwable {
        if (chain == null) {
            return null;
        }
        boolean acquired = false;
        boolean completed = false;
        try {
            if (acquirer != null) {
                try {
                    acquired = acquirer.acquire();
                } catch (Throwable ignored) {
                    // Keep-alive acquisition must never replace host behavior.
                }
            }
            Object result = chain.proceed();
            completed = true;
            return result;
        } finally {
            if (!completed && acquired) {
                swallowRelease(release);
            }
        }
    }

    static Object interceptStarter(
            Object owner,
            Chain chain,
            Acquirer acquirer,
            Runnable release,
            AcquisitionLedger ledger) throws Throwable {
        if (chain == null) {
            return null;
        }
        AcquisitionLedger.LeaseToken token = ledger == null
                ? null
                : ledger.begin(owner);
        boolean acquired = false;
        boolean completed = false;
        try {
            if (token != null && acquirer != null) {
                try {
                    acquired = acquirer.acquire();
                } catch (Throwable ignored) {
                    // Keep-alive acquisition must never replace host behavior.
                }
            }
            if (token != null && ledger.complete(token, acquired)) {
                swallowRelease(release);
            }
            Object result = chain.proceed();
            completed = true;
            return result;
        } finally {
            if (!completed && token != null && ledger.rollback(token)) {
                swallowRelease(release);
            }
        }
    }

    static Object interceptCleanup(Chain chain, Runnable release) throws Throwable {
        try {
            return chain == null ? null : chain.proceed();
        } finally {
            swallowRelease(release);
        }
    }

    static Object interceptCleanup(
            Object owner,
            Chain chain,
            AcquisitionLedger ledger,
            Runnable release) throws Throwable {
        try {
            return chain == null ? null : chain.proceed();
        } finally {
            if (ledger != null && ledger.consume(owner)) {
                swallowRelease(release);
            }
        }
    }

    private static void swallowRelease(Runnable release) {
        if (release == null) {
            return;
        }
        try {
            release.run();
        } catch (Throwable ignored) {
            // A cleanup failure must not replace the host result or exception.
        }
    }

    /** Identity-keyed owner leases with exactly-once cleanup and close-time draining. */
    static final class AcquisitionLedger {
        private static final int PENDING = 0;
        private static final int ACTIVE = 1;
        private static final int RELEASED = 2;
        private static final int UNOWNED = 3;

        private final IdentityHashMap<Object, LeaseToken> activeByOwner =
                new IdentityHashMap<Object, LeaseToken>();
        private boolean closed;

        synchronized LeaseToken begin(Object owner) {
            if (closed || owner == null) {
                return null;
            }
            return new LeaseToken(owner);
        }

        /** Returns true when one acquired lease must be released outside the lock. */
        synchronized boolean complete(LeaseToken token, boolean owned) {
            if (token == null || token.state != PENDING) {
                return false;
            }
            if (!owned) {
                token.state = UNOWNED;
                return false;
            }
            if (closed) {
                token.state = RELEASED;
                return true;
            }
            LeaseToken displaced = activeByOwner.put(token.owner, token);
            token.state = ACTIVE;
            if (displaced == null || displaced.state != ACTIVE) {
                return false;
            }
            displaced.state = RELEASED;
            return true;
        }

        synchronized boolean consume(Object owner) {
            if (owner == null) {
                return false;
            }
            LeaseToken token = activeByOwner.remove(owner);
            if (token == null || token.state != ACTIVE) {
                return false;
            }
            token.state = RELEASED;
            return true;
        }

        synchronized boolean rollback(LeaseToken token) {
            if (token == null || token.state != ACTIVE
                    || activeByOwner.get(token.owner) != token) {
                return false;
            }
            activeByOwner.remove(token.owner);
            token.state = RELEASED;
            return true;
        }

        void close(Runnable release) {
            int releases = 0;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                for (LeaseToken token : activeByOwner.values()) {
                    if (token != null && token.state == ACTIVE) {
                        token.state = RELEASED;
                        releases++;
                    }
                }
                activeByOwner.clear();
            }
            for (int index = 0; index < releases; index++) {
                swallowRelease(release);
            }
        }

        private static final class LeaseToken {
            private final Object owner;
            private int state = PENDING;

            private LeaseToken(Object owner) {
                this.owner = owner;
            }
        }
    }

    interface Chain {
        Object proceed() throws Throwable;
    }

    interface Acquirer {
        boolean acquire();
    }
}
