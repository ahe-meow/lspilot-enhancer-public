package com.lspilot.enhancer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.lang.reflect.Proxy;

/** Bridges the module settings into the host's native Miuix Compose settings navigation. */
final class HostNativeSettings {
    private static final String TITLE = "模型请求增强";
    private static final String[] REASONING_LABELS = {
            "low", "Medium", "high", "xhigh", "max", "ultra"
    };
    private static final String[] REASONING_VALUES = {
            "low", "medium", "high", "xhigh", "max", "ultra"
    };
    private static final ThreadLocal<Boolean> RENDERING_PAGE = new ThreadLocal<>();
    private static final Map<String, Object> STATES = new LinkedHashMap<>();

    private static ClassLoader loader;
    private static Class<?> function0Class;
    private static Class<?> function1Class;
    private static Class<?> function2Class;
    private static Class<?> function3Class;
    private static Object kotlinUnit;
    private static Object baseModifier;
    private static Object roundedIcons;
    private static Object theme;
    private static int themeChanged;

    private static Method settingsPagerMethod;
    private static Method settingsListMethod;
    private static Method aboutScreenMethod;
    private static Method topAppBarMethod;
    private static Method arrowPreferenceMethod;
    private static Method cardMethod;
    private static Method switchPreferenceMethod;
    private static Method dropdownPreferenceMethod;
    private static Method lazyItemMethod;
    private static Method mutableStateFactory;
    private static Method autoAwesomeVectorMethod;
    private static Method dpMethod;
    private static Method modifierPaddingMethod;
    private static Method modifierHeightMethod;
    private static Method spacerMethod;
    private static Method themeColorsMethod;
    private static Method iconColorMethod;
    private static Method iconRenderMethod;

    private static volatile Object settingsState;
    private static volatile Object settingsActions;
    private static volatile float settingsPadding;
    private static volatile boolean pageRequested;
    private static volatile boolean pageRendered;

    private HostNativeSettings() {}

    static void resolve(ClassLoader hostLoader, Method arrowPreference) throws Exception {
        loader = hostLoader;
        function0Class = hostLoader.loadClass("kotlin.jvm.functions.Function0");
        function1Class = hostLoader.loadClass("kotlin.jvm.functions.Function1");
        function2Class = hostLoader.loadClass("kotlin.jvm.functions.Function2");
        function3Class = hostLoader.loadClass("kotlin.jvm.functions.Function3");
        kotlinUnit = hostLoader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        arrowPreferenceMethod = accessible(arrowPreference);

        String arrowComposerName = arrowPreference.getParameterTypes()[12].getName();
        boolean currentHost = "xg2".equals(arrowComposerName);
        if (!currentHost && !"vg2".equals(arrowComposerName)) {
            throw new NoSuchMethodException(
                    "Unsupported host Compose ABI=" + arrowComposerName);
        }

        Class<?> settingsClass = hostLoader.loadClass(currentHost ? "p1b" : "l1b");
        settingsPagerMethod = findStatic(settingsClass, "l", 5,
                types -> types[2] == float.class);
        Class<?> composerClass = settingsPagerMethod.getParameterTypes()[3];
        settingsListMethod = findStatic(settingsClass, "n", 3,
                types -> true);
        aboutScreenMethod = findStatic(hostLoader.loadClass("a2"), "f", 2,
                types -> types[0] == composerClass && types[1] == int.class);
        topAppBarMethod = findStatic(
                hostLoader.loadClass(currentHost ? "epc" : "apc"), "B", 20,
                types -> types[0] == String.class && types[16] == composerClass);

        Class<?> lazyBuilderClass = settingsListMethod.getParameterTypes()[2];
        lazyItemMethod = findStatic(lazyBuilderClass, "a", 6,
                types -> types[0] == lazyBuilderClass
                        && types[3] == function3Class && types[4] == int.class);
        cardMethod = findStatic(
                hostLoader.loadClass(currentHost ? "e01" : "c01"), "k", 8,
                types -> types[4] == function3Class && types[5] == composerClass);
        switchPreferenceMethod = findStatic(
                hostLoader.loadClass(currentHost ? "fyb" : "byb"), "d", 18,
                types -> types[0] == boolean.class && types[1] == function1Class
                        && types[2] == String.class && types[14] == composerClass);
        dropdownPreferenceMethod = findStatic(
                hostLoader.loadClass(currentHost ? "om8" : "mm8"), "i", 21,
                types -> types[0] == List.class && types[1] == int.class
                        && types[2] == String.class && types[15] == function1Class
                        && types[17] == composerClass);

        Class<?> modifierClass = arrowPreference.getParameterTypes()[1];
        baseModifier = singleton(modifierClass, "a");
        dpMethod = findStatic(hostLoader.loadClass(currentHost ? "nj3" : "lj3"), "m", 1,
                types -> types[0] == float.class);
        modifierPaddingMethod = findStatic(
                hostLoader.loadClass(currentHost ? "so8" : "qo8"), "q", 7,
                types -> types[0].isAssignableFrom(baseModifier.getClass())
                        || types[0] == modifierClass);
        modifierHeightMethod = findStatic(
                hostLoader.loadClass(currentHost ? "q7b" : "m7b"), "i", 2,
                types -> types[1] == float.class);
        spacerMethod = findStatic(
                hostLoader.loadClass(currentHost ? "dhb" : "zgb"), "a", 3,
                types -> types[1] == composerClass && types[2] == int.class);

        Class<?> statePolicyClass = hostLoader.loadClass(currentHost ? "odb" : "kdb");
        mutableStateFactory = findStatic(
                hostLoader.loadClass(currentHost ? "qdb" : "mdb"), "j", 4,
                types -> types[0] == Object.class && types[1] == statePolicyClass
                        && types[2] == int.class && types[3] == Object.class);

        Class<?> autoAwesomeClass = hostLoader.loadClass("sz");
        autoAwesomeVectorMethod = findStatic(autoAwesomeClass, "a", 1,
                types -> !types[0].isPrimitive());
        Class<?> roundedClass = autoAwesomeVectorMethod.getParameterTypes()[0];
        roundedIcons = singleton(roundedClass, "a");

        Class<?> themeClass = hostLoader.loadClass(currentHost ? "ka7" : "ia7");
        theme = singleton(themeClass, "a");
        Field themeChangedField = themeClass.getDeclaredField("b");
        themeChangedField.setAccessible(true);
        themeChanged = themeChangedField.getInt(null);
        themeColorsMethod = accessible(themeClass.getDeclaredMethod("a", composerClass, int.class));
        iconColorMethod = accessible(themeColorsMethod.getReturnType().getDeclaredMethod("q"));
        iconRenderMethod = findStatic(
                hostLoader.loadClass(currentHost ? "y45" : "w45"), "d", 7,
                types -> types[1] == String.class && types[3] == long.class
                        && types[4] == composerClass);
    }

    static Method settingsPagerMethod() {
        return settingsPagerMethod;
    }

    static Method settingsListMethod() {
        return settingsListMethod;
    }

    static Method aboutScreenMethod() {
        return aboutScreenMethod;
    }

    static Method topAppBarMethod() {
        return topAppBarMethod;
    }

    static boolean isRenderingPage() {
        return Boolean.TRUE.equals(RENDERING_PAGE.get());
    }

    static void captureSettings(Object state, Object actions, float padding) throws Exception {
        if (!isRenderingPage() && pageRendered) {
            pageRequested = false;
            pageRendered = false;
        }
        settingsState = state;
        settingsActions = actions;
        settingsPadding = padding;
    }

    static boolean shouldRenderPage() {
        return pageRequested && settingsState != null && settingsActions != null;
    }

    static Object renderPage(Object composer) throws Exception {
        pageRendered = true;
        syncStates();
        RENDERING_PAGE.set(Boolean.TRUE);
        try {
            invoke(settingsPagerMethod, null,
                    settingsState, settingsActions, settingsPadding, composer, 0);
            return null;
        } finally {
            RENDERING_PAGE.remove();
        }
    }

    static void addEntry(Object lazyBuilder) throws Exception {
        addItem(lazyBuilder, composer -> renderCard(composer, true));
    }

    static Object addSettings(Object lazyBuilder) throws Exception {
        syncStates();
        addItem(lazyBuilder, composer -> renderCard(composer, false));
        return kotlinUnit;
    }

    static void requestPageForCheck() {
        pageRequested = true;
        pageRendered = false;
    }

    static void markPageRenderedForCheck() {
        pageRendered = true;
    }

    static void markHostSettingsRenderedForCheck() {
        if (pageRendered) {
            pageRequested = false;
            pageRendered = false;
        }
    }

    static boolean pageRequestedForCheck() {
        return pageRequested;
    }

    private static void addItem(Object builder, ComposerContent content) throws Exception {
        Object item = proxy(function3Class, "LSPilotEnhancerLazyItem", args -> {
            content.render(args[1]);
            return kotlinUnit;
        });
        invoke(lazyItemMethod, null, builder, null, null, item, 3, null);
    }

    private static void renderCard(Object composer, boolean entry) throws Exception {
        renderSpacing(composer);
        Object content = proxy(function3Class,
                entry ? "LSPilotEnhancerEntryCard" : "LSPilotEnhancerSettingsCard",
                args -> {
                    if (entry) renderEntry(args[1]);
                    else renderSettings(args[1]);
                    return kotlinUnit;
                });
        invoke(cardMethod, null, baseModifier, 0f, null, null,
                content, composer, 0x6006, 0xe);
    }

    private static void renderSpacing(Object composer) throws Exception {
        float height = ((Number) invoke(dpMethod, null, 12f)).floatValue();
        Object modifier = invoke(modifierHeightMethod, null, baseModifier, height);
        invoke(spacerMethod, null, modifier, composer, 6);
    }

    private static void renderEntry(Object composer) throws Exception {
        Object icon = proxy(function2Class, "LSPilotEnhancerAutoAwesome", args -> {
            renderAutoAwesome(args[0]);
            return kotlinUnit;
        });
        Object click = proxy(function0Class, "LSPilotEnhancerOpenSettings", args -> {
            openPage();
            return kotlinUnit;
        });
        renderArrow(composer, TITLE, "推理强度、Prompt Cache 与上下文策略", icon, click);
    }

    private static void renderSettings(Object composer) throws Exception {
        renderSwitch(composer, ModuleSettings.KEY_ENABLED, "启用模型请求增强",
                "关闭后保留 Hook，但不修改请求");
        renderReasoning(composer);
        renderSwitch(composer, ModuleSettings.KEY_CACHE_KEY, "稳定缓存路由键",
                "按模型与系统提示词生成无明文 prompt_cache_key");
        renderSwitch(composer, ModuleSettings.KEY_RETENTION, "24 小时缓存保留",
                "适用于所有具名模型请求");
        renderSwitch(composer, ModuleSettings.KEY_INCLUDE_USAGE, "请求缓存用量统计",
                "让流式响应返回 cached_tokens 等 usage 信息");
        renderSwitch(composer, ModuleSettings.KEY_DEBUG_LOG, "诊断日志",
                "记录 Hook、缓存和 ABI 阶段信息，不记录消息正文或密钥");
        renderSwitch(composer, ModuleSettings.KEY_VERBOSE_DEBUG_LOG, "详细诊断日志",
                "仅在诊断日志开启时记录反射调用、线程和状态变化");
        Object reset = proxy(function0Class, "LSPilotEnhancerReset", args -> {
            ModuleSettings.resetPolicy();
            syncStates();
            return kotlinUnit;
        });
        renderArrow(composer, "恢复默认策略", "重置模型请求增强设置", null, reset);
    }

    private static void renderSwitch(Object composer, String key, String title, String summary)
            throws Exception {
        boolean available = ModuleSettings.isSettingAvailable(key);
        Object state = STATES.get(key);
        boolean checked = available && Boolean.TRUE.equals(getState(state));
        String detail = available ? summary : summary + "\n已禁用：" + ModuleSettings.disabledReason(key);
        Object changed = proxy(function1Class, "LSPilotEnhancerSwitch:" + key, args -> {
            if (available && args != null && args.length > 0 && args[0] instanceof Boolean) {
                Boolean value = (Boolean) args[0];
                ModuleSettings.putBoolean(key, value);
                setState(state, value);
            }
            return kotlinUnit;
        });
        invoke(switchPreferenceMethod, null,
                checked, changed, title, null, null, detail, null,
                null, null, null, null, null, false, false,
                composer, 0x301b0, 0, 0x3fd8);
    }

    private static void renderReasoning(Object composer) throws Exception {
        Object state = STATES.get(ModuleSettings.KEY_REASONING_EFFORT);
        int selected = ((Number) getState(state)).intValue();
        boolean available = ModuleSettings.isSettingAvailable(ModuleSettings.KEY_REASONING_EFFORT);
        String summary = available ? "设置模型 reasoning_effort"
                : "设置模型 reasoning_effort\n已禁用："
                        + ModuleSettings.disabledReason(ModuleSettings.KEY_REASONING_EFFORT);
        Object changed = proxy(function1Class, "LSPilotEnhancerReasoning", args -> {
            if (available && args != null && args.length > 0 && args[0] instanceof Number) {
                int index = ((Number) args[0]).intValue();
                if (index >= 0 && index < REASONING_VALUES.length) {
                    ModuleSettings.setReasoningEffort(REASONING_VALUES[index]);
                    setState(state, index);
                }
            }
            return kotlinUnit;
        });
        invoke(dropdownPreferenceMethod, null,
                Arrays.asList(REASONING_LABELS), selected, "模型推理强度",
                null, null, summary, null, null, null, null, null, null,
                false, false, false, changed, null,
                composer, 0x30186, 0, 0xffd8);
    }

    private static void renderArrow(Object composer, String title, String summary,
            Object icon, Object click) throws Exception {
        invoke(arrowPreferenceMethod, null,
                title, null, null, summary, null, icon, null, null, null, click,
                false, false, composer, 0x30c06, 0, 0xdd6);
    }

    private static void renderAutoAwesome(Object composer) throws Exception {
        Object vector = invoke(autoAwesomeVectorMethod, null, roundedIcons);
        float padding = ((Number) invoke(dpMethod, null, 6f)).floatValue();
        Object modifier = invoke(modifierPaddingMethod, null,
                baseModifier, 0f, 0f, padding, 0f, 11, null);
        Object colors = invoke(themeColorsMethod, theme, composer, themeChanged);
        long color = ((Number) invoke(iconColorMethod, colors)).longValue();
        invoke(iconRenderMethod, null,
                vector, TITLE, modifier, color, composer, 0x1b0, 0);
    }

    private static void openPage() throws Exception {
        Object actions = settingsActions;
        if (actions == null) return;
        Method aboutActionMethod = accessible(actions.getClass().getDeclaredMethod("a"));
        Object aboutAction = invoke(aboutActionMethod, actions);
        requestPageForCheck();
        invoke(aboutAction.getClass().getMethod("invoke"), aboutAction);
    }

    private static void syncStates() throws Exception {
        if (STATES.isEmpty()) {
            STATES.put(ModuleSettings.KEY_ENABLED, newState(ModuleSettings.isEnabled()));
            STATES.put(ModuleSettings.KEY_CACHE_KEY, newState(ModuleSettings.isCacheKeyEnabled()));
            STATES.put(ModuleSettings.KEY_RETENTION, newState(ModuleSettings.isRetentionEnabled()));
            STATES.put(ModuleSettings.KEY_INCLUDE_USAGE,
                    newState(ModuleSettings.isIncludeUsageEnabled()));
            STATES.put(ModuleSettings.KEY_DEBUG_LOG, newState(ModuleSettings.isDebugLogEnabled()));
            STATES.put(ModuleSettings.KEY_VERBOSE_DEBUG_LOG,
                    newState(ModuleSettings.isVerboseDebugLogEnabled()));
            STATES.put(ModuleSettings.KEY_REASONING_EFFORT,
                    newState(reasoningIndex(ModuleSettings.getReasoningEffort())));
            return;
        }
        setState(STATES.get(ModuleSettings.KEY_ENABLED), ModuleSettings.isEnabled());
        setState(STATES.get(ModuleSettings.KEY_CACHE_KEY), ModuleSettings.isCacheKeyEnabled());
        setState(STATES.get(ModuleSettings.KEY_RETENTION), ModuleSettings.isRetentionEnabled());
        setState(STATES.get(ModuleSettings.KEY_INCLUDE_USAGE),
                ModuleSettings.isIncludeUsageEnabled());
        setState(STATES.get(ModuleSettings.KEY_DEBUG_LOG), ModuleSettings.isDebugLogEnabled());
        setState(STATES.get(ModuleSettings.KEY_VERBOSE_DEBUG_LOG),
                ModuleSettings.isVerboseDebugLogEnabled());
        setState(STATES.get(ModuleSettings.KEY_REASONING_EFFORT),
                reasoningIndex(ModuleSettings.getReasoningEffort()));
    }

    private static int reasoningIndex(String value) {
        for (int index = 0; index < REASONING_VALUES.length; index++) {
            if (REASONING_VALUES[index].equals(value)) return index;
        }
        return 1;
    }

    private static Object newState(Object value) throws Exception {
        return invoke(mutableStateFactory, null, value, null, 2, null);
    }

    private static Object getState(Object state) throws Exception {
        return invoke(state.getClass().getMethod("getValue"), state);
    }

    private static void setState(Object state, Object value) throws Exception {
        invoke(state.getClass().getMethod("setValue", Object.class), state, value);
    }

    private static Object singleton(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Method findStatic(Class<?> type, String name, int parameterCount,
            TypePredicate predicate) throws NoSuchMethodException {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && Modifier.isStatic(method.getModifiers())
                    && method.getParameterTypes().length == parameterCount
                    && predicate.matches(method.getParameterTypes())) {
                return accessible(method);
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name
                + " parameterCount=" + parameterCount);
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }

    private static Object proxy(Class<?> contract, String name, ProxyBody body) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{contract}, (proxy, method, args) -> {
            if ("invoke".equals(method.getName())) return body.invoke(args);
            if ("toString".equals(method.getName())) return name;
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) {
                return args != null && args.length == 1 && proxy == args[0];
            }
            return null;
        });
    }

    private interface ComposerContent {
        void render(Object composer) throws Exception;
    }

    private interface ProxyBody {
        Object invoke(Object[] args) throws Exception;
    }

    private interface TypePredicate {
        boolean matches(Class<?>[] types);
    }
}
