package com.dekk.yanyu;

import android.content.Context;
import android.content.SharedPreferences;

/** Switch and user-authored notes. Replaces the old per-session draft dialog. */
public final class ContextSummaryStore {
    public static final int LIMIT = 6000;
    private static final String PREF = "dekk_prefs";
    private static final String KEY_ENABLED = "context_summary_enabled";
    private static final String KEY_NOTES = "context_summary_notes";
    private ContextSummaryStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context context) {
        return context != null && prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean value) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply();
        if (!value) PreviousQuestionStore.clear();
    }

    public static String notes(Context context) {
        return context == null ? "" : prefs(context).getString(KEY_NOTES, "");
    }

    public static void setNotes(Context context, String text) {
        if (context == null) return;
        String value = text == null ? "" : text.trim();
        if (value.length() > LIMIT) value = value.substring(0, LIMIT);
        prefs(context).edit().putString(KEY_NOTES, value).apply();
    }
}