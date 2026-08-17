package com.lspilot.enhancer;

import android.content.pm.ApplicationInfo;

import java.lang.reflect.Method;
import java.util.List;

/** Host request/SSE ABI resolver for named debug builds and minified release builds. */
final class HostAbi {
    private static final String NAMED_PROVIDER =
            "me.yun.lspilot.data.provider.OpenAiApiProvider";
    private static final String NAMED_CONFIG =
            "me.yun.lspilot.data.model.AiProviderConfig";

    // Known minified profiles; the update path still runs structural DEX discovery first.
    private static final String MINIFIED_PROVIDER = "vj8";
    private static final String ALTERNATE_MINIFIED_PROVIDER = "xj8";
    private static final String CURRENT_MINIFIED_PROVIDER = "zj8";
    private static final String MINIFIED_CONFIG = "cb";

    final Class<?> providerClass;
    final Class<?> configClass;
    final boolean minified;
    final Method buildRequestMethod;
    final Method scanSseDataMethod;

    HostAbi(Class<?> providerClass, Class<?> configClass, boolean minified,
            Method buildRequestMethod, Method scanSseDataMethod) {
        this.providerClass = providerClass;
        this.configClass = configClass;
        this.minified = minified;
        this.buildRequestMethod = buildRequestMethod;
        this.scanSseDataMethod = scanSseDataMethod;
    }

    static HostAbi resolve(ClassLoader loader) throws Exception {
        return resolve(loader, null);
    }

    static HostAbi resolve(ClassLoader loader, String[] dexPaths) throws Exception {
        return resolveFresh(loader, dexPaths);
    }

    static HostAbi resolve(ClassLoader loader, String[] dexPaths, ApplicationInfo appInfo)
            throws Exception {
        return HostAbiCache.resolve(loader, dexPaths, appInfo);
    }

    static HostAbi resolveFresh(ClassLoader loader, String[] dexPaths) throws Exception {
        return resolveFresh(loader, dexPaths, false);
    }

    static HostAbi resolveFresh(ClassLoader loader, String[] dexPaths,
            boolean preferDexScan) throws Exception {
        Throwable dexScanError = null;
        if (preferDexScan && dexPaths != null && dexPaths.length > 0) {
            try {
                HostAbi scanned = DexAbiScanner.resolve(loader, dexPaths);
                DebugLogger.i("host update DEX self-adaptation resolved provider="
                        + scanned.providerClass.getName());
                return scanned;
            } catch (DexAbiScanner.AmbiguousRequestException error) {
                throw error;
            } catch (Throwable error) {
                dexScanError = error;
                DebugLogger.w("host update DEX self-adaptation failed; trying known profiles: "
                        + shortError(error));
            }
        }

        Throwable namedError;
        try {
            return resolveNamed(loader);
        } catch (Throwable error) {
            namedError = error;
        }

        try {
            HostAbi minified = resolveMinified(loader);
            DebugLogger.w("named host ABI unavailable; using known minified ABI: "
                    + shortError(namedError));
            return minified;
        } catch (Throwable minifiedError) {
            if (dexScanError == null && dexPaths != null && dexPaths.length > 0) {
                try {
                    HostAbi scanned = DexAbiScanner.resolve(loader, dexPaths);
                    DebugLogger.w("named/minified ABI unavailable; using structural DEX scan: "
                            + shortError(minifiedError));
                    return scanned;
                } catch (Throwable error) {
                    dexScanError = error;
                }
            }

            NoSuchMethodException combined = new NoSuchMethodException(
                    "host ABI unavailable; named=" + shortError(namedError)
                            + " minified=" + shortError(minifiedError)
                            + " dex=" + shortError(dexScanError));
            combined.initCause(dexScanError != null ? dexScanError : minifiedError);
            throw combined;
        }
    }

    boolean hasRequestAbi() {
        return buildRequestMethod != null && scanSseDataMethod != null;
    }

    private static HostAbi resolveNamed(ClassLoader loader) throws Exception {
        Class<?> providerClass = Class.forName(NAMED_PROVIDER, false, loader);
        Class<?> configClass = Class.forName(NAMED_CONFIG, false, loader);
        Class<?> function1Class = Class.forName(
                "kotlin.jvm.functions.Function1", false, loader);
        Method buildRequest = providerClass.getDeclaredMethod("buildOpenAiRequestBody",
                configClass, List.class, String.class, boolean.class);
        requireReturnType(buildRequest, String.class);
        Method scanSseData = providerClass.getDeclaredMethod(
                "scanSseData", String.class, function1Class);
        requireReturnType(scanSseData, boolean.class);
        return new HostAbi(providerClass, configClass, false,
                accessible(buildRequest), accessible(scanSseData));
    }

    private static HostAbi resolveMinified(ClassLoader loader) throws Exception {
        Throwable firstError;
        try {
            return resolveMinifiedProfile(loader, MINIFIED_PROVIDER);
        } catch (Throwable error) {
            firstError = error;
        }
        Throwable alternateError;
        try {
            return resolveMinifiedProfile(loader, ALTERNATE_MINIFIED_PROVIDER);
        } catch (Throwable error) {
            alternateError = error;
        }
        try {
            return resolveMinifiedProfile(loader, CURRENT_MINIFIED_PROVIDER);
        } catch (Throwable currentError) {
            currentError.addSuppressed(firstError);
            currentError.addSuppressed(alternateError);
            if (currentError instanceof Exception) throw (Exception) currentError;
            throw new Exception(currentError);
        }
    }

    private static HostAbi resolveMinifiedProfile(ClassLoader loader, String providerName)
            throws Exception {
        Class<?> providerClass = loader.loadClass(providerName);
        Class<?> configClass = Class.forName(MINIFIED_CONFIG, false, loader);
        Class<?> function1Class = Class.forName(
                "kotlin.jvm.functions.Function1", false, loader);
        Method buildRequest = providerClass.getDeclaredMethod(
                "p", configClass, List.class, String.class, boolean.class);
        requireReturnType(buildRequest, String.class);
        Method scanSseData = providerClass.getDeclaredMethod("t", String.class, function1Class);
        requireReturnType(scanSseData, boolean.class);
        return new HostAbi(providerClass, configClass, true,
                accessible(buildRequest), accessible(scanSseData));
    }

    static HostAbi minifiedRequestFromDex(Class<?> providerClass, Class<?> configClass,
            Method buildRequestMethod, Method scanSseDataMethod) throws Exception {
        requireReturnType(buildRequestMethod, String.class);
        requireReturnType(scanSseDataMethod, boolean.class);
        return new HostAbi(providerClass, configClass, true,
                accessible(buildRequestMethod), accessible(scanSseDataMethod));
    }

    private static void requireReturnType(Method method, Class<?> returnType)
            throws NoSuchMethodException {
        if (method == null || method.getReturnType() != returnType) {
            throw new NoSuchMethodException("method return type mismatch");
        }
    }

    private static Method accessible(Method method) {
        if (method != null) method.setAccessible(true);
        return method;
    }

    private static String shortError(Throwable error) {
        if (error == null) return "none";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
