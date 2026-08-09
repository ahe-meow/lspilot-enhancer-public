package dev.operit.lspilot.enhancer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

final class ModuleSettings {
    private static final String TAG = "LSPilotEnhancer";
    private static final String HOST_PACKAGE = "me.yun.lspilot";

    // Retain the legacy name so existing host-local values can be reused.
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
    static final String KEY_AUTO_CONTEXT_TOKENS = "auto_context_tokens";

    private static final String KEY_HOST_MIGRATED = "settings_host_migrated_v1";

    static final int DEFAULT_AUTO_CONTEXT_TOKENS = 16_000;
    static final int MIN_AUTO_CONTEXT_TOKENS = 1_000;
    static final int MAX_AUTO_CONTEXT_TOKENS = 512_000;

    private static volatile Context applicationContext;
    private static volatile SharedPreferences legacyRemotePreferences;

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
    }

    static synchronized void useRemotePreferences(SharedPreferences preferences) {
        legacyRemotePreferences = preferences;
        migrateLegacyRemoteSettings();
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
        SharedPreferences preferences = preferences();
        return preferences == null || !preferences.getBoolean(KEY_SUCCESS_NOTICE, false);
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
        return value == 16 || value == 32 || value == 64
                ? value : ContextCompression.KEEP_RECENT_MESSAGES;
    }

    static void setManualKeepRecent(int value) {
        if (value == 16 || value == 32 || value == 64) {
            putInt(KEY_MANUAL_KEEP_RECENT, value);
        }
    }

    static int getAutoContextTokens() {
        SharedPreferences preferences = preferences();
        int value = preferences == null ? DEFAULT_AUTO_CONTEXT_TOKENS
                : preferences.getInt(KEY_AUTO_CONTEXT_TOKENS, DEFAULT_AUTO_CONTEXT_TOKENS);
        return clamp(value, MIN_AUTO_CONTEXT_TOKENS, MAX_AUTO_CONTEXT_TOKENS);
    }

    static void setAutoContextTokens(int value) {
        putInt(KEY_AUTO_CONTEXT_TOKENS,
                clamp(value, MIN_AUTO_CONTEXT_TOKENS, MAX_AUTO_CONTEXT_TOKENS));
    }

    static synchronized void putBoolean(String key, boolean value) {
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            Log.e(TAG, "Host setting write skipped without host context key=" + key);
            return;
        }
        if (!preferences.edit().putBoolean(key, value).commit()) {
            Log.e(TAG, "Host setting commit failed key=" + key);
        } else {
            Log.i(TAG, "Host setting committed key=" + key + " value=" + value);
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
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        SharedPreferences preferences = preferences();
        return preferences == null ? defaultValue : preferences.getBoolean(key, defaultValue);
    }

    private static synchronized void putInt(String key, int value) {
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            Log.e(TAG, "Host setting write skipped without host context key=" + key);
            return;
        }
        if (!preferences.edit().putInt(key, value).commit()) {
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
            copyBoolean(remote, editor, KEY_CONTEXT_COMPRESSION);
            copyBoolean(remote, editor, KEY_DEBUG_LOG);
            copyBoolean(remote, editor, KEY_VERBOSE_DEBUG_LOG);
            copyInt(remote, editor, KEY_MANUAL_KEEP_RECENT);
            copyInt(remote, editor, KEY_AUTO_CONTEXT_TOKENS);
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
                || preferences.contains(KEY_CONTEXT_COMPRESSION)
                || preferences.contains(KEY_DEBUG_LOG)
                || preferences.contains(KEY_VERBOSE_DEBUG_LOG)
                || preferences.contains(KEY_MANUAL_KEEP_RECENT)
                || preferences.contains(KEY_AUTO_CONTEXT_TOKENS);
    }

    private static void copyBoolean(
            SharedPreferences source, SharedPreferences.Editor target, String key) {
        if (source.contains(key)) {
            target.putBoolean(key, source.getBoolean(key, false));
        }
    }

    private static void copyInt(
            SharedPreferences source, SharedPreferences.Editor target, String key) {
        if (source.contains(key)) {
            target.putInt(key, source.getInt(key, 0));
        }
    }

    private static int clamp(int value, int minValue, int maxValue) {
        return Math.max(minValue, Math.min(value, maxValue));
    }
}