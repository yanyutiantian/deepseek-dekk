package com.dekk.yanyu;

import android.content.Context;

/** Two AI permissions, both off by default, plus the pending result for the next request. */
final class AdbControlStore {
    private static final String PREF = "dekk_prefs";
    private static final String KEY_FREEZE = "adb_allow_freeze";
    private static final String KEY_USAGE = "adb_allow_usage";
    private static final String KEY_RESULT = "adb_pending_result";
    private static final String KEY_USAGE_TEXT = "adb_usage_text";
    private static final String KEY_USAGE_AT = "adb_usage_at";
    private static final String KEY_STATUS_TEXT = "adb_status_text";
    private static final String KEY_STATUS_AT = "adb_status_at";
    /** Snapshots older than this are refreshed in the background. */
    static final long USAGE_TTL_MS = 60000L;

    private AdbControlStore() { }

    static boolean allowFreeze(Context context) {
        return context != null && context.getSharedPreferences(PREF, 0).getBoolean(KEY_FREEZE, false);
    }

    static void setAllowFreeze(Context context, boolean value) {
        context.getSharedPreferences(PREF, 0).edit().putBoolean(KEY_FREEZE, value).apply();
        if (!value) clearResult(context);
    }

    static boolean allowUsage(Context context) {
        return context != null && context.getSharedPreferences(PREF, 0).getBoolean(KEY_USAGE, false);
    }

    static void setAllowUsage(Context context, boolean value) {
        context.getSharedPreferences(PREF, 0).edit().putBoolean(KEY_USAGE, value).apply();
        if (value) AdbControl.refreshUsageAsync(context);
        else {
            clearResult(context);
            clearUsage(context);
        }
    }

    static boolean enabled(Context context) {
        return allowFreeze(context) || allowUsage(context);
    }

    /** Queued once, carried by the next request only. */
    static void queueResult(Context context, String block) {
        if (context == null) return;
        context.getSharedPreferences(PREF, 0).edit()
                .putString(KEY_RESULT, block == null ? "" : block).apply();
    }

    static String takeResultBlock(Context context) {
        if (context == null) return "";
        String block = context.getSharedPreferences(PREF, 0).getString(KEY_RESULT, "");
        if (block.length() == 0) return "";
        clearResult(context);
        return block;
    }

    static String peekResult(Context context) {
        return context == null ? "" : context.getSharedPreferences(PREF, 0).getString(KEY_RESULT, "");
    }

    static void clearResult(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREF, 0).edit().remove(KEY_RESULT).apply();
    }

    /** Last usage snapshot, refreshed in the background so sending never blocks on it. */
    static void saveUsage(Context context, String text) {
        if (context == null) return;
        context.getSharedPreferences(PREF, 0).edit()
                .putString(KEY_USAGE_TEXT, text == null ? "" : text)
                .putLong(KEY_USAGE_AT, System.currentTimeMillis()).apply();
    }

    static String usageText(Context context) {
        return context == null ? "" : context.getSharedPreferences(PREF, 0).getString(KEY_USAGE_TEXT, "");
    }

    static long usageAt(Context context) {
        return context == null ? 0L : context.getSharedPreferences(PREF, 0).getLong(KEY_USAGE_AT, 0L);
    }

    static boolean usageStale(Context context) {
        return System.currentTimeMillis() - usageAt(context) > USAGE_TTL_MS;
    }

    static void clearUsage(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREF, 0).edit().remove(KEY_USAGE_TEXT).remove(KEY_USAGE_AT).apply();
    }

    /** Last connection check, shown instantly when the ADB page opens. */
    static void saveStatus(Context context, String text) {
        if (context == null) return;
        context.getSharedPreferences(PREF, 0).edit()
                .putString(KEY_STATUS_TEXT, text == null ? "" : text)
                .putLong(KEY_STATUS_AT, System.currentTimeMillis()).apply();
    }

    static String statusText(Context context) {
        return context == null ? "" : context.getSharedPreferences(PREF, 0).getString(KEY_STATUS_TEXT, "");
    }

    static long statusAt(Context context) {
        return context == null ? 0L : context.getSharedPreferences(PREF, 0).getLong(KEY_STATUS_AT, 0L);
    }
}