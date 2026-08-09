package dev.operit.lspilot.enhancer;

import android.app.Activity;
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

    private static final String CACHE_NAMESPACE = "lspilot:v2:";
    private static final ThreadLocal<Boolean> SETTINGS_ENTRY_REPLAYING = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> AUTO_REPLAY_SEND = new ThreadLocal<>();
    private static volatile boolean settingsEntryRenderReported;
    private static volatile boolean aboutPreferenceObservedReported;
    private static volatile boolean publicChatEntryReported;
    private static volatile boolean chatStateCapturedReported;

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
        HostAbi abi;
        try {
            abi = HostAbi.resolve(loader);
            ManualCompressionManager.configure(abi);
            log(Log.INFO, TAG, "Resolved LSPilot host ABI minified=" + abi.minified);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to resolve LSPilot host ABI", error);
            return;
        }
        installRequestHook(loader, abi);
        installSseUsageHook(loader, abi);
        installUiHooks(loader);
        installChatRouteHook(loader, abi);
        installChatViewModelHook(loader, abi);
        installSendBeforeCompressionHook(loader, abi);
        installChatButtonHook(loader);
        log(Log.INFO, TAG, "LSPilotEnhancer loaded version=1.7.4-preview.9 local-window-compression");
        // Disable the experimental Compose TopAppBar injection. The reliable entry is
        // the Activity-owned native overlay button installed from SubScreenActivity hooks.
        // installNativeChatTopBarActionHook(loader);
    }

    private void installRequestHook(ClassLoader loader, HostAbi abi) {
        if (abi.minified) {
            installMinifiedStreamHook(abi);
            return;
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
                    String model = abi.modelName(config);
                    JSONObject body = new JSONObject(originalBody);
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
                    if (policyEnabled && isOpenAiModel(model)
                            && ModuleSettings.isCacheKeyEnabled()) {
                        body.put("prompt_cache_key", buildCacheKey(model, systemPrompt));
                    }
                    if (policyEnabled && isOpenAiModel(model) && ModuleSettings.isRetentionEnabled()
                            && supportsExtendedRetention(model)) {
                        body.put("prompt_cache_retention", "24h");
                    }

                    if (policyEnabled && ModuleSettings.isIncludeUsageEnabled()) {
                        JSONObject streamOptions = body.optJSONObject("stream_options");
                        if (streamOptions == null) {
                            streamOptions = new JSONObject();
                            body.put("stream_options", streamOptions);
                        }
                        streamOptions.put("include_usage", true);
                    }

                    if (ModuleSettings.isDebugLogEnabled()) {
                        log(Log.DEBUG, TAG,
                                "Enhanced OpenAI request: model=" + model
                                        + ", key=" + (isOpenAiModel(model)
                                        ? buildCacheKey(model, systemPrompt) : "not-applicable")
                                        + ", usage=" + ModuleSettings.isIncludeUsageEnabled());
                    }
                    return body.toString();
                } catch (Throwable error) {
                    log(Log.ERROR, TAG,
                            "Request enhancement failed; using original body", error);
                    return originalResult;
                }
            });

            InjectedUiController.setRequestHookInstalled(true);
            log(Log.INFO, TAG,
                    "Hook installed for " + PROVIDER_CLASS
                            + "#buildOpenAiRequestBody, API=" + getApiVersion());
        } catch (Throwable error) {
            InjectedUiController.setRequestHookInstalled(false);
            log(Log.ERROR, TAG, "Failed to install OpenAI cache hook", error);
        }
    }

    private void installMinifiedStreamHook(HostAbi abi) {
        try {
            Method streamMessages = abi.streamMessagesMethod;
            hook(streamMessages).intercept(chain -> {
                Object config = chain.getArg(0);
                Object rawMessages = chain.getArg(1);
                if (!(rawMessages instanceof List)) return chain.proceed();
                List<?> messages = (List<?>) rawMessages;
                List<Object> compacted = ManualCompressionManager.applyPreparedToHostMessages(
                        messages, config, abi);
                if (compacted == null) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[1] = compacted;
                log(Log.INFO, TAG, "minified stream context applied originalMessages="
                        + messages.size() + " compactedMessages=" + compacted.size());
                return chain.proceed(args);
            });
            InjectedUiController.setRequestHookInstalled(true);
            log(Log.INFO, TAG, "Adaptive stream hook installed for "
                    + streamMessages.getDeclaringClass().getName() + "#" + streamMessages.getName());
        } catch (Throwable error) {
            InjectedUiController.setRequestHookInstalled(false);
            log(Log.ERROR, TAG, "Failed to install minified stream hook", error);
        }
    }

    private void installSseUsageHook(ClassLoader loader, HostAbi abi) {
        if (abi.minified) {
            log(Log.INFO, TAG, "Raw SSE usage hook unavailable on minified stream ABI");
            return;
        }
        try {
            Class<?> providerClass = Class.forName(PROVIDER_CLASS, false, loader);
            Class<?> function1Class = Class.forName(
                    "kotlin.jvm.functions.Function1", false, loader);
            Method scanSseData = providerClass.getDeclaredMethod(
                    "scanSseData", String.class, function1Class);
            scanSseData.setAccessible(true);

            hook(scanSseData).intercept(chain -> {
                Object payload = chain.getArg(0);
                if (payload instanceof String) {
                    recordSseUsage((String) payload);
                }
                return chain.proceed();
            });

            log(Log.INFO, TAG,
                    "Raw SSE usage hook installed for " + PROVIDER_CLASS + "#scanSseData");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install raw SSE usage hook", error);
        }
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
            ManualCompressionManager.onProviderUsage(
                    inputTokens, outputTokens, cachedTokens, totalTokens);
            log(Log.INFO, TAG,
                    "OpenAI cache usage: input_tokens=" + displayTokenCount(inputTokens)
                            + ", output_tokens=" + displayTokenCount(outputTokens)
                            + ", total_tokens="
                            + displayTokenCount(totalTokens)
                            + ", cached_tokens=" + displayTokenCount(cachedTokens));
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
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install SubScreenActivity.onCreate hook", error);
        }
    }

    private void installChatRouteHook(ClassLoader loader, HostAbi abi) {
        if (abi.minified) {
            installMinifiedMainRouteHooks(loader);
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

    private void installMinifiedMainRouteHooks(ClassLoader loader) {
        int installed = 0;
        for (char suffix = 'a'; suffix <= 'x'; suffix++) {
            try {
                Class<?> contentClass = Class.forName(
                        MAIN_ACTIVITY_CLASS + "$" + suffix, false, loader);
                Method invoke = findFunction1Invoke(contentClass);
                boolean aiChat = suffix == 'j';
                hook(invoke).intercept(chain -> {
                    InjectedUiController.onMainRouteComposed(aiChat);
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
                                    ManualCompressionManager.postCompressionStatus(
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

    private void installUiHooks(ClassLoader loader) {
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
            Class<?> arrowClass = findArrowPreferenceClass(loader);
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
                        copiedArgs[0] = "模型缓存增强";
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

    private static Class<?> findArrowPreferenceClass(ClassLoader loader)
            throws ClassNotFoundException {
        ClassNotFoundException last = null;
        String[] candidates = {ARROW_PREFERENCE_CLASS, "ex"};
        for (String candidate : candidates) {
            try {
                return Class.forName(candidate, false, loader);
            } catch (ClassNotFoundException error) {
                last = error;
            }
        }
        throw last == null ? new ClassNotFoundException(ARROW_PREFERENCE_CLASS) : last;
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
        return "OpenAI Prompt Cache 策略";
    }
    private static String buildCacheKey(String model, String systemPrompt) {
        String prompt = systemPrompt == null ? "" : systemPrompt;
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