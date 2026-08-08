package dev.operit.lspilot.enhancer;

import android.content.Context;
import android.content.SharedPreferences;

final class ModuleSettings {
    // Legacy preference name retained so existing settings survive the project rename.
    static final String PREFS_NAME = "lspilot_cache_enhancer";

    static final String KEY_ENABLED = "enabled";
    static final String KEY_CACHE_KEY = "cache_key_enabled";
    static final String KEY_RETENTION = "retention_enabled";
    static final String KEY_INCLUDE_USAGE = "include_usage_enabled";
    static final String KEY_CONTEXT_COMPRESSION = "context_compression_enabled";
    static final String KEY_DEBUG_LOG = "debug_log_enabled";
    static final String KEY_VERBOSE_DEBUG_LOG = "verbose_debug_log_enabled";
    static final String KEY_SUCCESS_NOTICE = "hook_success_notice_v2";
    static final String KEY_MANUAL_KEEP_RECENT = "manual_keep_recent";

    private static volatile Context applicationContext;

    private ModuleSettings() {
    }

    static void initialize(Context context) {
        if (context != null) {
            applicationContext = context.getApplicationContext();
        }
    }

    static SharedPreferences preferences() {
        Context context = applicationContext;
        return context == null
                ? null
                : context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean isEnabled() {
        return getBoolean(KEY_ENABLED, true);
    }

    static boolean isCacheKeyEnabled() {
        return getBoolean(KEY_CACHE_KEY, true);
    }

    static boolean isRetentionEnabled() {
        return getBoolean(KEY_RETENTION, true);
    }

    static boolean isIncludeUsageEnabled() {
        return getBoolean(KEY_INCLUDE_USAGE, true);
    }

    static boolean isContextCompressionEnabled() {
        return getBoolean(KEY_CONTEXT_COMPRESSION, false);
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
        return !getBoolean(KEY_SUCCESS_NOTICE, false);
    }

    static void markSuccessNoticeShown() {
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            preferences.edit().putBoolean(KEY_SUCCESS_NOTICE, true).apply();
        }
    }

    static int getManualKeepRecent() {
        SharedPreferences preferences = preferences();
        int value = preferences == null ? ContextCompression.KEEP_RECENT_MESSAGES
                : preferences.getInt(KEY_MANUAL_KEEP_RECENT,
                ContextCompression.KEEP_RECENT_MESSAGES);
        return value == 16 || value == 64 ? value : ContextCompression.KEEP_RECENT_MESSAGES;
    }

    static void setManualKeepRecent(int value) {
        SharedPreferences preferences = preferences();
        if (preferences != null && (value == 16 || value == 32 || value == 64)) {
            preferences.edit().putInt(KEY_MANUAL_KEEP_RECENT, value).apply();
        }
    }

    static void putBoolean(String key, boolean value) {
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            preferences.edit().putBoolean(key, value).apply();
        }
    }

    static void resetPolicy() {
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            boolean noticeShown = preferences.getBoolean(KEY_SUCCESS_NOTICE, false);
            preferences.edit().clear().putBoolean(KEY_SUCCESS_NOTICE, noticeShown).apply();
        }
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        SharedPreferences preferences = preferences();
        return preferences == null ? defaultValue : preferences.getBoolean(key, defaultValue);
    }
}