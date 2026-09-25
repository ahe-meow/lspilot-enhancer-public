package com.lspilot.enhancer;

import java.util.List;
import java.util.concurrent.TimeUnit;

final class NetworkStabilityPolicy {
    private NetworkStabilityPolicy() {
    }

    static Object intercept(
            Chain chain,
            InitializationScope scope,
            long minimumTimeout,
            long expectedTimeout,
            TimeUnit expectedUnit) throws Throwable {
        if (chain == null) {
            return null;
        }
        if (scope == null || !scope.isActive()) {
            return chain.proceed();
        }

        List<Object> currentArgs;
        try {
            currentArgs = chain.getArgs();
        } catch (Throwable ignored) {
            return chain.proceed();
        }

        boolean expectedShape;
        try {
            expectedShape = currentArgs != null && currentArgs.size() == 2
                    && currentArgs.get(0) instanceof Long
                    && currentArgs.get(1) == expectedUnit;
        } catch (Throwable ignored) {
            expectedShape = false;
        }
        if (!expectedShape) {
            return chain.proceed();
        }

        long requestedTimeout;
        try {
            requestedTimeout = ((Long) currentArgs.get(0)).longValue();
        } catch (Throwable ignored) {
            return chain.proceed();
        }
        if (requestedTimeout != expectedTimeout
                || minimumTimeout <= expectedTimeout) {
            return chain.proceed();
        }

        Object[] forwardedArgs;
        try {
            forwardedArgs = currentArgs.toArray(new Object[2]);
            forwardedArgs[0] = Long.valueOf(minimumTimeout);
        } catch (Throwable ignored) {
            return chain.proceed();
        }
        return chain.proceed(forwardedArgs);
    }

    static Object intercept(
            Chain chain,
            long minimumTimeout,
            long expectedTimeout,
            TimeUnit expectedUnit) throws Throwable {
        return intercept(chain, null, minimumTimeout, expectedTimeout, expectedUnit);
    }

    static final class InitializationScope {
        private final ThreadLocal<Integer> depth = new ThreadLocal<Integer>();

        void enter() {
            Integer current = depth.get();
            depth.set(Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }

        void exit() {
            Integer current = depth.get();
            if (current == null || current.intValue() <= 1) {
                depth.remove();
            } else {
                depth.set(Integer.valueOf(current.intValue() - 1));
            }
        }

        boolean isActive() {
            Integer current = depth.get();
            return current != null && current.intValue() > 0;
        }
    }

    interface Chain {
        List<Object> getArgs();

        Object proceed() throws Throwable;

        Object proceed(Object[] args) throws Throwable;
    }
}
