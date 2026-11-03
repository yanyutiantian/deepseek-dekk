package com.dekk.yanyu;

import java.lang.reflect.Member;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * 替代 {@code XposedBridge}：日志 + 三种 hook 安装方式。
 *
 * <p>和 {@link HotHooks} 一样，真正干活的是壳里的那份（壳的 dex 会被 LSPosed 重写）。
 */
public final class HotBridge {

    public static void log(String text) {
        XposedBridge.log(text);
    }

    public static void log(Throwable throwable) {
        XposedBridge.log(throwable);
    }

    /** 与 {@code XposedBridge.hookMethod} 同形。 */
    public static HotUnhook hookMethod(Member method, HotHook callback) {
        return HotHooks.wrapUnhook(XposedBridge.hookMethod(method, HotHooks.wrap(callback)));
    }

    /** 与 {@code XposedBridge.hookAllMethods} 同形（返回值换成门面句柄）。 */
    public static Set<HotUnhook> hookAllMethods(Class<?> clazz, String methodName, HotHook callback) {
        return HotHooks.wrapUnhooks(XposedBridge.hookAllMethods(clazz, methodName, HotHooks.wrap(callback)));
    }

    /** 与 {@code XposedBridge.hookAllConstructors} 同形（返回值换成门面句柄）。 */
    public static Set<HotUnhook> hookAllConstructors(Class<?> clazz, HotHook callback) {
        return HotHooks.wrapUnhooks(XposedBridge.hookAllConstructors(clazz, HotHooks.wrap(callback)));
    }

    private HotBridge() { }
}