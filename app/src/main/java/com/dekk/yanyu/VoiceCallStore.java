package com.dekk.yanyu;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 语音通话的开关与设备相关参数。
 *
 * <p>全部存在 {@code dekk_prefs} 里，和模块其它功能一致（不需要存储权限，
 * 也不写宿主的 MMKV —— 宿主的 {@code key_auto_tts_enabled} 只在通话期间用 hook 临时接管）。
 */
final class VoiceCallStore {

    private static final String PREFS = "dekk_prefs";
    private static final String KEY_ENABLED = "voice_call_enabled";
    private static final String KEY_SPEAK = "voice_call_auto_speak";
    private static final String KEY_X = "voice_call_key_x";
    private static final String KEY_Y = "voice_call_key_y";

    private VoiceCallStore() { }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, 0);
    }

    /**
     * 语音通话整块功能的总闸（编译期常量）。
     *
     * <p>2026-10-30 按需求先<b>完全关掉</b>：设置页不显示入口、默认关闭、
     * 隐藏提示词也不携带、连钩子都不装。要恢复只把这里改成 {@code true} ——
     * {@code VoiceCallHook / VoiceCallCard / VoiceCallManager} 的代码全部保留。
     */
    static final boolean FEATURE_ON = false;

    /** 功能总开关。关掉以后通话页、来电、相机拦截全部不介入。 */
    static boolean enabled(Context context) {
        return FEATURE_ON && context != null && prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean value) {
        if (context != null) prefs(context).edit().putBoolean(KEY_ENABLED, value).apply();
    }

    /** 通话中是否让 AI 的回复自动朗读（等价于用户手动点那条「朗读」）。默认开。 */
    static boolean autoSpeak(Context context) {
        return context == null || prefs(context).getBoolean(KEY_SPEAK, true);
    }

    static void setAutoSpeak(Context context, boolean value) {
        if (context != null) prefs(context).edit().putBoolean(KEY_SPEAK, value).apply();
    }

    /** 宿主「按住说话」按钮的中心坐标（屏幕绝对像素）；-1 表示还没标定过。 */
    static float keyX(Context context) {
        return context == null ? -1f : prefs(context).getFloat(KEY_X, -1f);
    }

    static float keyY(Context context) {
        return context == null ? -1f : prefs(context).getFloat(KEY_Y, -1f);
    }

    static boolean hasKeyPoint(Context context) {
        return keyX(context) > 0f && keyY(context) > 0f;
    }

    static void setKeyPoint(Context context, float x, float y) {
        if (context == null) return;
        prefs(context).edit().putFloat(KEY_X, x).putFloat(KEY_Y, y).apply();
        HotBridge.log("[DEKK][通话] 语音键坐标已记录: " + (int) x + "," + (int) y);
    }

    static void clearKeyPoint(Context context) {
        if (context == null) return;
        prefs(context).edit().remove(KEY_X).remove(KEY_Y).apply();
    }
}
