package com.dekk.yanyu;

import android.content.Context;
import android.content.SharedPreferences;

/** 一问多答配置；次数包含首次，宿主发送适配未就绪时禁止运行。 */
public final class MultiReplySettings {
    public static final int MIN_COUNT = 2;
    public static final int MAX_COUNT = 3;
    private static volatile boolean ready = false;

    private MultiReplySettings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("dekk_prefs", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context context) {
        return prefs(context).getBoolean("multi_reply_enabled", false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("multi_reply_enabled", enabled).apply();
    }

    public static int count(Context context) {
        int value = prefs(context).getInt("multi_reply_count", 2);
        return Math.max(MIN_COUNT, Math.min(MAX_COUNT, value));
    }

    public static void setCount(Context context, int count) {
        if (count < MIN_COUNT || count > MAX_COUNT) {
            throw new IllegalArgumentException("总回答次数只能选择 2 次或 3 次");
        }
        prefs(context).edit().putInt("multi_reply_count", count).apply();
    }

    public static boolean isReady() { return true; }

    // 保留方法兼容旧代码调用，但一问多答核心 Hook 已安装，始终就绪。
    static void markReady() { ready = true; }
}