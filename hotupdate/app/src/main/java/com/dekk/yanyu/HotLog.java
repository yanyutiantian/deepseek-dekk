package com.dekk.yanyu;

import de.robv.android.xposed.XposedBridge;

/** 壳自己的日志，统一前缀方便在 LSPosed 日志里过滤。 */
final class HotLog {

    private static final String TAG = "[DEKK][热更新] ";

    static void i(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // 类还没被 Xposed 环境挂上时（比如壳自己的界面线程）忽略
        }
    }

    /** 直接把异常连同堆栈打进日志（排查 payload 启动失败时最关键）。 */
    static void i(Throwable throwable) {
        try {
            XposedBridge.log(throwable);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    private HotLog() { }
}