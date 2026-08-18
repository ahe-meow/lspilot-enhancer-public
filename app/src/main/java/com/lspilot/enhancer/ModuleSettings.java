package com.lspilot.enhancer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ModuleSettings {
    private static final String TAG = "LSPilotEnhancer";
    private static final String HOST_PACKAGE = "me.yun.lspilot";

    // Retain the legacy name so existing host-local values can be reused.
    static final String PREFS_NAME = "lspilot_cache_enhancer";

    static final String KEY_ENABLED = "enabled";
    static final String KEY_CACHE_KEY = "cache_key_enabled";
    static final String KEY_RETENTION = "retention_enabled";
    static final String KEY_INCLUDE_USAGE = "include_usage_enabled";
    static final String KEY_HISTORY_RETENTION = "history_retention_enabled";
    static final String KEY_REASONING_EFFORT = "reasoning_effort";
    static final String KEY_DEBUG_LOG = "debug_log_enabled";
    static final String KEY_VERBOSE_DEBUG_LOG = "verbose_debug_log_enabled";
    static final String KEY_SUCCESS_NOTICE = "hook_success_notice_v2";

    private static final String KEY_HOST_MIGRATED = "settings_host_migrated_v1";

    private static volatile Context applicationContext;
    private static volatile SharedPreferences legacyRemotePreferences;
    private static final Map<String, String> UNAVAILABLE_SETTINGS = new ConcurrentHashMap<>();

    private ModuleSettings() {
    }

    static synchronized void initialize(Context context) {
        if (context != null) {
            Context candidate = context.getApplicationContext();
            if (candidate != null && HOST_PACKAGE.equals(candidate.getPackageName())) {
                applicationContext = candidate;
            }
        }
        migrateLegacyRemoteSettings();
        purgeRemovedCompressionSettings();
        persistUnavailableSettings();
    }

    static synchronized void useRemotePreferences(SharedPreferences preferences) {
        legacyRemotePreferences = preferences;
        migrateLegacyRemoteSettings();
        persistUnavailableSettings();
    }

    static SharedPreferences preferences() {
        Context context = applicationContext;
        return context == null
                ? null
                : context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean isEnabled() {
        return isSettingAvailable(KEY_ENABLED) && getBoolean(KEY_ENABLED, true);
    }

    static boolean isCacheKeyEnabled() {
        return isSettingAvailable(KEY_CACHE_KEY) && getBoolean(KEY_CACHE_KEY, true);
    }

    static boolean isRetentionEnabled() {
        return isSettingAvailable(KEY_RETENTION) && getBoolean(KEY_RETENTION, true);
    }

    static boolean isIncludeUsageEnabled() {
        return isSettingAvailable(KEY_INCLUDE_USAGE) && getBoolean(KEY_INCLUDE_USAGE, true);
    }

    static boolean isHistoryRetentionEnabled() {
        return isSettingAvailable(KEY_HISTORY_RETENTION)
                && getBoolean(KEY_HISTORY_RETENTION, true);
    }

    static String getReasoningEffort() {
        SharedPreferences preferences = preferences();
        Object stored = preferences == null ? null : preferences.getAll().get(KEY_REASONING_EFFORT);
        String value = stored instanceof String ? (String) stored : ReasoningPolicy.DEFAULT_EFFORT;
        return ReasoningPolicy.isSupportedEffort(value) ? value : ReasoningPolicy.DEFAULT_EFFORT;
    }

    static void setReasoningEffort(String value) {
        if (ReasoningPolicy.isSupportedEffort(value)) putString(KEY_REASONING_EFFORT, value);
    }

    static boolean isDebugLogEnabled() {
        return getBoolean(KEY_DEBUG_LOG, false);
    }

    static boolean isVerboseDebugLogEnabled() {
        return isDebugLogEnabled() && getBoolean(KEY_VERBOSE_DEBUG_LOG, false);
    }

    static Context applicationContextForLogging() {
        return applicationContext;
    }

    static boolean shouldShowSuccessNotice() {
        SharedPreferences preferences = preferences();
        return preferences == null || !preferences.getBoolean(KEY_SUCCESS_NOTICE, false);
    }

    static void markSuccessNoticeShown() {
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            preferences.edit().putBoolean(KEY_SUCCESS_NOTICE, true).apply();
        }
    }

    static synchronized void disableSettings(String reason, String... keys) {
        if (keys == null || keys.length == 0) return;
        String safeReason = reason == null || reason.trim().isEmpty()
                ? "启动探测发现宿主接口不可用" : reason.trim();
        for (String key : keys) {
            markUnavailable(key, safeReason);
        }
        persistUnavailableSettings();
    }

    static boolean isSettingAvailable(String key) {
        return !UNAVAILABLE_SETTINGS.containsKey(key);
    }

    static String disabledReason(String key) {
        return fallbackReason(UNAVAILABLE_SETTINGS.get(key));
    }

    static String unavailableSummary() {
        StringBuilder result = new StringBuilder();
        appendUnavailable(result, "总开关", KEY_ENABLED);
        appendUnavailable(result, "缓存路由键", KEY_CACHE_KEY);
        appendUnavailable(result, "缓存保留", KEY_RETENTION);
        appendUnavailable(result, "用量统计", KEY_INCLUDE_USAGE);
        appendUnavailable(result, "历史消息保留", KEY_HISTORY_RETENTION);
        appendUnavailable(result, "推理强度", KEY_REASONING_EFFORT);
        return result.toString();
    }

    static synchronized void putBoolean(String key, boolean value) {
        boolean actual = isSettingAvailable(key) && value;
        if (value && !actual) {
            Log.w(TAG, "Host setting write forced off for unavailable key=" + key
                    + " reason=" + disabledReason(key));
        }
        SharedPreferences preferences = writablePreferences();
        if (preferences == null) {
            Log.e(TAG, "Host setting write skipped without host context key=" + key);
            return;
        }
        if (!preferences.edit().putBoolean(key, actual).commit()) {
            Log.e(TAG, "Host setting commit failed key=" + key);
        } else {
            Log.i(TAG, "Host setting committed key=" + key + " value=" + actual);
        }
    }

    static synchronized void resetPolicy() {
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            return;
        }
        boolean noticeShown = preferences.getBoolean(KEY_SUCCESS_NOTICE, false);
        SharedPreferences.Editor editor = preferences.edit().clear()
                .putBoolean(KEY_HOST_MIGRATED, true);
        if (noticeShown) {
            editor.putBoolean(KEY_SUCCESS_NOTICE, true);
        }
        if (!editor.commit()) {
            Log.e(TAG, "Host settings reset commit failed");
        }
        persistUnavailableSettings();
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        SharedPreferences preferences = preferences();
        return preferences == null ? defaultValue : preferences.getBoolean(key, defaultValue);
    }

    private static SharedPreferences writablePreferences() {
        SharedPreferences local = preferences();
        return local != null ? local : legacyRemotePreferences;
    }

    private static void markUnavailable(String key, String reason) {
        if (isManagedSetting(key)) {
            UNAVAILABLE_SETTINGS.put(key, reason);
        }
    }

    private static boolean isManagedSetting(String key) {
        return KEY_ENABLED.equals(key)
                || KEY_CACHE_KEY.equals(key)
                || KEY_RETENTION.equals(key)
                || KEY_INCLUDE_USAGE.equals(key)
                || KEY_HISTORY_RETENTION.equals(key)
                || KEY_REASONING_EFFORT.equals(key);
    }

    private static String fallbackReason(String reason) {
        return reason == null || reason.trim().isEmpty()
                ? "启动探测发现宿主接口不可用" : reason;
    }

    private static void appendUnavailable(StringBuilder result, String label, String key) {
        if (isSettingAvailable(key)) return;
        if (result.length() > 0) result.append('\n');
        result.append(label).append("：").append(disabledReason(key));
    }

    private static void purgeRemovedCompressionSettings() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        SharedPreferences.Editor editor = null;
        for (String key : preferences.getAll().keySet()) {
            if ("context_compression_enabled".equals(key)
                    || "manual_keep_recent".equals(key)
                    || "auto_context_tokens".equals(key)
                    || key.startsWith("lspilot.summary.record.v1.")
                    || key.startsWith("lspilot.summary.record.v2.")) {
                if (editor == null) editor = preferences.edit();
                editor.remove(key);
            }
        }
        if (editor != null && !editor.commit()) {
            Log.e(TAG, "Removed-feature preference cleanup failed");
        }
    }

    private static void persistUnavailableSettings() {
        if (UNAVAILABLE_SETTINGS.isEmpty()) return;
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        try {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : UNAVAILABLE_SETTINGS.keySet()) {
                if (!KEY_REASONING_EFFORT.equals(key)) editor.putBoolean(key, false);
            }
            if (!editor.commit()) {
                Log.e(TAG, "Unavailable setting commit failed");
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unavailable setting persistence failed", error);
        }
    }

    private static synchronized void putString(String key, String value) {
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            Log.e(TAG, "Host setting write skipped without host context key=" + key);
            return;
        }
        if (!preferences.edit().putString(key, value).commit()) {
            Log.e(TAG, "Host setting commit failed key=" + key);
        } else {
            Log.i(TAG, "Host setting committed key=" + key + " value=" + value);
        }
    }

    private static void migrateLegacyRemoteSettings() {
        SharedPreferences local = preferences();
        SharedPreferences remote = legacyRemotePreferences;
        if (local == null || remote == null || local.getBoolean(KEY_HOST_MIGRATED, false)) {
            return;
        }
        try {
            if (!hasAnyPolicyValue(remote)) {
                local.edit().putBoolean(KEY_HOST_MIGRATED, true).commit();
                return;
            }
            boolean noticeShown = local.getBoolean(KEY_SUCCESS_NOTICE, false);
            SharedPreferences.Editor editor = local.edit().clear();
            copyBoolean(remote, editor, KEY_ENABLED);
            copyBoolean(remote, editor, KEY_CACHE_KEY);
            copyBoolean(remote, editor, KEY_RETENTION);
            copyBoolean(remote, editor, KEY_INCLUDE_USAGE);
            copyBoolean(remote, editor, KEY_HISTORY_RETENTION);
            copyBoolean(remote, editor, KEY_DEBUG_LOG);
            copyBoolean(remote, editor, KEY_VERBOSE_DEBUG_LOG);
            copyString(remote, editor, KEY_REASONING_EFFORT);
            editor.putBoolean(KEY_HOST_MIGRATED, true);
            if (noticeShown) {
                editor.putBoolean(KEY_SUCCESS_NOTICE, true);
            }
            if (editor.commit()) {
                Log.i(TAG, "Legacy remote settings migrated to host preferences");
            } else {
                Log.e(TAG, "Legacy remote settings migration commit failed");
            }
        } catch (Throwable error) {
            Log.e(TAG, "Legacy remote settings migration failed", error);
        }
    }

    private static boolean hasAnyPolicyValue(SharedPreferences preferences) {
        return preferences.contains(KEY_ENABLED)
                || preferences.contains(KEY_CACHE_KEY)
                || preferences.contains(KEY_RETENTION)
                || preferences.contains(KEY_INCLUDE_USAGE)
                || preferences.contains(KEY_HISTORY_RETENTION)
                || preferences.contains(KEY_DEBUG_LOG)
                || preferences.contains(KEY_VERBOSE_DEBUG_LOG)
                || preferences.contains(KEY_REASONING_EFFORT);
    }

    private static void copyBoolean(
            SharedPreferences source, SharedPreferences.Editor target, String key) {
        if (source.contains(key)) {
            target.putBoolean(key, source.getBoolean(key, false));
        }
    }

    private static void copyString(
            SharedPreferences source, SharedPreferences.Editor target, String key) {
        if (source.contains(key)) {
            target.putString(key, source.getString(key, ReasoningPolicy.DEFAULT_EFFORT));
        }
    }

}