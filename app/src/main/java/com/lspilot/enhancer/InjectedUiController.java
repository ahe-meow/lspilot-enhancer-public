package com.lspilot.enhancer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Appends the module settings entry without changing host chat UI. */
final class InjectedUiController {
    private static final String TAG = "LSPilotEnhancer";
    private static volatile WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static volatile WeakReference<Activity> dialogActivityRef =
            new WeakReference<>(null);
    private static volatile boolean requestHookInstalled;
    private static volatile AlertDialog settingsDialog;

    private InjectedUiController() {}

    static void prepare(Activity activity) {
        if (activity == null) return;
        Activity previous = activityRef.get();
        if (previous != null && previous != activity) dismissDialogOwnedBy(previous);
        ModuleSettings.initialize(activity);
        activityRef = new WeakReference<>(activity);
    }

    static void setActiveActivity(Activity activity) {
        if (isActivityUsable(activity)) {
            activityRef = new WeakReference<>(activity);
            ModuleSettings.initialize(activity);
            DebugLogger.d("active activity=" + activity.getClass().getName());
        }
    }

    static void attach(Activity activity) {
        prepare(activity);
        if (requestHookInstalled && ModuleSettings.shouldShowSuccessNotice()) {
            ModuleSettings.markSuccessNoticeShown();
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "模型请求增强已加载，可在 LSPilot 设置中配置",
                    Toast.LENGTH_LONG).show());
        }
    }

    static void setRequestHookInstalled(boolean installed) {
        requestHookInstalled = installed;
    }

    static Object createSettingsClickAction(ClassLoader loader) throws Exception {
        Class<?> function0Class = Class.forName("kotlin.jvm.functions.Function0", false, loader);
        return Proxy.newProxyInstance(loader, new Class<?>[]{function0Class},
                (proxy, method, args) -> {
                    if ("invoke".equals(method.getName())) {
                        openSettings();
                        return kotlinUnit(loader);
                    }
                    return handleProxyObjectMethod(proxy, method, args);
                });
    }

    private static Object kotlinUnit(ClassLoader loader) throws Exception {
        Class<?> unitClass = Class.forName("kotlin.Unit", false, loader);
        return unitClass.getField("INSTANCE").get(null);
    }

    private static Object handleProxyObjectMethod(Object proxy, Method method, Object[] args) {
        if ("toString".equals(method.getName())) return "LSPilotEnhancerSettings";
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(method.getName())) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        return null;
    }

    private static void openSettings() {
        Activity activity = activityRef.get();
        if (!isActivityUsable(activity)) {
            android.util.Log.w(TAG, "Settings entry clicked without a usable Activity");
            return;
        }
        activity.runOnUiThread(() -> {
            if (!isActivityUsable(activity)) return;
            try {
                showSettingsDialog(activity);
            } catch (Throwable error) {
                android.util.Log.e(TAG, "Failed to show settings dialog", error);
            }
        });
    }

    private static void showSettingsDialog(Activity activity) {
        AlertDialog old = settingsDialog;
        Activity owner = dialogActivityRef.get();
        if (old != null && old.isShowing()) {
            if (owner == activity) return;
            try {
                old.dismiss();
            } catch (Throwable ignored) {
                // The previous Activity window may already be gone.
            }
        }

        int padding = dp(activity, 20);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, dp(activity, 8), padding, dp(activity, 8));

        TextView status = new TextView(activity);
        String unavailable = ModuleSettings.unavailableSummary();
        status.setText(requestHookInstalled
                ? (unavailable.isEmpty() ? "状态：请求 Hook 已加载"
                        : "状态：请求 Hook 已加载，部分功能已禁用\n" + unavailable)
                : (unavailable.isEmpty() ? "状态：请求 Hook 未加载"
                        : "状态：请求 Hook 未加载\n" + unavailable));
        status.setTextColor(requestHookInstalled && unavailable.isEmpty()
                ? Color.rgb(32, 140, 74) : Color.rgb(190, 55, 55));
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextSize(14);
        status.setPadding(0, 0, 0, dp(activity, 8));
        content.addView(status, matchWrap());

        Switch master = addSwitch(content, activity, "启用模型请求增强",
                "关闭后保留 Hook，但不修改请求",
                ModuleSettings.KEY_ENABLED, ModuleSettings.isEnabled());
        String[] reasoningLabels = {"low", "Medium", "high", "xhigh", "max", "ultra"};
        String[] reasoningValues = {"low", "medium", "high", "xhigh", "max", "ultra"};
        TextView reasoningEffort = addChoiceSetting(content, activity,
                "模型推理强度", ModuleSettings.KEY_REASONING_EFFORT,
                reasoningLabels, reasoningValues,
                ModuleSettings::getReasoningEffort, ModuleSettings::setReasoningEffort);
        Switch cacheKey = addSwitch(content, activity, "稳定缓存路由键",
                "按模型与系统提示词生成无明文 prompt_cache_key",
                ModuleSettings.KEY_CACHE_KEY, ModuleSettings.isCacheKeyEnabled());
        Switch retention = addSwitch(content, activity, "24 小时缓存保留",
                "仅对白名单中的 GPT-4.1、GPT-5、o1/o3/o4 系列启用",
                ModuleSettings.KEY_RETENTION, ModuleSettings.isRetentionEnabled());
        Switch usage = addSwitch(content, activity, "请求缓存用量统计",
                "让流式响应返回 cached_tokens 等 usage 信息",
                ModuleSettings.KEY_INCLUDE_USAGE, ModuleSettings.isIncludeUsageEnabled());
        Switch debug = addSwitch(content, activity, "诊断日志",
                "记录请求 Hook、缓存和 ABI 阶段信息，不记录消息正文或密钥",
                ModuleSettings.KEY_DEBUG_LOG, ModuleSettings.isDebugLogEnabled());
        Switch verboseDebug = addSwitch(content, activity, "详细诊断日志",
                "记录反射调用、线程和状态变化，仅在诊断日志开启时生效",
                ModuleSettings.KEY_VERBOSE_DEBUG_LOG, ModuleSettings.isVerboseDebugLogEnabled());

        Button reset = new Button(activity);
        reset.setText("恢复默认策略");
        reset.setAllCaps(false);
        reset.setOnClickListener(view -> {
            ModuleSettings.resetPolicy();
            master.setChecked(ModuleSettings.isEnabled());
            cacheKey.setChecked(ModuleSettings.isCacheKeyEnabled());
            retention.setChecked(ModuleSettings.isRetentionEnabled());
            usage.setChecked(ModuleSettings.isIncludeUsageEnabled());
            reasoningEffort.setText(choiceSettingText("模型推理强度",
                    ModuleSettings.getReasoningEffort(), reasoningLabels, reasoningValues));
            debug.setChecked(false);
            verboseDebug.setChecked(false);
            Toast.makeText(activity, "已恢复默认策略", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams resetParams = matchWrap();
        resetParams.topMargin = dp(activity, 8);
        content.addView(reset, resetParams);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("模型请求增强")
                .setView(scrollView)
                .setPositiveButton("完成", null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            if (settingsDialog == dialog) {
                settingsDialog = null;
                dialogActivityRef = new WeakReference<>(null);
            }
        });
        settingsDialog = dialog;
        dialogActivityRef = new WeakReference<>(activity);
        dialog.show();
    }

    private static boolean isActivityUsable(Activity activity) {
        return activity != null
                && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }

    private static void dismissDialogOwnedBy(Activity activity) {
        if (activity == null || dialogActivityRef.get() != activity) return;
        AlertDialog dialog = settingsDialog;
        settingsDialog = null;
        dialogActivityRef = new WeakReference<>(null);
        if (dialog == null) return;
        Runnable dismiss = () -> {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
                // The Activity window may already have been destroyed.
            }
        };
        if (activity.getMainLooper().isCurrentThread()) dismiss.run();
        else activity.runOnUiThread(dismiss);
    }

    private static Switch addSwitch(LinearLayout parent, Activity activity, String title,
            String summary, String key, boolean checked) {
        boolean available = ModuleSettings.isSettingAvailable(key);
        Switch item = new Switch(activity);
        item.setText(title + "\n" + summary
                + (available ? "" : "\n已禁用：" + ModuleSettings.disabledReason(key)));
        item.setTextSize(15);
        item.setChecked(available && checked);
        item.setEnabled(available);
        item.setAlpha(available ? 1f : 0.55f);
        item.setPadding(0, dp(activity, 7), 0, dp(activity, 7));
        item.setOnCheckedChangeListener((button, value) -> ModuleSettings.putBoolean(key, value));
        parent.addView(item, matchWrap());
        return item;
    }

    private interface StringSettingWriter {
        void set(String value);
    }

    private interface StringSettingReader {
        String get();
    }

    private static TextView addChoiceSetting(LinearLayout parent, Activity activity, String title,
            String key, String[] labels, String[] values, StringSettingReader reader,
            StringSettingWriter writer) {
        boolean available = ModuleSettings.isSettingAvailable(key);
        TextView item = new TextView(activity);
        item.setText(choiceSettingText(title, reader.get(), labels, values)
                + (available ? "" : "\n已禁用：" + ModuleSettings.disabledReason(key)));
        item.setTextSize(15);
        item.setEnabled(available);
        item.setAlpha(available ? 1f : 0.55f);
        item.setPadding(0, dp(activity, 9), 0, dp(activity, 9));
        item.setOnClickListener(view -> showChoiceSettingDialog(
                activity, item, title, labels, values, reader, writer));
        parent.addView(item, matchWrap());
        return item;
    }

    private static void showChoiceSettingDialog(Activity activity, TextView item, String title,
            String[] labels, String[] values, StringSettingReader reader,
            StringSettingWriter writer) {
        String current = reader.get();
        int checked = 0;
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(current)) checked = index;
        }
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    writer.set(values[which]);
                    item.setText(choiceSettingText(title, values[which], labels, values));
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String choiceSettingText(
            String title, String value, String[] labels, String[] values) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(value)) return title + "\n当前：" + labels[index];
        }
        return title + "\n当前：" + value;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
