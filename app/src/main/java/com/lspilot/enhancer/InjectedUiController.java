package com.lspilot.enhancer;

import android.app.Activity;
import android.widget.Toast;

/** Initializes module settings from the host activity without adding an overlay UI. */
final class InjectedUiController {
    private static volatile boolean requestHookInstalled;

    private InjectedUiController() {}

    static void prepare(Activity activity) {
        if (activity != null) ModuleSettings.initialize(activity);
    }

    static void attach(Activity activity) {
        prepare(activity);
        if (activity != null
                && requestHookInstalled
                && ModuleSettings.shouldShowSuccessNotice()) {
            ModuleSettings.markSuccessNoticeShown();
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "模型请求增强已加载，可在 LSPilot 设置中配置",
                    Toast.LENGTH_LONG).show());
        }
    }

    static void setRequestHookInstalled(boolean installed) {
        requestHookInstalled = installed;
    }
}
