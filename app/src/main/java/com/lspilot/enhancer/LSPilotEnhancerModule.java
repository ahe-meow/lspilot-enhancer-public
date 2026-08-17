package com.lspilot.enhancer;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;


import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
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
    private static final String SUB_SCREEN_ACTIVITY_CLASS =
            "me.yun.lspilot.ui.SubScreenActivity";
    private static final String ARROW_PREFERENCE_CLASS =
            "top.yukonga.miuix.kmp.preference.ArrowPreferenceKt";

    private static final String CACHE_NAMESPACE = "lspilot:v3:";
    private static final ThreadLocal<Boolean> SETTINGS_ENTRY_REPLAYING = new ThreadLocal<>();
    private static volatile boolean settingsEntryRenderReported;
    private static volatile boolean aboutPreferenceObservedReported;
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
                    + " config=" + abi.configClass.getName()
                    + " viewModel=" + (abi.viewModelClass == null
                            ? "unavailable" : abi.viewModelClass.getName()));
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
        installChatViewModelHook(loader, abi);
        installAutoRetryHooks(abi);
        installUserSendHook(abi);
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
            if (abi.streamMessagesMethod != null) installMinifiedStreamHook(abi);
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
        boolean retentionEnabled = policyEnabled && openAiModel && !explicitCacheEnabled
                && ModuleSettings.isRetentionEnabled() && supportsExtendedRetention(model);
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

    private boolean installMinifiedStreamHook(HostAbi abi) {
        try {
            Method streamMessages = abi.streamMessagesMethod;
            hook(streamMessages).intercept(chain -> {
                Object viewModel = chain.getThisObject();
                String chatId = abi.currentChatId(viewModel);
                Object rawMessages = chain.getArg(1);
                Object[] args = chain.getArgs().toArray();
                List<?> messages = rawMessages instanceof List ? (List<?>) rawMessages : null;
                if (messages != null) {
                    List<?> retryRequest = AutoRetryManager.retryRequestMessages(
                            abi, viewModel, chatId, messages);
                    boolean customRetry = retryRequest != null;
                    if (customRetry) {
                        messages = retryRequest;
                        log(Log.WARN, TAG, "auto retry request starts before failed assistant"
                                + " requestMessages=" + messages.size());
                    } else {
                        AutoRetryManager.captureAttemptMessages(viewModel, chatId, messages);
                        List<?> restored = AutoRetryManager.restoreAttemptMessages(
                                viewModel, chatId, messages);
                        if (restored != messages) {
                            messages = restored;
                            log(Log.WARN, TAG, "auto retry restored original stream context messages="
                                    + messages.size());
                        }
                    }
                    if (messages != rawMessages) {
                        args[1] = messages;
                    }
                }
                args[2] = AutoRetryManager.wrapStreamCallback(
                        chain.getArg(2), viewModel, chatId, abi);
                return chain.proceed(args);
            });
            InjectedUiController.setRequestHookInstalled(true);
            log(Log.INFO, TAG, "Adaptive stream/retry hook installed for "
                    + streamMessages.getDeclaringClass().getName() + "#" + streamMessages.getName());
            return true;
        } catch (Throwable error) {
            InjectedUiController.setRequestHookInstalled(false);
            log(Log.ERROR, TAG, "Failed to install minified stream/retry hook", error);
            return false;
        }
    }

    private void installAutoRetryHooks(HostAbi abi) {
        if (!abi.hasRetryAbi()) {
            log(Log.WARN, TAG, "Auto retry ABI incomplete; retry hooks disabled");
            return;
        }
        Method retryResponse = abi.retryResponseMethod;
        Method stopGeneration = abi.stopGenerationMethod;
        AutoRetryManager.configure(retryResponse);

        if (!abi.minified) installNamedStreamRetryHook(abi);
        if (retryResponse == null || stopGeneration == null) {
            log(Log.ERROR, TAG, "Auto retry control ABI unavailable retry="
                    + (retryResponse != null) + " stop=" + (stopGeneration != null));
            return;
        }

        try {
            hook(retryResponse).intercept(chain -> {
                Object viewModel = chain.getThisObject();
                String chatId = currentChatId(abi, viewModel);
                if (!AutoRetryManager.isInternalRetry()) {
                    AutoRetryManager.beginTurn(viewModel, chatId);
                    AutoRetryManager.setRetryMethod(viewModel, retryResponse);
                    return chain.proceed();
                }
                AutoRetryManager.prepareHostRetry(abi, viewModel, chatId);
                try {
                    return chain.proceed();
                } catch (Throwable error) {
                    AutoRetryManager.restoreHostRetry(abi, viewModel, chatId);
                    throw error;
                }
            });
            hook(stopGeneration).intercept(chain -> {
                Object viewModel = chain.getThisObject();
                AutoRetryManager.cancelForStop(viewModel, currentChatId(abi, viewModel));
                return chain.proceed();
            });
            hook(abi.repositoryAddMessageMethod).intercept(chain -> {
                String chatId = String.valueOf(chain.getArg(0));
                Object message = chain.getArg(1);
                Object result = chain.proceed();
                AutoRetryManager.onRepositoryMessage(abi, chatId,
                        abi.messageRole(message), abi.messageContent(message), message);
                return result;
            });
            log(Log.INFO, TAG, "Auto retry hooks installed retry="
                    + retryResponse.getName() + " stop=" + stopGeneration.getName()
                    + " delays=5s,10s,30s,2m,5m");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install auto retry control hooks", error);
        }
    }

    private boolean installNamedStreamRetryHook(HostAbi abi) {
        try {
            Method streamMessages = abi.streamMessagesMethod;
            hook(streamMessages).intercept(chain -> {
                Object viewModel = chain.getThisObject();
                Object[] args = chain.getArgs().toArray();
                String chatId = currentChatId(abi, viewModel);
                Object rawMessages = chain.getArg(1);
                if (rawMessages instanceof List) {
                    List<?> messages = (List<?>) rawMessages;
                    AutoRetryManager.captureAttemptMessages(viewModel, chatId, messages);
                    List<?> restored = AutoRetryManager.restoreAttemptMessages(
                            viewModel, chatId, messages);
                    if (restored != messages) {
                        args[1] = restored;
                        log(Log.WARN, TAG, "auto retry restored named stream context messages="
                                + restored.size());
                    }
                }
                args[2] = AutoRetryManager.wrapStreamCallback(
                        chain.getArg(2), viewModel, chatId, abi);
                return chain.proceed(args);
            });
            log(Log.INFO, TAG, "Named stream retry hook installed for "
                    + streamMessages.getDeclaringClass().getName() + "#" + streamMessages.getName());
            return true;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install named stream retry hook", error);
            return false;
        }
    }

    private static String currentChatId(HostAbi abi, Object viewModel) {
        return abi.currentChatId(viewModel);
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

    private void installSubScreenCreateHook(ClassLoader loader) {
        try {
            Class<?> activityClass = Class.forName(SUB_SCREEN_ACTIVITY_CLASS, false, loader);
            Method onCreate = activityClass.getDeclaredMethod("onCreate", Bundle.class);
            onCreate.setAccessible(true);
            hook(onCreate).intercept(chain -> {
                Object instance = chain.getThisObject();
                Object result = chain.proceed();
                if (instance instanceof Activity) {
                    log(Log.INFO, TAG, "SubScreenActivity.onCreate captured");
                    InjectedUiController.setActiveActivity((Activity) instance);
                }
                return result;
            });
            log(Log.INFO, TAG, "SubScreenActivity.onCreate hook installed");
        } catch (ClassNotFoundException ignored) {
            log(Log.INFO, TAG, "SubScreenActivity absent; using MainActivity overlay");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install SubScreenActivity.onCreate hook", error);
        }
    }

    private void installChatViewModelHook(ClassLoader loader, HostAbi abi) {
        if (abi.loadSessionMethod == null) {
            log(Log.WARN, TAG, "Load-session ABI unavailable; chat retry hook disabled");
            return;
        }
        try {
            Method loadSession = abi.loadSessionMethod;
            hook(loadSession).intercept(chain -> {
                Object viewModel = chain.getThisObject();
                String chatId = (String) chain.getArg(1);
                AutoRetryManager.onChatLoaded(viewModel, chatId);
                return chain.proceed();
            });
            log(Log.INFO, TAG, "AiChatViewModel.loadSession hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install AiChatViewModel.loadSession hook", error);
        }
    }

    private void installUserSendHook(HostAbi abi) {
        if (abi.sendMessageMethod == null) {
            log(Log.WARN, TAG, "Send-message ABI unavailable; user-send retry hook disabled");
            return;
        }
        try {
            Method sendMessage = abi.sendMessageMethod;
            hook(sendMessage).intercept(chain -> {
                Object viewModel = chain.getThisObject();
                AutoRetryManager.onUserSend(viewModel, currentChatId(abi, viewModel));
                return chain.proceed();
            });
            log(Log.INFO, TAG, "AiChatViewModel.sendMessage retry hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install user-send hook", error);
        }
    }

    private void installUiHooks(ClassLoader loader, String[] dexPaths) {
        try {
            Class<?> activityClass = Class.forName(MAIN_ACTIVITY_CLASS, false, loader);
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
            installSubScreenCreateHook(loader);
            // Observe LSPilot's own working ArrowPreference calls. When its native "About"
            // row is rendered, replay the exact same valid argument set while changing only
            // title, summary and click action. This preserves the host's colors, icon, padding,
            // Composer flags and Kotlin default mask instead of guessing their ABI.
            Class<?> arrowClass = findArrowPreferenceClass(loader, dexPaths);
            Method arrowPreference = findArrowPreferenceMethod(arrowClass);
            arrowPreference.setAccessible(true);
            final Method nativeArrowPreference = arrowPreference;
            final Object settingsClickAction =
                    InjectedUiController.createSettingsClickAction(loader);
            hook(nativeArrowPreference).intercept(chain -> {
                Object result = chain.proceed();
                if (Boolean.TRUE.equals(SETTINGS_ENTRY_REPLAYING.get())) {
                    return result;
                }

                Object title = chain.getArg(0);
                if (isAboutTitle(title)) {
                    if (!aboutPreferenceObservedReported) {
                        aboutPreferenceObservedReported = true;
                        log(Log.INFO, TAG,
                                "Native About preference observed; replaying module entry");
                    }
                    try {
                        Object[] copiedArgs = new Object[16];
                        for (int index = 0; index < copiedArgs.length; index++) {
                            copiedArgs[index] = chain.getArg(index);
                        }
                        copiedArgs[0] = "模型请求增强";
                        copiedArgs[3] = requestHookSummary();
                        copiedArgs[9] = settingsClickAction;

                        SETTINGS_ENTRY_REPLAYING.set(Boolean.TRUE);
                        try {
                            nativeArrowPreference.invoke(null, copiedArgs);
                        } finally {
                            SETTINGS_ENTRY_REPLAYING.remove();
                        }
                        if (!settingsEntryRenderReported) {
                            settingsEntryRenderReported = true;
                            log(Log.INFO, TAG,
                                    "Settings-page entry rendered by replaying About preference");
                        }
                    } catch (Throwable error) {
                        SETTINGS_ENTRY_REPLAYING.remove();
                        if (!settingsEntryRenderReported) {
                            settingsEntryRenderReported = true;
                            log(Log.ERROR, TAG,
                                    "Failed to replay native About preference",
                                    unwrapInvocationError(error));
                        }
                    }
                }
                return result;
            });

            log(Log.INFO, TAG, "Native ArrowPreference replay hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install settings UI hooks", error);
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

    private static boolean isAboutTitle(Object title) {
        if (!(title instanceof CharSequence)) {
            return false;
        }
        String value = title.toString().trim();
        return "关于".equals(value) || "About".equalsIgnoreCase(value);
    }

    private static Throwable unwrapInvocationError(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getTargetException() != null) {
            current = ((InvocationTargetException) current).getTargetException();
        }
        return current;
    }

    private static String requestHookSummary() {
        return "推理强度、Prompt Cache 与上下文策略";
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

    private static boolean supportsExtendedRetention(String model) {
        String value = normalize(model);
        return value.startsWith("gpt-4.1")
                || value.startsWith("gpt-5")
                || value.startsWith("o1")
                || value.startsWith("o3")
                || value.startsWith("o4");
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
