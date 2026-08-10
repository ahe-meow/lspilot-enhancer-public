package dev.operit.lspilot.enhancer;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private static final String CHAT_SCREEN_CLASS =
            "me.yun.lspilot.ui.screen.aichat.AiChatScreenKt";
    private static final String ARROW_PREFERENCE_CLASS =
            "top.yukonga.miuix.kmp.preference.ArrowPreferenceKt";

    private static final String CACHE_NAMESPACE = "lspilot:v3:";
    private static final ThreadLocal<Boolean> SETTINGS_ENTRY_REPLAYING = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> AUTO_REPLAY_SEND = new ThreadLocal<>();
    private static volatile boolean settingsEntryRenderReported;
    private static volatile boolean aboutPreferenceObservedReported;
    private static volatile boolean publicChatEntryReported;
    private static volatile boolean chatStateCapturedReported;
    private static volatile boolean reasoningDeltaNormalizedReported;

    private static final class StartupProbe {
        boolean requestBody;
        boolean compression;
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
            ManualCompressionManager.configure(abi);
            log(Log.INFO, TAG, "Resolved LSPilot host ABI minified=" + abi.minified
                    + " provider=" + abi.providerClass.getName()
                    + " config=" + abi.configClass.getName()
                    + " viewModel=" + abi.viewModelClass.getName());
        } catch (Throwable error) {
            ModuleSettings.disableSettings("宿主 ABI 解析失败：" + shortError(error),
                    ModuleSettings.KEY_ENABLED,
                    ModuleSettings.KEY_CACHE_KEY,
                    ModuleSettings.KEY_RETENTION,
                    ModuleSettings.KEY_INCLUDE_USAGE,
                    ModuleSettings.KEY_CONTEXT_COMPRESSION,
                    ModuleSettings.KEY_REASONING_EFFORT);
            log(Log.ERROR, TAG, "Failed to resolve LSPilot host ABI", error);
            return;
        }
        StartupProbe probe = installRequestHook(loader, abi);
        if (probe.compression && !abi.hasCompressionAccessors()) {
            probe.compression = false;
            log(Log.ERROR, TAG, "Compression endpoint/accessor probe failed");
        }
        boolean sseUsageHook = installSseUsageHook(abi);
        applyStartupProbe(probe, sseUsageHook, abi);
        installUiHooks(loader, dexPaths);
        installChatRouteHook(loader, abi);
        installChatViewModelHook(loader, abi);
        installAutoRetryHooks(abi);
        installSendBeforeCompressionHook(loader, abi);
        if (!abi.minified) installChatButtonHook(loader);
        log(Log.INFO, TAG,
                "LSPilotEnhancer loaded version=1.7.4-preview.14 dexkit-adaptive-abi");
        // Disable the experimental Compose TopAppBar injection. The reliable entry is
        // the Activity-owned native overlay button installed from SubScreenActivity hooks.
        // installNativeChatTopBarActionHook(loader);
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
            probe.compression = installMinifiedStreamHook(abi);
            probe.requestBody = installMinifiedRequestBodyHook(abi);
            InjectedUiController.setRequestHookInstalled(probe.requestBody);
            return probe;
        }
        try {
            Method buildRequest = abi.buildRequestMethod;
            buildRequest.setAccessible(true);

            hook(buildRequest).intercept(chain -> {
                Object originalResult = chain.proceed();
                if (ManualCompressionManager.isInternalBuild()) {
                    return originalResult;
                }
                if (!(originalResult instanceof String)) {
                    return originalResult;
                }

                try {
                    String originalBody = (String) originalResult;
                    boolean policyEnabled = ModuleSettings.isEnabled();
                    if (!policyEnabled
                            && !ManualCompressionManager.hasPreparedForCurrentChat()) {
                        log(Log.DEBUG, TAG, "request compression bypassed enabled=false prepared=false");
                        return originalResult;
                    }
                    Object config = chain.getArg(0);
                    String systemPrompt = (String) chain.getArg(2);
                    JSONObject body = new JSONObject(originalBody);
                    String model = requestModel(body, abi, config);
                    JSONArray messages = body.optJSONArray("messages");
                    JSONArray requestMessages = ManualCompressionManager.sanitizeRequestMessages(messages);
                    if (requestMessages != null && requestMessages != messages) {
                        body.put("messages", requestMessages);
                        messages = requestMessages;
                        log(Log.INFO, TAG, "removed enhancer status messages from provider request");
                    }
                    JSONArray compacted = ManualCompressionManager.applyPrepared(messages, config);
                    boolean preparedUsed = compacted != null && compacted != messages;
                    if (compacted == null && policyEnabled
                            && ModuleSettings.isContextCompressionEnabled()) {
                        log(Log.DEBUG, TAG,
                                "request runtime compression skipped; no prepared context. "
                                        + "Compress while idle before sending the next message.");
                    }
                    if (compacted != null && compacted != messages) {
                        body.put("messages", compacted);
                        log(Log.INFO, TAG,
                                "request context applied mode=" + (preparedUsed ? "manual_prepared" : "none")
                                        + " originalMessages=" + messages.length()
                                        + " compactedMessages=" + compacted.length()
                                        + " originalChars=" + messages.toString().length()
                                        + " compactedChars=" + compacted.toString().length());
                    } else {
                        log(Log.DEBUG, TAG, "request context unchanged mode="
                                + (preparedUsed ? "manual_prepared_empty" : "none")
                                + " messages=" + (messages == null ? 0 : messages.length()));
                    }
                    applyOpenAiRequestPolicy(body, model, systemPrompt, policyEnabled);
                    return body.toString();
                } catch (Throwable error) {
                    log(Log.ERROR, TAG,
                            "Request enhancement failed; using original body", error);
                    return originalResult;
                }
            });

            probe.requestBody = true;
            probe.compression = true;
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
            log(Log.INFO, TAG, "GPT-5.6 sol reasoning effort applied="
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
                if (ManualCompressionManager.isInternalBuild()
                        || !(originalResult instanceof String)
                        || !ModuleSettings.isEnabled()) {
                    return originalResult;
                }

                try {
                    Object config = chain.getArg(0);
                    String systemPrompt = (String) chain.getArg(2);
                    JSONObject body = new JSONObject((String) originalResult);
                    String model = requestModel(body, abi, config);
                    applyOpenAiRequestPolicy(body, model, systemPrompt, ModuleSettings.isEnabled());
                    return body.toString();
                } catch (Throwable error) {
                    log(Log.ERROR, TAG,
                            "Minified request enhancement failed; using original body", error);
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
                Object config = chain.getArg(0);
                Object rawMessages = chain.getArg(1);
                Object[] args = chain.getArgs().toArray();
                List<?> messages = rawMessages instanceof List ? (List<?>) rawMessages : null;
                if (messages != null) {
                    AutoRetryManager.captureAttemptMessages(viewModel, chatId, messages);
                    List<?> restored = AutoRetryManager.restoreAttemptMessages(
                            viewModel, chatId, messages);
                    boolean restoredContext = restored != messages;
                    if (restoredContext) {
                        messages = restored;
                        log(Log.WARN, TAG, "auto retry restored original stream context messages="
                                + messages.size());
                    }
                    List<Object> compacted = ManualCompressionManager.applyPreparedToHostMessages(
                            messages, config, abi);
                    if (compacted != null) {
                        args[1] = compacted;
                        log(Log.INFO, TAG, "minified stream context applied originalMessages="
                                + messages.size() + " compactedMessages=" + compacted.size());
                    } else if (restoredContext) {
                        args[1] = messages;
                    }
                }
                args[2] = AutoRetryManager.wrapStreamCallback(
                        chain.getArg(2), viewModel, chatId);
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
        Method retryResponse = HostAbi.findRetryResponseMethod(abi.viewModelClass);
        Method stopGeneration = HostAbi.findStopGenerationMethod(abi.viewModelClass);
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
                } finally {
                    AutoRetryManager.restoreHostRetry(abi, viewModel, chatId);
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
                AutoRetryManager.onRepositoryMessage(chatId,
                        abi.messageRole(message), abi.messageContent(message));
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
                        chain.getArg(2), viewModel, chatId);
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
        String chatId = abi.currentChatId(viewModel);
        if (chatId != null && !chatId.trim().isEmpty()) return chatId;
        ManualCompressionManager.ScreenState screen = ManualCompressionManager.getCurrentScreen();
        return screen == null ? null : screen.chatId;
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
        if (!probe.requestBody && !probe.compression) {
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
        if (!probe.compression) {
            ModuleSettings.disableSettings("上下文压缩 Hook 接口失效",
                    ModuleSettings.KEY_CONTEXT_COMPRESSION);
        }
        if (!sseUsageHook) {
            ModuleSettings.disableSettings("SSE usage Hook 接口失效",
                    ModuleSettings.KEY_INCLUDE_USAGE);
        }
        log(Log.INFO, TAG, "Startup hook probe: minified=" + abi.minified
                + " requestBody=" + probe.requestBody
                + " compression=" + probe.compression
                + " sseUsage=" + sseUsageHook);
    }

    private static String requestModel(JSONObject body, HostAbi abi, Object config) throws Exception {
        String model = body == null ? null : body.optString("model", null);
        if (model != null && !model.trim().isEmpty()) return model;
        return abi.modelName(config);
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
                DebugLogger.d("Raw SSE usage event length=" + payload.length()
                        + " prefix=" + DebugLogger.redact(payload));
            }
            long totalTokens = usage.optLong("total_tokens", -1L);
            long cacheWriteTokens = firstLong(
                    usage.optLong("cache_write_tokens", -1L),
                    usage.optLong("cached_write_tokens", -1L));
            ManualCompressionManager.onProviderUsage(
                    inputTokens, outputTokens, cachedTokens, totalTokens);
            log(Log.INFO, TAG,
                    "OpenAI cache usage: input_tokens=" + displayTokenCount(inputTokens)
                            + ", output_tokens=" + displayTokenCount(outputTokens)
                            + ", total_tokens=" + displayTokenCount(totalTokens)
                            + ", cached_tokens=" + displayTokenCount(cachedTokens)
                            + ", cache_write_tokens=" + displayTokenCount(cacheWriteTokens));
        } catch (Throwable error) {
            if (ModuleSettings.isDebugLogEnabled()) {
                log(Log.DEBUG, TAG, "Ignored non-JSON SSE data event", error);
            }
        }
    }

    private static long firstLong(long first, long second) {
        return first >= 0L ? first : second;
    }

    private static String displayTokenCount(long value) {
        return value >= 0L ? Long.toString(value) : "unavailable";
    }

    private void installNativeChatTopBarActionHook(ClassLoader loader) {
        try {
            Class<?> screenClass = Class.forName(CHAT_SCREEN_CLASS, false, loader);
            Class<?> function0Class = Class.forName(
                    "kotlin.jvm.functions.Function0", false, loader);
            Class<?> mutableStateClass = Class.forName(
                    "androidx.compose.runtime.MutableState", false, loader);
            Class<?> rowScopeClass = Class.forName(
                    "androidx.compose.foundation.layout.RowScope", false, loader);
            Class<?> composerClass = Class.forName(
                    "androidx.compose.runtime.Composer", false, loader);
            Method actions = screenClass.getDeclaredMethod(
                    "AiChatScreenMiuix$lambda$5$0$1",
                    function0Class,
                    mutableStateClass,
                    rowScopeClass,
                    composerClass,
                    int.class);
            actions.setAccessible(true);
            hook(actions).intercept(chain -> {
                Object result = chain.proceed();
                NativeChatTopBarAction.renderAction(loader, chain.getArg(3));
                return result;
            });
            log(Log.INFO, TAG, "Native chat TopAppBar compression action hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install native chat TopAppBar action hook", error);
        }
    }

    private void installNativeChatContentPanelHook(ClassLoader loader) {
        try {
            Class<?> contentClass = Class.forName(
                    "me.yun.lspilot.ui.screen.aichat.AiChatScreenKt$$ExternalSyntheticLambda47",
                    false,
                    loader);
            Method invoke = contentClass.getDeclaredMethod(
                    "invoke",
                    Object.class,
                    Object.class,
                    Object.class);
            invoke.setAccessible(true);
            hook(invoke).intercept(chain -> {
                Object result = chain.proceed();
                NativeChatTopBarAction.renderPanel(loader, chain.getArg(1));
                return result;
            });
            log(Log.INFO, TAG, "Native chat content compression panel hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install native chat content compression panel hook", error);
        }
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

    private void installChatRouteHook(ClassLoader loader, HostAbi abi) {
        if (abi.minified) {
            installMinifiedMainRouteHooks(loader, abi);
            return;
        }
        try {
            Class<?> activityClass = Class.forName(SUB_SCREEN_ACTIVITY_CLASS, false, loader);
            Class<?> routeClass = Class.forName(
                    "me.yun.lspilot.ui.navigation.Route", false, loader);
            Class<?> aiChatRouteClass = Class.forName(
                    "me.yun.lspilot.ui.navigation.Route$AiChat", false, loader);
            Class<?> composerClass = Class.forName(
                    "androidx.compose.runtime.Composer", false, loader);
            Method routeContent = activityClass.getDeclaredMethod(
                    "onCreate$lambda$0$1",
                    routeClass, activityClass, composerClass, int.class);
            routeContent.setAccessible(true);
            hook(routeContent).intercept(chain -> {
                Object route = chain.getArg(0);
                Object owner = chain.getArg(1);
                if (owner instanceof Activity) {
                    InjectedUiController.setChatRouteVisible(
                            (Activity) owner, aiChatRouteClass.isInstance(route));
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "SubScreen route dispatcher hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install SubScreen route dispatcher hook", error);
        }
    }

    private void installMinifiedMainRouteHooks(ClassLoader loader, HostAbi abi) {
        int installed = 0;
        Class<?> aiChatRouteClass = abi.aiChatRouteClass;
        if (aiChatRouteClass == null) {
            try {
                aiChatRouteClass = Class.forName("lka$b", false, loader);
            } catch (ClassNotFoundException error) {
                log(Log.ERROR, TAG, "Minified AiChat route class unavailable", error);
                return;
            }
        }
        final Class<?> routeClass = aiChatRouteClass;
        for (char suffix = 'a'; suffix <= 'x'; suffix++) {
            try {
                Class<?> contentClass = Class.forName(
                        MAIN_ACTIVITY_CLASS + "$" + suffix, false, loader);
                Method invoke = findFunction1Invoke(contentClass);
                hook(invoke).intercept(chain -> {
                    InjectedUiController.onMainRouteComposed(
                            routeClass.isInstance(chain.getArg(0)));
                    return chain.proceed();
                });
                installed++;
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                // Not every compiler-generated MainActivity class is a route content lambda.
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Failed to hook MainActivity route lambda $" + suffix, error);
            }
        }
        if (installed == 0) {
            log(Log.ERROR, TAG, "No minified MainActivity route content hooks installed");
        } else {
            log(Log.INFO, TAG, "Minified MainActivity route content hooks installed=" + installed);
        }
    }

    private static Method findFunction1Invoke(Class<?> owner) throws NoSuchMethodException {
        Method fallback = null;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!"invoke".equals(method.getName()) || parameters.length != 1
                    || method.getReturnType() == void.class) {
                continue;
            }
            method.setAccessible(true);
            if (parameters[0] == Object.class && method.getReturnType() == Object.class) {
                return method;
            }
            fallback = method;
        }
        if (fallback != null) {
            return fallback;
        }
        throw new NoSuchMethodException("Function1 invoke not found on " + owner.getName());
    }

    private void installChatViewModelHook(ClassLoader loader, HostAbi abi) {
        try {
            Method loadSession = abi.loadSessionMethod;
            hook(loadSession).intercept(chain -> {
                Object viewModel = chain.getThisObject();
                String chatId = (String) chain.getArg(1);
                Object packageName = chain.getArg(0);
                AutoRetryManager.onChatLoaded(viewModel, chatId);
                ManualCompressionManager.captureViewModel(viewModel, chatId,
                        packageName == null ? null : String.valueOf(packageName), chain.getArg(2));
                ManualCompressionManager.enterChat(chatId);
                Object result = chain.proceed();
                if (abi.minified) {
                    ManualCompressionManager.updateMinifiedScreen(chatId, viewModel);
                    InjectedUiController.onChatSessionLoaded();
                }
                return result;
            });
            log(Log.INFO, TAG, "AiChatViewModel.loadSession hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install AiChatViewModel.loadSession hook", error);
        }
    }

    private void installSendBeforeCompressionHook(ClassLoader loader, HostAbi abi) {
        try {
            Method sendMessage = abi.sendMessageMethod;
            hook(sendMessage).intercept(chain -> {
                if (abi.minified) {
                    Object viewModel = chain.getThisObject();
                    String chatId = abi.currentChatId(viewModel);
                    if (chatId != null) {
                        ManualCompressionManager.enterChat(chatId);
                        ManualCompressionManager.updateMinifiedScreen(chatId, viewModel);
                        InjectedUiController.onChatSessionLoaded();
                    }
                }
                if (Boolean.TRUE.equals(AUTO_REPLAY_SEND.get())) {
                    return chain.proceed();
                }
                Object retryViewModel = chain.getThisObject();
                AutoRetryManager.onUserSend(
                        retryViewModel, currentChatId(abi, retryViewModel));
                if (ManualCompressionManager.blockSendWhilePreparing()) {
                    log(Log.INFO, TAG, "sendMessage blocked while compression is preparing");
                    return null;
                }
                if (!ManualCompressionManager.shouldAutoCompressBeforeSend()) {
                    return chain.proceed();
                }
                Object viewModel = chain.getThisObject();
                log(Log.INFO, TAG, "sendMessage paused for pre-send compression");
                ManualCompressionManager.prepareCurrentAutomatic(ModuleSettings.getManualKeepRecent(), result ->
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                log(result.success ? Log.INFO : Log.WARN, TAG,
                                        "pre-send compression finished success=" + result.success
                                                + " original=" + result.originalCount
                                                + " compacted=" + result.compactedCount);
                                if (!result.success) {
                                    log(Log.WARN, TAG,
                                            "pre-send compression failed; send not replayed"
                                                    + " to avoid uncompressed fallback");
                                    ManualCompressionManager.postCompressionStatusQuiet(
                                            "自动压缩失败，本次发送已取消，未回退发送完整历史。"
                                                    + "请检查压缩状态后重试；需要放行原始历史时请先关闭上下文压缩。");
                                    return;
                                }
                                AUTO_REPLAY_SEND.set(Boolean.TRUE);
                                try {
                                    sendMessage.invoke(viewModel);
                                } finally {
                                    AUTO_REPLAY_SEND.remove();
                                }
                            } catch (Throwable error) {
                                log(Log.ERROR, TAG, "Failed to replay sendMessage after compression", error);
                            }
                        }));
                return null;
            });
            log(Log.INFO, TAG, "AiChatViewModel.sendMessage pre-send compression hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install send-before-compression hook", error);
        }
    }

    private void installChatButtonHook(ClassLoader loader) {
        try {
            Class<?> screenClass = Class.forName(CHAT_SCREEN_CLASS, false, loader);
            Class<?> uiStateClass = Class.forName(
                    "me.yun.lspilot.ui.viewmodel.AiChatUiState", false, loader);
            Class<?> function0Class = Class.forName(
                    "kotlin.jvm.functions.Function0", false, loader);
            Class<?> function1Class = Class.forName(
                    "kotlin.jvm.functions.Function1", false, loader);
            Class<?> function2Class = Class.forName(
                    "kotlin.jvm.functions.Function2", false, loader);
            Class<?> composerClass = Class.forName(
                    "androidx.compose.runtime.Composer", false, loader);
            Method publicChatScreen = screenClass.getDeclaredMethod(
                    "AiChatScreen", String.class, String.class, composerClass, int.class);
            publicChatScreen.setAccessible(true);
            hook(publicChatScreen).intercept(chain -> {
                String chatId = (String) chain.getArg(1);
                ManualCompressionManager.enterChat(chatId);
                InjectedUiController.showChatCompressionButton();
                if (!publicChatEntryReported) {
                    publicChatEntryReported = true;
                    log(Log.INFO, TAG, "Public AiChatScreen executed; chat button requested");
                }
                return chain.proceed();
            });

            Method chatScreen = screenClass.getDeclaredMethod(
                    "AiChatScreenMiuix",
                    String.class, uiStateClass,
                    function0Class, function0Class, function0Class,
                    function1Class, function1Class, function2Class,
                    function0Class, function0Class, function2Class,
                    function0Class, function0Class, function1Class,
                    composerClass, int.class, int.class);
            chatScreen.setAccessible(true);
            hook(chatScreen).intercept(chain -> {
                String chatId = (String) chain.getArg(0);
                Object uiState = chain.getArg(1);
                ManualCompressionManager.updateScreen(chatId, uiState);
                InjectedUiController.showChatCompressionButton();
                if (!chatStateCapturedReported) {
                    chatStateCapturedReported = true;
                    log(Log.INFO, TAG, "Internal chat UI state captured");
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "Chat manual compression button hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install chat compression button hook", error);
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
        String normalized = value.toLowerCase(Locale.ROOT)
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
        } catch (Throwable ignored) {
            return Integer.toHexString(input.hashCode());
        }
    }
}