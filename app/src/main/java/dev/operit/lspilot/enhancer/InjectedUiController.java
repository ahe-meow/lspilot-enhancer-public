package dev.operit.lspilot.enhancer;
import android.content.res.Configuration;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** Safe integration: only appends a normal item to LSPilot's settings LazyColumn. */
final class InjectedUiController {
    private static final String TAG = "LSPilotEnhancer";
    private static volatile WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static volatile WeakReference<Activity> dialogActivityRef =
            new WeakReference<>(null);
    private static volatile boolean requestHookInstalled;
    private static volatile boolean chatRouteVisible;
    private static volatile AlertDialog settingsDialog;

    private InjectedUiController() {}

    static void prepare(Activity activity) {
        if (activity == null) {
            return;
        }
        Activity previous = activityRef.get();
        if (previous != null && previous != activity) {
            dismissDialogOwnedBy(previous);
        }
        ModuleSettings.initialize(activity);
        activityRef = new WeakReference<>(activity);
    }

    static void setActiveActivity(Activity activity) {
        if (activity != null && isActivityUsable(activity)) {
            activityRef = new WeakReference<>(activity);
            ModuleSettings.initialize(activity);
            DebugLogger.d("active activity=" + activity.getClass().getName());
        }
    }

    static void setChatRouteVisible(Activity activity, boolean visible) {
        if (!isActivityUsable(activity)) return;
        activityRef = new WeakReference<>(activity);
        chatRouteVisible = visible;
        activity.runOnUiThread(() -> {
            if (!isActivityUsable(activity)) return;
            if (visible) {
                scheduleFloatingChatButton(activity);
            } else {
                removeChatOverlay(activity);
            }
        });
        Log.i(TAG, "chat route visible=" + visible);
    }

    static void attach(Activity activity) {
        prepare(activity);
        if (requestHookInstalled && ModuleSettings.shouldShowSuccessNotice()) {
            ModuleSettings.markSuccessNoticeShown();
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "模型缓存增强已加载，可在 LSPilot 设置中配置",
                    Toast.LENGTH_LONG).show());
        }
    }

    static void setRequestHookInstalled(boolean installed) {
        requestHookInstalled = installed;
    }

    static void showChatCompressionButton() {
        Activity activity = currentChatActivity();
        if (!chatRouteVisible || !isActivityUsable(activity)) return;
        scheduleFloatingChatButton(activity);
    }

    static Activity currentChatActivity() {
        Activity activity = findResumedChatActivity();
        if (isActivityUsable(activity)) return activity;
        Activity fallback = activityRef.get();
        if (fallback != null && "me.yun.lspilot.ui.SubScreenActivity".equals(fallback.getClass().getName())
                && isActivityUsable(fallback)) {
            return fallback;
        }
        return null;
    }

    static void showChatCompressionDialog() {
        Activity activity = findResumedChatActivity();
        if (!isActivityUsable(activity)) {
            Activity fallback = activityRef.get();
            if (fallback != null && "me.yun.lspilot.ui.SubScreenActivity".equals(fallback.getClass().getName())) {
                activity = fallback;
            }
        }
        if (!isActivityUsable(activity)) {
            DebugLogger.w("compression dialog rejected: no usable chat Activity");
            return;
        }
        Log.i(TAG, "compression panel requested on " + activity.getClass().getName());
        activityRef = new WeakReference<>(activity);
        Activity owner = activity;
        owner.runOnUiThread(() -> {
            if (isActivityUsable(owner)) showInlineCompressionPanel(owner);
        });
    }

    static void startNativeCompression() {
        ManualCompressionManager.prepareCurrent(ModuleSettings.getManualKeepRecent(), result -> {
            NativeChatTopBarAction.showResult();
            DebugLogger.i("native compose compression result=" + DebugLogger.redact(result.message));
        });
    }

    static void onNativeChatCompressionAction() {
        DebugLogger.i("compression action clicked in chat Composer");
        NativeChatTopBarAction.showDialog();
    }


    private static void scheduleFloatingChatButton(Activity activity) {
        if (!chatRouteVisible || !isActivityUsable(activity)) return;
        activity.runOnUiThread(() -> {
            if (!chatRouteVisible || !isActivityUsable(activity)) return;
            attachFloatingChatButton(activity);
        });
    }

    private static void attachFloatingChatButton(Activity activity) {
        if (!chatRouteVisible || !isActivityUsable(activity)
                || !"me.yun.lspilot.ui.SubScreenActivity".equals(activity.getClass().getName())) {
            Log.i(TAG, "chat icon attach skipped: route hidden or Activity unusable");
            return;
        }
        FrameLayout overlay = ensureOverlay(activity);
        if (overlay == null) {
            Log.i(TAG, "chat icon attach skipped: no decor overlay");
            return;
        }
        View existing = overlay.findViewWithTag("lspilot-enhancer-chat-compress-fab");
        if (existing instanceof ImageButton) {
            applyCompressionIconTheme(activity, (ImageButton) existing);
            existing.bringToFront();
            return;
        }
        if (existing != null) overlay.removeView(existing);

        ImageButton button = new ImageButton(activity);
        button.setTag("lspilot-enhancer-chat-compress-fab");
        button.setContentDescription("压缩上下文");
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                Log.i(TAG, "chat compression icon touch down");
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                Log.i(TAG, "chat compression icon touch up");
            }
            return false;
        });
        button.setOnClickListener(view -> {
            Log.i(TAG, "chat compression icon clicked");
            if (chatRouteVisible && isActivityUsable(activity)) {
                showInlineCompressionPanel(activity);
            }
        });
        applyCompressionIconTheme(activity, button);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(activity, 48), dp(activity, 48), Gravity.TOP | Gravity.RIGHT);
        params.topMargin = statusBarHeight(activity) + dp(activity, 60);
        params.rightMargin = dp(activity, 12);
        overlay.addView(button, params);
        button.bringToFront();
        if (Build.VERSION.SDK_INT >= 21) button.setElevation(dp(activity, 40));
        Log.i(TAG, "chat compression icon attached overlayChildren=" + overlay.getChildCount());
    }

    private static FrameLayout ensureOverlay(Activity activity) {
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return null;
        ViewGroup decorGroup = (ViewGroup) decor;
        View existing = decorGroup.findViewWithTag("lspilot-enhancer-overlay-root");
        if (existing instanceof FrameLayout) {
            existing.bringToFront();
            return (FrameLayout) existing;
        }
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag("lspilot-enhancer-overlay-root");
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setClickable(false);
        overlay.setFocusable(false);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP | Gravity.LEFT);
        decorGroup.addView(overlay, params);
        overlay.bringToFront();
        if (Build.VERSION.SDK_INT >= 21) overlay.setElevation(dp(activity, 36));
        Log.i(TAG, "overlay root attached decor=" + decorGroup.getClass().getName()
                + " children=" + decorGroup.getChildCount());
        return overlay;
    }

    private static void removeChatOverlay(Activity activity) {
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        View overlay = ((ViewGroup) decor).findViewWithTag("lspilot-enhancer-overlay-root");
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
            Log.i(TAG, "chat overlay removed");
        }
    }

    private static void applyCompressionIconTheme(Activity activity, ImageButton button) {
        boolean dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int foreground = dark ? Color.WHITE : Color.rgb(35, 35, 35);
        int surface = dark ? Color.rgb(58, 58, 58) : Color.rgb(245, 245, 245);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(surface);
        if (Build.VERSION.SDK_INT >= 21) {
            button.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000), circle, null));
        } else {
            button.setBackground(circle);
        }
        button.setImageDrawable(new CompressionIconDrawable(foreground, dp(activity, 24)));
        button.setEnabled(true);
        button.setClickable(true);
        button.setAlpha(0.98f);
    }

    private static final class CompressionIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int intrinsicSize;

        CompressionIconDrawable(int color, int intrinsicSize) {
            this.intrinsicSize = intrinsicSize;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            paint.setStrokeJoin(Paint.Join.MITER);
        }

        @Override
        public void draw(Canvas canvas) {
            float density = getBounds().width() / 24f;
            paint.setStrokeWidth(2.2f * density);
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.drawLine(4f * density, 9f * density, 9f * density, 9f * density, paint);
            canvas.drawLine(9f * density, 9f * density, 9f * density, 4f * density, paint);
            canvas.drawLine(4f * density, 4f * density, 9f * density, 9f * density, paint);
            canvas.drawLine(20f * density, 9f * density, 15f * density, 9f * density, paint);
            canvas.drawLine(15f * density, 9f * density, 15f * density, 4f * density, paint);
            canvas.drawLine(20f * density, 4f * density, 15f * density, 9f * density, paint);
            canvas.drawLine(4f * density, 15f * density, 9f * density, 15f * density, paint);
            canvas.drawLine(9f * density, 15f * density, 9f * density, 20f * density, paint);
            canvas.drawLine(4f * density, 20f * density, 9f * density, 15f * density, paint);
            canvas.drawLine(20f * density, 15f * density, 15f * density, 15f * density, paint);
            canvas.drawLine(15f * density, 15f * density, 15f * density, 20f * density, paint);
            canvas.drawLine(20f * density, 20f * density, 15f * density, 15f * density, paint);
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return intrinsicSize; }
        @Override public int getIntrinsicHeight() { return intrinsicSize; }
    }

    private static int statusBarHeight(Activity activity) {
        int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? 0 : activity.getResources().getDimensionPixelSize(id);
    }

    private static void showInlineCompressionPanel(Activity activity) {
        if (!isActivityUsable(activity)) return;
        FrameLayout frame = ensureOverlay(activity);
        if (frame == null) {
            Log.i(TAG, "inline compression panel skipped: no overlay");
            return;
        }
        View old = frame.findViewWithTag("lspilot-enhancer-chat-compress-panel");
        if (old != null) frame.removeView(old);
        boolean dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ManualCompressionManager.ScreenState state = ManualCompressionManager.getCurrentScreen();
        boolean prepared = ManualCompressionManager.hasPreparedForCurrentChat();
        int keepRecent = ModuleSettings.getManualKeepRecent();

        LinearLayout panel = new LinearLayout(activity);
        panel.setTag("lspilot-enhancer-chat-compress-panel");
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 12));
        panel.setBackgroundColor(dark ? Color.rgb(42, 42, 42) : Color.WHITE);
        if (Build.VERSION.SDK_INT >= 21) panel.setElevation(dp(activity, 32));

        TextView title = new TextView(activity);
        title.setText(prepared ? "上下文摘要已就绪" : "手动压缩上下文");
        title.setTextColor(dark ? Color.WHITE : Color.rgb(32, 33, 36));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(16);
        panel.addView(title, matchWrap());

        TextView body = new TextView(activity);
        body.setText(state == null
                ? "正在读取当前对话。保留最近 " + keepRecent + " 条消息。"
                : (prepared
                ? "摘要已准备完成，将在下一次发送时使用。原始聊天记录不会被修改。"
                : "当前共 " + state.messageCount + " 条消息。将使用当前模型压缩较早消息，保留最近 "
                + keepRecent + " 条。此操作会产生一次额外模型请求，但不会修改原始聊天记录。"));
        body.setTextColor(dark ? Color.rgb(210, 210, 210) : Color.rgb(80, 80, 80));
        body.setTextSize(14);
        body.setPadding(0, dp(activity, 8), 0, dp(activity, 12));
        panel.addView(body, matchWrap());

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.RIGHT);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button close = new Button(activity);
        close.setText("关闭");
        close.setAllCaps(false);
        close.setOnClickListener(v -> frame.removeView(panel));
        row.addView(close, new LinearLayout.LayoutParams(dp(activity, 76), dp(activity, 40)));
        Button start = new Button(activity);
        start.setText(prepared ? "重新生成" : "开始压缩");
        start.setAllCaps(false);
        start.setOnClickListener(v -> {
            Log.i(TAG, "inline compression start clicked");
            frame.removeView(panel);
            startManualCompression(activity, null);
        });
        row.addView(start, new LinearLayout.LayoutParams(dp(activity, 96), dp(activity, 40)));
        panel.addView(row, matchWrap());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        params.leftMargin = dp(activity, 14);
        params.rightMargin = dp(activity, 14);
        params.topMargin = statusBarHeight(activity) + dp(activity, 108);
        frame.addView(panel, params);
        panel.bringToFront();
        Log.i(TAG, "inline compression panel shown");
    }

    private static void showCompressionDialog(Activity activity, Button button) {
        if (ManualCompressionManager.isPreparing()) {
            Toast.makeText(activity, "压缩任务正在运行", Toast.LENGTH_SHORT).show();
            return;
        }
        ManualCompressionManager.ScreenState state =
                ManualCompressionManager.getCurrentScreen();
        boolean prepared = ManualCompressionManager.hasPreparedForCurrentChat();
        int keepRecent = ModuleSettings.getManualKeepRecent();
        String message;
        if (state == null) {
            message = "正在读取当前对话。保留最近 " + keepRecent + " 条消息。";
        } else {
            message = prepared
                    ? "摘要已准备完成，将在下一次发送时使用。原始聊天记录不会被修改。"
                    : "当前共 " + state.messageCount + " 条消息。将使用当前模型压缩较早消息，"
                    + "保留最近 " + keepRecent
                    + " 条。此操作会产生一次额外模型请求，但不会修改原始聊天记录。";
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(prepared ? "上下文摘要已就绪" : "手动压缩上下文")
                .setMessage(message)
                .setNegativeButton("取消", null);
        if (prepared) {
            builder.setNeutralButton("丢弃摘要", (dialog, which) -> {
                ManualCompressionManager.clearPrepared();
                if (button != null) {
                    updateChatButtonState(button);
                }
                Toast.makeText(activity, "已丢弃准备好的摘要", Toast.LENGTH_SHORT).show();
            });
        } else {
            builder.setNeutralButton("保留 " + keepRecent + " 条", (dialog, which) ->
                    chooseKeepRecent(activity, button));
        }
        builder.setPositiveButton(prepared ? "重新生成" : "开始压缩", (dialog, which) ->
                startManualCompression(activity, button));
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> applyChatDialogTheme(activity, dialog));
        dialog.show();
    }

    private static void applyChatDialogTheme(Activity activity, AlertDialog dialog) {
        boolean dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int surface = Color.parseColor(dark ? "#242424" : "#FFFFFF");
        int primary = Color.parseColor(dark ? "#F5F5F5" : "#202124");
        int secondary = Color.parseColor(dark ? "#C8C8C8" : "#555555");
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(surface));
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextColor(secondary);
        int titleId = activity.getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title != null) title.setTextColor(primary);
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (positive != null) positive.setTextColor(dark ? Color.rgb(140, 190, 255) : Color.rgb(30, 90, 180));
        if (negative != null) negative.setTextColor(dark ? Color.rgb(190, 190, 190) : Color.rgb(80, 80, 80));
        if (neutral != null) neutral.setTextColor(dark ? Color.rgb(140, 190, 255) : Color.rgb(30, 90, 180));
    }

    private static void chooseKeepRecent(Activity activity, Button button) {
        int current = ModuleSettings.getManualKeepRecent();
        String[] labels = {"16 条（更强压缩）", "32 条（平衡）", "64 条（更多上下文）"};
        int checked = current == 16 ? 0 : current == 64 ? 2 : 1;
        new AlertDialog.Builder(activity)
                .setTitle("保留最近消息")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    int value = which == 0 ? 16 : which == 2 ? 64 : 32;
                    ModuleSettings.setManualKeepRecent(value);
                    dialog.dismiss();
                    showCompressionDialog(activity, button);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void startManualCompression(Activity activity, Button button) {
        ManualCompressionManager.clearPrepared();
        if (button != null) {
            button.setEnabled(false);
            button.setText("压缩中");
        } else {
            Toast.makeText(activity, "压缩任务已开始", Toast.LENGTH_SHORT).show();
        }
        ManualCompressionManager.prepareCurrent(
                ModuleSettings.getManualKeepRecent(),
                result -> activity.runOnUiThread(() -> {
                    if (!isActivityUsable(activity)) {
                        return;
                    }
                    if (button != null) {
                        if (button.getParent() == null) {
                            return;
                        }
                        updateChatButtonState(button);
                    }
                    String detail = result.success
                            ? result.message + "（" + result.originalCount + " → "
                            + result.compactedCount + " 条）"
                            : "压缩失败：" + result.message;
                    Toast.makeText(activity, detail, Toast.LENGTH_LONG).show();
                }));
    }

    private static void updateChatButtonState(Button button) {
        ManualCompressionManager.ScreenState state =
                ManualCompressionManager.getCurrentScreen();
        if (ManualCompressionManager.isPreparing()) {
            button.setText("压缩中");
            button.setEnabled(false);
        } else if (ManualCompressionManager.hasPreparedForCurrentChat()) {
            button.setText("已就绪");
            button.setEnabled(true);
        } else {
            int count = state == null ? 0 : state.messageCount;
            button.setText(count > 0 ? "压缩 " + count : "压缩");
            button.setEnabled(state != null && !state.loading);
        }
    }

    // Settings entry injection is implemented by replaying LSPilot's native
    // ArrowPreference call in LSPilotEnhancerModule.

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
        if ("toString".equals(method.getName())) return "LSPilotEnhancerComposable";
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
        return null;
    }

    private static void openSettings() {
        Activity activity = activityRef.get();
        if (!isActivityUsable(activity)) {
            android.util.Log.w(TAG, "Settings entry clicked without a usable MainActivity");
            return;
        }
        activity.runOnUiThread(() -> {
            if (!isActivityUsable(activity)) {
                return;
            }
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
            if (owner == activity) {
                return;
            }
            try {
                old.dismiss();
            } catch (Throwable ignored) {
                // The old Activity window may already be gone.
            }
        }

        int padding = dp(activity, 20);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, dp(activity, 8), padding, dp(activity, 8));

        TextView status = new TextView(activity);
        status.setText(requestHookInstalled ? "状态：请求 Hook 已加载" : "状态：请求 Hook 未加载");
        status.setTextColor(requestHookInstalled ? Color.rgb(32, 140, 74) : Color.rgb(190, 55, 55));
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextSize(14);
        status.setPadding(0, 0, 0, dp(activity, 8));
        content.addView(status, matchWrap());

        Switch master = addSwitch(content, activity, "启用模型缓存增强",
                "关闭后保留 Hook，但不修改请求", ModuleSettings.KEY_ENABLED, ModuleSettings.isEnabled());
        Switch cacheKey = addSwitch(content, activity, "稳定缓存路由键",
                "按模型与系统提示词生成无明文 prompt_cache_key", ModuleSettings.KEY_CACHE_KEY, ModuleSettings.isCacheKeyEnabled());
        Switch retention = addSwitch(content, activity, "24 小时缓存保留",
                "仅对白名单中的 GPT-4.1、GPT-5、o1/o3/o4 系列启用", ModuleSettings.KEY_RETENTION, ModuleSettings.isRetentionEnabled());
        Switch usage = addSwitch(content, activity, "请求缓存用量统计",
                "让流式响应返回 cached_tokens 等 usage 信息", ModuleSettings.KEY_INCLUDE_USAGE, ModuleSettings.isIncludeUsageEnabled());
        Switch compression = addSwitch(content, activity, "上下文压缩",
                "超过 40 条消息时保留最近 32 条，并在请求中加入旧消息摘录",
                ModuleSettings.KEY_CONTEXT_COMPRESSION, ModuleSettings.isContextCompressionEnabled());
        Switch debug = addSwitch(content, activity, "诊断日志",
                "记录压缩与请求 Hook 的阶段信息，不记录消息正文或密钥",
                ModuleSettings.KEY_DEBUG_LOG, ModuleSettings.isDebugLogEnabled());
        Switch verboseDebug = addSwitch(content, activity, "详细诊断日志",
                "记录反射调用、线程和状态变化，仅在诊断日志开启时生效",
                ModuleSettings.KEY_VERBOSE_DEBUG_LOG, ModuleSettings.isVerboseDebugLogEnabled());

        Button reset = new Button(activity);
        reset.setText("恢复默认策略");
        reset.setAllCaps(false);
        reset.setOnClickListener(view -> {
            ModuleSettings.resetPolicy();
            master.setChecked(true); cacheKey.setChecked(true); retention.setChecked(true);
            usage.setChecked(true); debug.setChecked(false); verboseDebug.setChecked(false);
            Toast.makeText(activity, "已恢复默认策略", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams resetParams = matchWrap();
        resetParams.topMargin = dp(activity, 8);
        content.addView(reset, resetParams);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("模型缓存增强").setView(scrollView)
                .setPositiveButton("完成", null).create();
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

    private static Activity findResumedChatActivity() {
        Activity activity = findResumedActivity();
        if (activity != null && "me.yun.lspilot.ui.SubScreenActivity".equals(activity.getClass().getName())) {
            return activity;
        }
        return null;
    }

    private static Activity findResumedActivity() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Method current = threadClass.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            Field activitiesField = threadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object records = activitiesField.get(thread);
            if (!(records instanceof Map)) return null;
            for (Object record : ((Map<?, ?>) records).values()) {
                Field paused = record.getClass().getDeclaredField("paused");
                paused.setAccessible(true);
                Field destroyed = record.getClass().getDeclaredField("destroyed");
                destroyed.setAccessible(true);
                if (paused.getBoolean(record) || destroyed.getBoolean(record)) continue;
                Field activity = record.getClass().getDeclaredField("activity");
                activity.setAccessible(true);
                Object value = activity.get(record);
                if (value instanceof Activity && isActivityUsable((Activity) value)) {
                    DebugLogger.d("resumed activity probe=" + value.getClass().getName());
                    return (Activity) value;
                }
            }
        } catch (Throwable error) {
            DebugLogger.d("resumed activity probe unavailable: " + error.getClass().getSimpleName());
        }
        return null;
    }

    private static boolean isActivityUsable(Activity activity) {
        return activity != null
                && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }

    private static void dismissDialogOwnedBy(Activity activity) {
        if (activity == null || dialogActivityRef.get() != activity) {
            return;
        }
        AlertDialog dialog = settingsDialog;
        settingsDialog = null;
        dialogActivityRef = new WeakReference<>(null);
        if (dialog == null) {
            return;
        }
        Runnable dismiss = () -> {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
                // The Activity window may already have been destroyed.
            }
        };
        if (activity.getMainLooper().isCurrentThread()) {
            dismiss.run();
        } else {
            activity.runOnUiThread(dismiss);
        }
    }

    private static Switch addSwitch(LinearLayout parent, Activity activity, String title,
                                    String summary, String key, boolean checked) {
        Switch item = new Switch(activity);
        item.setText(title + "\n" + summary);
        item.setTextSize(15); item.setChecked(checked);
        item.setPadding(0, dp(activity, 7), 0, dp(activity, 7));
        item.setOnCheckedChangeListener((button, value) -> ModuleSettings.putBoolean(key, value));
        parent.addView(item, matchWrap());
        return item;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}