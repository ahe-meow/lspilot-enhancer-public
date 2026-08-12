package dev.operit.lspilot.enhancer;

import android.content.res.Configuration;
import android.graphics.Color;
import android.util.Log;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class NativeChatTopBarAction {
    private static final String TAG = "LSPilotEnhancer";
    private static final int ACTION_GROUP_KEY = 0x4c535041;
    private static final int PANEL_GROUP_KEY = 0x4c535050;
    private static volatile ActionBridge actionBridge;
    private static volatile PanelBridge panelBridge;

    private NativeChatTopBarAction() {}

    static void renderAction(ClassLoader loader, Object composer) {
        if (loader == null || composer == null) return;
        try { getAction(loader).render(composer); }
        catch (Throwable e) { Log.e(TAG, "compose chat action failed", e); }
    }

    static void renderPanel(ClassLoader loader, Object composer) {
        if (loader == null || composer == null) return;
        try { getPanel(loader).render(composer); }
        catch (Throwable e) { Log.e(TAG, "compose chat panel failed", e); }
    }

    static void refreshPanel() {
        refreshPanel(true);
    }

    static void refreshPanel(boolean allowShow) {
        if (!allowShow) return;
        try {
            PanelBridge bridge = panelBridge;
            if (bridge != null) bridge.set(true);
        } catch (Throwable error) {
            Log.e(TAG, "compose compression panel refresh failed", error);
        }
    }

    static void showDialog() {
        Log.i(TAG, "native TopAppBar compression icon clicked");
        InjectedUiController.showChatCompressionDialog();
    }

    static void showResult() {
        showDialog();
    }

    private static ActionBridge getAction(ClassLoader loader) throws Exception {
        synchronized (NativeChatTopBarAction.class) {
            if (actionBridge == null || actionBridge.loader != loader) actionBridge = ActionBridge.create(loader);
            return actionBridge;
        }
    }

    private static PanelBridge getPanel(ClassLoader loader) throws Exception {
        synchronized (NativeChatTopBarAction.class) {
            if (panelBridge == null || panelBridge.loader != loader) panelBridge = PanelBridge.create(loader);
            return panelBridge;
        }
    }

    private static Class<?> c(ClassLoader loader, String name) throws Exception {
        return Class.forName(name, false, loader);
    }

    private static Method m(ClassLoader loader, String className, String name, Class<?>... params) throws Exception {
        Method method = c(loader, className).getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }

    private static Object unit(ClassLoader loader) throws Exception {
        return c(loader, "kotlin.Unit").getField("INSTANCE").get(null);
    }

    private static Object obj(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if ("toString".equals(name)) return "LSPilotEnhancer";
        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
        if ("equals".equals(name)) return args != null && args.length == 1 && proxy == args[0];
        return null;
    }

    private abstract static class BaseBridge {
        final ClassLoader loader;
        final Method startRestartGroup;
        final Method endRestartGroup;
        final Method updateScope;
        final Object showState;

        BaseBridge(ClassLoader loader, Method startRestartGroup, Method endRestartGroup, Method updateScope, Object showState) {
            this.loader = loader;
            this.startRestartGroup = startRestartGroup;
            this.endRestartGroup = endRestartGroup;
            this.updateScope = updateScope;
            this.showState = showState;
        }

        Object f0(Run run) throws Exception {
            return Proxy.newProxyInstance(loader, new Class<?>[]{c(loader, "kotlin.jvm.functions.Function0")},
                    (proxy, method, args) -> { if (!"invoke".equals(method.getName())) return obj(proxy, method, args); run.run(); return unit(loader); });
        }

        Object f2(One run) throws Exception {
            return Proxy.newProxyInstance(loader, new Class<?>[]{c(loader, "kotlin.jvm.functions.Function2")},
                    (proxy, method, args) -> { if (!"invoke".equals(method.getName())) return obj(proxy, method, args); run.run(args == null ? null : args[0]); return unit(loader); });
        }

        boolean visible() throws Exception {
            return Boolean.TRUE.equals(showState.getClass().getMethod("getValue").invoke(showState));
        }

        void set(boolean value) throws Exception {
            showState.getClass().getMethod("setValue", Object.class).invoke(showState, value);
        }

        void update(Object scope, One render) throws Exception {
            if (scope != null) updateScope.invoke(scope, f2(render));
        }
    }

    private static final class ActionBridge extends BaseBridge {
        final Method iconButton;
        final Method icon;
        final Method composeColor;
        final Object iconValue;
        final Object click;

        ActionBridge(ClassLoader loader, Method start, Method end, Method update, Object state,
                     Method iconButton, Method icon, Method composeColor, Object iconValue, Object click) {
            super(loader, start, end, update, state);
            this.iconButton = iconButton;
            this.icon = icon;
            this.composeColor = composeColor;
            this.iconValue = iconValue;
            this.click = click;
        }

        static ActionBridge create(ClassLoader loader) throws Exception {
            Class<?> f0 = c(loader, "kotlin.jvm.functions.Function0");
            Class<?> f2 = c(loader, "kotlin.jvm.functions.Function2");
            Class<?> modifier = c(loader, "androidx.compose.ui.Modifier");
            Class<?> composer = c(loader, "androidx.compose.runtime.Composer");
            Class<?> imageVector = c(loader, "androidx.compose.ui.graphics.vector.ImageVector");
            Method start = composer.getMethod("startRestartGroup", int.class);
            Method end = composer.getMethod("endRestartGroup");
            Method update = c(loader, "androidx.compose.runtime.ScopeUpdateScope").getMethod("updateScope", f2);
            Method iconButton = m(loader, "top.yukonga.miuix.kmp.basic.IconButtonKt", "IconButton-0xlP5wg",
                    f0, modifier, boolean.class, boolean.class, long.class, float.class, float.class, float.class,
                    f2, composer, int.class, int.class);
            Method icon = m(loader, "androidx.compose.material3.IconKt", "Icon-ww6aTOc",
                    imageVector, String.class, modifier, long.class, composer, int.class, int.class);
            Method composeColor = m(loader, "androidx.compose.ui.graphics.ColorKt", "Color", int.class);
            Class<?> filled = c(loader, "androidx.compose.material.icons.Icons$Filled");
            Object filledInstance = filled.getField("INSTANCE").get(null);
            Object compressIcon = c(loader, "androidx.compose.material.icons.filled.CompressKt")
                    .getDeclaredMethod("getCompress", filled).invoke(null, filledInstance);
            Object state = c(loader, "androidx.compose.runtime.SnapshotStateKt")
                    .getDeclaredMethod("mutableStateOf$default", Object.class,
                            c(loader, "androidx.compose.runtime.SnapshotMutationPolicy"), int.class, Object.class)
                    .invoke(null, false, null, 2, null);
            ActionBridge seed = new ActionBridge(loader, start, end, update, state, iconButton, icon, composeColor, compressIcon, null);
            Object click = seed.f0(() -> NativeChatTopBarAction.showDialog());
            return new ActionBridge(loader, start, end, update, state, iconButton, icon, composeColor, compressIcon, click);
        }

        long iconTint() throws Exception {
            android.app.Activity activity = InjectedUiController.currentChatActivity();
            boolean dark = activity != null && (activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int argb = dark ? Color.WHITE : Color.rgb(35, 35, 35);
            return (Long) composeColor.invoke(null, argb);
        }

        void render(Object parentComposer) throws Exception {
            Object composer = startRestartGroup.invoke(parentComposer, ACTION_GROUP_KEY);
            iconButton.invoke(null, click, null, true, false, 0L, 0f, 0f, 0f,
                    f2(arg -> { if (arg != null) icon.invoke(null, iconValue, "压缩上下文", null, iconTint(), arg, 0, 0x04); }),
                    composer, 0x6000000, 0xfe);
            Object scope = endRestartGroup.invoke(composer);
            update(scope, this::render);
        }
    }

    private static final class PanelBridge extends BaseBridge {
        final Method textButton;
        final Method text;

        PanelBridge(ClassLoader loader, Method start, Method end, Method update, Object state, Method textButton, Method text) {
            super(loader, start, end, update, state);
            this.textButton = textButton;
            this.text = text;
        }

        static PanelBridge create(ClassLoader loader) throws Exception {
            Class<?> f0 = c(loader, "kotlin.jvm.functions.Function0");
            Class<?> f2 = c(loader, "kotlin.jvm.functions.Function2");
            Class<?> modifier = c(loader, "androidx.compose.ui.Modifier");
            Class<?> composer = c(loader, "androidx.compose.runtime.Composer");
            Method start = composer.getMethod("startRestartGroup", int.class);
            Method end = composer.getMethod("endRestartGroup");
            Method update = c(loader, "androidx.compose.runtime.ScopeUpdateScope").getMethod("updateScope", f2);
            Method textButton = m(loader, "top.yukonga.miuix.kmp.basic.ButtonKt", "TextButton-v3dQN_U",
                    String.class, f0, modifier, boolean.class, float.class, float.class, float.class,
                    c(loader, "top.yukonga.miuix.kmp.basic.TextButtonColors"),
                    c(loader, "androidx.compose.foundation.layout.PaddingValues"),
                    c(loader, "androidx.compose.ui.text.TextStyle"),
                    c(loader, "androidx.compose.foundation.interaction.MutableInteractionSource"),
                    c(loader, "androidx.compose.foundation.Indication"),
                    composer, int.class, int.class, int.class);
            Method text = m(loader, "top.yukonga.miuix.kmp.basic.TextKt", "Text-Nvy7gAk",
                    String.class, modifier, long.class, c(loader, "androidx.compose.foundation.text.TextAutoSize"),
                    long.class, c(loader, "androidx.compose.ui.text.font.FontStyle"),
                    c(loader, "androidx.compose.ui.text.font.FontWeight"), c(loader, "androidx.compose.ui.text.font.FontFamily"),
                    long.class, c(loader, "androidx.compose.ui.text.style.TextDecoration"),
                    c(loader, "androidx.compose.ui.text.style.TextAlign"), long.class, int.class, boolean.class,
                    int.class, int.class, c(loader, "kotlin.jvm.functions.Function1"),
                    c(loader, "androidx.compose.ui.text.TextStyle"), composer, int.class, int.class, int.class);
            Object state = c(loader, "androidx.compose.runtime.SnapshotStateKt")
                    .getDeclaredMethod("mutableStateOf$default", Object.class,
                            c(loader, "androidx.compose.runtime.SnapshotMutationPolicy"), int.class, Object.class)
                    .invoke(null, false, null, 2, null);
            return new PanelBridge(loader, start, end, update, state, textButton, text);
        }

        void render(Object parentComposer) throws Exception {
            Object composer = startRestartGroup.invoke(parentComposer, PANEL_GROUP_KEY);
            if (visible()) {
                ManualCompressionManager.Result result = ManualCompressionManager.getLastResult();
                drawText("上下文压缩", composer);
                drawText(panelMessage(result), composer);
                CompressionStateMachine.Action action = InjectedUiController.primaryCompressionAction();
                long taskId = ManualCompressionManager.currentCompressionTaskId();
                String chatId = ManualCompressionManager.currentCompressionChatId();
                String button = action != null ? InjectedUiController.compressionActionLabel(action)
                        : (ManualCompressionManager.isPreparing() ? "压缩中" : (result != null && result.success ? "关闭" : "开始压缩"));
                textButton.invoke(null, button,
                        f0(() -> {
                            if (action != null) {
                                if (InjectedUiController.runCompressionAction(action, taskId, chatId)) set(false);
                                return;
                            }
                            if (ManualCompressionManager.isPreparing()) return;
                            ManualCompressionManager.Result current = ManualCompressionManager.getLastResult();
                            if (current != null && current.success) set(false);
                            else InjectedUiController.startNativeCompression();
                        }),
                        null, action != null || !ManualCompressionManager.isPreparing(), 0f, 0f, 0f,
                        null, null, null, null, null, composer, 0, 0, 0xffe);
            }
            Object scope = endRestartGroup.invoke(composer);
            update(scope, this::render);
        }

        String panelMessage(ManualCompressionManager.Result result) {
            if (ManualCompressionManager.isPreparing()) return "正在压缩当前对话上下文，请稍候。";
            if (result != null) return result.message;
            ManualCompressionManager.ScreenState screen = ManualCompressionManager.getCurrentScreen();
            int count = screen == null ? 0 : screen.messageCount;
            int keep = ModuleSettings.getManualKeepRecent();
            return "当前共 " + count + " 条消息。将压缩较早历史，保留最近 " + keep + " 条；摘要会作为本会话长期基线持续复用，原始聊天记录不会被修改。";
        }

        void drawText(String value, Object composer) throws Exception {
            text.invoke(null, value, null, 0L, null, 0L, null, null, null, 0L, null, null, 0L,
                    0, false, 0, 0, null, null, composer, 0, 0, 0);
        }
    }

    interface Run { void run() throws Exception; }
    interface One { void run(Object value) throws Exception; }
}
