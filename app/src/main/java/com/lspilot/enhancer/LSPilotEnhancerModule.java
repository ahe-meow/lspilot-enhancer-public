package com.lspilot.enhancer;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;


import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;

public final class LSPilotEnhancerModule extends XposedModule {
    private static final String TAG = "LSPilotEnhancer";
    private static final String TARGET_PACKAGE = "me.yun.lspilot";
    private static final String PROVIDER_CLASS =
            "me.yun.lspilot.data.provider.OpenAiApiProvider";
    private static final String CONFIG_CLASS =
            "me.yun.lspilot.data.model.AiProviderConfig";
    private static final String MAIN_ACTIVITY_CLASS =
            "me.yun.lspilot.ui.MainActivity";

    private static final String CACHE_NAMESPACE = "lspilot:v3:";
    private static final ThreadLocal<Boolean> NATIVE_ROUTE_REPLAYING = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> NATIVE_TOP_BAR_REPLAYING = new ThreadLocal<>();
    private static volatile boolean reasoningDeltaNormalizedReported;

    private static final class StartupProbe {
        boolean requestBody;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            ModuleSettings.useRemotePreferences(getRemotePreferences(ModuleSettings.PREFS_NAME));
            log(Log.INFO, TAG, "Legacy settings migration source connected");
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Legacy settings migration source unavailable", error);
        }

        ClassLoader loader = param.getClassLoader();
        String[] dexPaths = hostDexPaths(param.getApplicationInfo());
        HostAbi abi;
        try {
            abi = HostAbi.resolve(loader, dexPaths, param.getApplicationInfo());
            String resolutionReason = HostAbiCache.lastResolutionReason();
            log(Log.INFO, TAG, "Host ABI resolution reason=" + resolutionReason);
            if ("host_update".equals(resolutionReason)
                    || "host_and_module_update".equals(resolutionReason)) {
                log(Log.INFO, TAG,
                        "HOST_UPDATE_DETECTED; DEX_SELF_ADAPTATION completed before hooks");
            }
            log(Log.INFO, TAG, "Resolved LSPilot host ABI minified=" + abi.minified
                    + " provider=" + abi.providerClass.getName()
                    + " config=" + abi.configClass.getName());
        } catch (Throwable error) {
            ModuleSettings.disableSettings("宿主 ABI 解析失败：" + shortError(error),
                    ModuleSettings.KEY_ENABLED,
                    ModuleSettings.KEY_CACHE_KEY,
                    ModuleSettings.KEY_RETENTION,
                    ModuleSettings.KEY_INCLUDE_USAGE,
                    ModuleSettings.KEY_REASONING_EFFORT);
            log(Log.ERROR, TAG, "Failed to resolve LSPilot host ABI", error);
            return;
        }
        StartupProbe probe = installRequestHook(loader, abi);
        boolean sseUsageHook = installSseUsageHook(abi);
        applyStartupProbe(probe, sseUsageHook, abi);
        installUiHooks(loader, dexPaths);
        log(Log.INFO, TAG,
                "LSPilotEnhancer loaded version=" + BuildConfig.VERSION_NAME
                        + " (" + BuildConfig.VERSION_CODE + ") dexkit-free-abi");
    }

    private static String[] hostDexPaths(ApplicationInfo appInfo) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (appInfo != null) {
            addDexPath(result, appInfo.sourceDir);
            if (appInfo.splitSourceDirs != null) {
                for (String path : appInfo.splitSourceDirs) addDexPath(result, path);
            }
        }
        return result.toArray(new String[0]);
    }

    private static void addDexPath(java.util.ArrayList<String> result, String path) {
        if (path != null && !path.trim().isEmpty() && !result.contains(path)) {
            result.add(path);
        }
    }

    private StartupProbe installRequestHook(ClassLoader loader, HostAbi abi) {
        StartupProbe probe = new StartupProbe();
        if (abi.minified) {
            probe.requestBody = installMinifiedRequestBodyHook(abi);
            InjectedUiController.setRequestHookInstalled(probe.requestBody);
            return probe;
        }
        try {
            Method buildRequest = abi.buildRequestMethod;
            buildRequest.setAccessible(true);

            hook(buildRequest).intercept(chain -> {
                Object originalResult = chain.proceed();
                if (!(originalResult instanceof String)) {
                    return originalResult;
                }

                try {
                    boolean policyEnabled = ModuleSettings.isEnabled();
                    if (!policyEnabled) return originalResult;
                    String systemPrompt = (String) chain.getArg(2);
                    JSONObject body = new JSONObject((String) originalResult);
                    String model = requestModel(body);
                    applyOpenAiRequestPolicy(body, model, systemPrompt, true);
                    return body.toString();
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "Request enhancement failed; using host request", error);
                    return originalResult;
                }
            });

            probe.requestBody = true;
            InjectedUiController.setRequestHookInstalled(true);
            log(Log.INFO, TAG,
                    "Hook installed for " + PROVIDER_CLASS
                            + "#buildOpenAiRequestBody, API=" + getApiVersion());
        } catch (Throwable error) {
            InjectedUiController.setRequestHookInstalled(false);
            log(Log.ERROR, TAG, "Failed to install OpenAI cache hook", error);
        }
        return probe;
    }

    private void applyOpenAiRequestPolicy(JSONObject body, String model, String systemPrompt,
            boolean policyEnabled) throws Exception {
        boolean openAiModel = isOpenAiModel(model);
        boolean reasoningApplied = policyEnabled && ReasoningPolicy.applyRequest(
                body, model, ModuleSettings.getReasoningEffort());
        boolean cacheKeyEnabled = policyEnabled && openAiModel
                && ModuleSettings.isCacheKeyEnabled();
        boolean explicitCacheEnabled = cacheKeyEnabled
                && PromptCachePolicy.supportsExplicitBreakpoints(model);
        boolean retentionEnabled = policyEnabled
                && PromptCachePolicy.supportsRetention(model) && !explicitCacheEnabled
                && ModuleSettings.isRetentionEnabled();
        boolean usageEnabled = policyEnabled && ModuleSettings.isIncludeUsageEnabled();
        String cacheKey = openAiModel
                ? buildCacheKey(model, cacheIdentity(body, systemPrompt)) : "not-applicable";
        int explicitBreakpoints = 0;

        if (cacheKeyEnabled) {
            body.put("prompt_cache_key", cacheKey);
        }
        if (explicitCacheEnabled) {
            explicitBreakpoints = PromptCachePolicy.applyExplicitBreakpoints(body);
        } else if (retentionEnabled) {
            body.put("prompt_cache_retention", "24h");
        }
        if (usageEnabled) {
            JSONObject streamOptions = body.optJSONObject("stream_options");
            if (streamOptions == null) {
                streamOptions = new JSONObject();
                body.put("stream_options", streamOptions);
            }
            streamOptions.put("include_usage", true);
        }

        if (reasoningApplied) {
            log(Log.INFO, TAG, "Reasoning effort applied="
                    + ModuleSettings.getReasoningEffort());
        }
        if (ModuleSettings.isDebugLogEnabled()) {
            log(Log.DEBUG, TAG,
                    "Enhanced OpenAI request: model=" + model
                            + ", reasoning=" + body.optString("reasoning_effort", "disabled")
                            + ", key=" + (cacheKeyEnabled ? cacheKey : "disabled")
                            + ", explicitBreakpoints=" + explicitBreakpoints
                            + ", retention=" + retentionEnabled
                            + ", usage=" + usageEnabled);
        }
    }

    private boolean installMinifiedRequestBodyHook(HostAbi abi) {
        try {
            Method buildRequest = abi.buildRequestMethod;
            hook(buildRequest).intercept(chain -> {
                Object originalResult = chain.proceed();
                if (!(originalResult instanceof String)) {
                    return originalResult;
                }

                try {
                    boolean policyEnabled = ModuleSettings.isEnabled();
                    if (!policyEnabled) return originalResult;
                    String systemPrompt = (String) chain.getArg(2);
                    JSONObject body = new JSONObject((String) originalResult);
                    String model = requestModel(body);
                    applyOpenAiRequestPolicy(body, model, systemPrompt, true);
                    return body.toString();
                } catch (Throwable error) {
                    log(Log.ERROR, TAG,
                            "Minified request enhancement failed; using host request", error);
                    return originalResult;
                }
            });

            InjectedUiController.setRequestHookInstalled(true);
            log(Log.INFO, TAG, "Cache request body hook installed for "
                    + buildRequest.getDeclaringClass().getName() + "#" + buildRequest.getName());
            return true;
        } catch (Throwable error) {
            InjectedUiController.setRequestHookInstalled(false);
            log(Log.ERROR, TAG, "Failed to install minified cache request hook", error);
            return false;
        }
    }

    private boolean installSseUsageHook(HostAbi abi) {
        try {
            Method scanSseData = abi.scanSseDataMethod;
            scanSseData.setAccessible(true);

            hook(scanSseData).intercept(chain -> {
                Object payload = chain.getArg(0);
                if (!(payload instanceof String)) return chain.proceed();
                String original = (String) payload;
                recordSseUsage(original);
                String normalized = ReasoningPolicy.normalizeSseDelta(original);
                if (original.equals(normalized)) return chain.proceed();
                if (!reasoningDeltaNormalizedReported) {
                    reasoningDeltaNormalizedReported = true;
                    log(Log.INFO, TAG, "SSE delta.reasoning normalized to reasoning_content");
                }
                Object[] args = chain.getArgs().toArray();
                args[0] = normalized;
                return chain.proceed(args);
            });

            log(Log.INFO, TAG,
                    "Raw SSE usage/reasoning hook installed for "
                            + scanSseData.getDeclaringClass().getName() + "#" + scanSseData.getName());
            return true;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install raw SSE usage/reasoning hook", error);
            return false;
        }
    }

    private void applyStartupProbe(StartupProbe probe, boolean sseUsageHook, HostAbi abi) {
        if (probe == null) probe = new StartupProbe();
        if (!probe.requestBody) {
            ModuleSettings.disableSettings("请求修改 Hook 接口全部失效",
                    ModuleSettings.KEY_ENABLED);
        }
        if (!probe.requestBody) {
            ModuleSettings.disableSettings("请求体 Hook 接口失效",
                    ModuleSettings.KEY_CACHE_KEY,
                    ModuleSettings.KEY_RETENTION,
                    ModuleSettings.KEY_INCLUDE_USAGE,
                    ModuleSettings.KEY_REASONING_EFFORT);
        }
        if (!sseUsageHook) {
            ModuleSettings.disableSettings("SSE usage Hook 接口失效",
                    ModuleSettings.KEY_INCLUDE_USAGE);
        }
        log(Log.INFO, TAG, "Startup hook probe: minified=" + abi.minified
                + " requestBody=" + probe.requestBody
                + " sseUsage=" + sseUsageHook);
    }

    private static String requestModel(JSONObject body) {
        if (body == null) return null;
        String model = body.optString("model", null);
        return model == null || model.trim().isEmpty() ? null : model;
    }

    private static String shortError(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    private void recordSseUsage(String payload) {
        try {
            JSONObject root = new JSONObject(payload);
            JSONObject usage = root.optJSONObject("usage");
            if (usage == null) {
                return;
            }

            JSONObject promptDetails = usage.optJSONObject("prompt_tokens_details");
            JSONObject inputDetails = usage.optJSONObject("input_tokens_details");
            long cachedTokens = firstLong(
                    promptDetails == null ? -1L : promptDetails.optLong("cached_tokens", -1L),
                    inputDetails == null ? -1L : inputDetails.optLong("cached_tokens", -1L));
            long inputTokens = firstLong(
                    usage.optLong("prompt_tokens", -1L),
                    usage.optLong("input_tokens", -1L));
            long outputTokens = firstLong(
                    usage.optLong("completion_tokens", -1L),
                    usage.optLong("output_tokens", -1L));

            if (ModuleSettings.isVerboseDebugLogEnabled()) {
                DebugLogger.d("Raw SSE usage event length=" + payload.length());
            }
            long totalTokens = usage.optLong("total_tokens", -1L);
            long cacheWriteTokens = firstLong(
                    usage.optLong("cache_write_tokens", -1L),
                    usage.optLong("cached_write_tokens", -1L));
            log(Log.INFO, TAG,
                    "OpenAI cache usage: input_tokens=" + displayTokenCount(inputTokens)
                            + ", output_tokens=" + displayTokenCount(outputTokens)
                            + ", total_tokens=" + displayTokenCount(totalTokens)
                            + ", cached_tokens=" + displayTokenCount(cachedTokens)
                            + ", cache_write_tokens=" + displayTokenCount(cacheWriteTokens));
        } catch (Throwable error) {
            if (ModuleSettings.isDebugLogEnabled()) {
                log(Log.DEBUG, TAG, "Ignored non-JSON SSE data event");
            }
        }
    }

    private static long firstLong(long first, long second) {
        return first >= 0L ? first : second;
    }

    private static String displayTokenCount(long value) {
        return value >= 0L ? Long.toString(value) : "unavailable";
    }

    private void installUiHooks(ClassLoader loader, String[] dexPaths) {
        try {
            Class<?> activityClass = loader.loadClass(MAIN_ACTIVITY_CLASS);
            Method onCreate = activityClass.getDeclaredMethod("onCreate", Bundle.class);
            onCreate.setAccessible(true);
            hook(onCreate).intercept(chain -> {
                Object instance = chain.getThisObject();
                if (instance instanceof Activity) {
                    InjectedUiController.prepare((Activity) instance);
                }
                Object result = chain.proceed();
                if (instance instanceof Activity) {
                    InjectedUiController.attach((Activity) instance);
                }
                return result;
            });

            Method arrowPreference = findArrowPreferenceMethod(
                    findArrowPreferenceClass(loader, dexPaths));
            HostNativeSettings.resolve(loader, arrowPreference);

            final Method navigate = HostNativeSettings.navigateMethod();
            hook(navigate).intercept(chain -> {
                if (Boolean.TRUE.equals(NATIVE_ROUTE_REPLAYING.get())) {
                    return chain.proceed();
                }
                Object originalRoute = chain.getArg(0);
                Object replacementRoute = HostNativeSettings.rewriteNavigationRoute(originalRoute);
                if (replacementRoute == originalRoute) return chain.proceed();
                NATIVE_ROUTE_REPLAYING.set(Boolean.TRUE);
                try {
                    return navigate.invoke(chain.getThisObject(), replacementRoute);
                } finally {
                    NATIVE_ROUTE_REPLAYING.remove();
                }
            });

            hook(HostNativeSettings.routeRendererMethod()).intercept(chain -> {
                boolean moduleRoute = HostNativeSettings.isModuleRoute(chain.getArg(0));
                HostNativeSettings.setModuleRouteVisible(moduleRoute);
                if (!moduleRoute) return chain.proceed();
                try {
                    return HostNativeSettings.renderPage(chain.getArg(1));
                } catch (Throwable error) {
                    HostNativeSettings.setModuleRouteVisible(false);
                    log(Log.ERROR, TAG, "Failed to render native module settings route", error);
                    return chain.proceed();
                }
            });

            hook(HostNativeSettings.settingsPagerMethod()).intercept(chain -> {
                if (!HostNativeSettings.isRenderingPage()) {
                    HostNativeSettings.captureSettings(
                            chain.getArg(0), chain.getArg(1),
                            ((Number) chain.getArg(2)).floatValue());
                }
                return chain.proceed();
            });

            hook(HostNativeSettings.lazyItemMethod()).intercept(chain -> {
                if (HostNativeSettings.shouldInsertBeforeHostItem()) {
                    try {
                        HostNativeSettings.addEntry(chain.getArg(0));
                        HostNativeSettings.markHostEntryInserted();
                    } catch (Throwable error) {
                        log(Log.ERROR, TAG, "Failed to insert native settings entry", error);
                    }
                }
                return chain.proceed();
            });

            hook(HostNativeSettings.settingsListMethod()).intercept(chain -> {
                if (HostNativeSettings.shouldRenderSettings()) {
                    try {
                        return HostNativeSettings.addSettings(chain.getArg(2));
                    } catch (Throwable error) {
                        log(Log.ERROR, TAG, "Failed to render native module settings", error);
                        return chain.proceed();
                    }
                }
                HostNativeSettings.beginHostList();
                try {
                    Object result = chain.proceed();
                    if (!HostNativeSettings.hostEntryInserted()) {
                        try {
                            HostNativeSettings.addEntry(chain.getArg(2));
                            HostNativeSettings.markHostEntryInserted();
                            log(Log.WARN, TAG,
                                    "Native settings entry used end-of-list compatibility fallback");
                        } catch (Throwable error) {
                            log(Log.ERROR, TAG, "Failed to append native settings entry", error);
                        }
                    }
                    return result;
                } finally {
                    HostNativeSettings.endHostList();
                }
            });

            final Method topAppBar = HostNativeSettings.topAppBarMethod();
            hook(topAppBar).intercept(chain -> {
                if (!HostNativeSettings.isRenderingPage()
                        || Boolean.TRUE.equals(NATIVE_TOP_BAR_REPLAYING.get())) {
                    return chain.proceed();
                }
                Object[] args = new Object[topAppBar.getParameterTypes().length];
                for (int index = 0; index < args.length; index++) {
                    args[index] = chain.getArg(index);
                }
                args[0] = "模型请求增强";
                NATIVE_TOP_BAR_REPLAYING.set(Boolean.TRUE);
                try {
                    return topAppBar.invoke(null, args);
                } finally {
                    NATIVE_TOP_BAR_REPLAYING.remove();
                }
            });

            log(Log.INFO, TAG,
                    "Native host settings page hooks installed with AutoAwesome icon");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install native settings UI hooks", error);
        }
    }

    private static Class<?> findArrowPreferenceClass(ClassLoader loader, String[] dexPaths)
            throws Exception {
        return DexAbiScanner.findArrowPreferenceClass(loader, dexPaths);
    }

    private static Method findArrowPreferenceMethod(Class<?> arrowClass)
            throws NoSuchMethodException {
        StringBuilder candidates = new StringBuilder();
        for (Method method : arrowClass.getDeclaredMethods()) {
            boolean compatible = matchesArrowPreferenceAbi(method);
            if (!"ArrowPreference".equals(method.getName()) && !compatible) {
                continue;
            }
            if (candidates.length() > 0) {
                candidates.append("; ");
            }
            candidates.append(describeMethod(method));
            if (compatible) {
                return method;
            }
        }
        throw new NoSuchMethodException(
                "Compatible ArrowPreference ABI not found; candidates=" + candidates);
    }

    private static boolean matchesArrowPreferenceAbi(Method method) {
        if (!Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != void.class) {
            return false;
        }
        Class<?>[] types = method.getParameterTypes();
        return types.length == 16
                && typeNameEquals(types[0], "java.lang.String")
                && typeNameEquals(types[3], "java.lang.String")
                && typeNameEquals(types[5], "kotlin.jvm.functions.Function2")
                && typeNameEquals(types[6], "kotlin.jvm.functions.Function3")
                && typeNameEquals(types[7], "kotlin.jvm.functions.Function2")
                && typeNameEquals(types[9], "kotlin.jvm.functions.Function0")
                && types[10] == boolean.class
                && types[11] == boolean.class
                && !types[12].isPrimitive()
                && types[13] == int.class
                && types[14] == int.class
                && types[15] == int.class;
    }

    private static boolean typeNameEquals(Class<?> type, String expectedName) {
        return type != null && expectedName.equals(type.getName());
    }

    private static String describeMethod(Method method) {
        StringBuilder result = new StringBuilder(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int index = 0; index < types.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(types[index].getName());
        }
        return result.append("): ").append(method.getReturnType().getName()).toString();
    }

    private static String cacheIdentity(JSONObject body, String fallback) {
        if (body != null) {
            JSONArray messages = body.optJSONArray("messages");
            if (messages != null) {
                for (int index = 0; index < messages.length(); index++) {
                    JSONObject message = messages.optJSONObject(index);
                    if (message == null) continue;
                    String role = message.optString("role", "");
                    if (!"system".equals(role) && !"developer".equals(role)) continue;
                    Object content = message.opt("content");
                    if (content != null && !JSONObject.NULL.equals(content)) {
                        return String.valueOf(content);
                    }
                }
            }
        }
        return fallback == null ? "" : fallback;
    }

    private static String buildCacheKey(String model, String cacheIdentity) {
        String prompt = cacheIdentity == null ? "" : cacheIdentity;
        return CACHE_NAMESPACE + sha256Prefix(normalize(model) + "\n" + prompt);
    }

    private static boolean isOpenAiModel(String model) {
        String value = normalize(model);
        return value.startsWith("gpt-")
                || value.startsWith("chatgpt-")
                || value.matches("o[134](-.*)?");
    }

    private static String normalize(String value) {
        String source = value == null ? "" : value;
        String normalized = source.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private static String sha256Prefix(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                result.append(String.format(Locale.ROOT, "%02x", hash[i] & 0xff));
            }
            return result.toString();
        } catch (Throwable error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
