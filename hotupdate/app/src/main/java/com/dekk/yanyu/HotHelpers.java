package com.dekk.yanyu;

import de.robv.android.xposed.XposedHelpers;

/**
 * 替代 {@code XposedHelpers} 里 payload 实际用到的那部分（只覆盖用到的，子集足够）。
 *
 * <p>变参方法都会先过一遍 {@link HotHooks#wrapArgs(Object[])}，把里面的门面 hook
 * 换成真 hook —— 这是必须的，否则 {@code findAndHookMethod(...)} 的最后一个参数
 * 传进去的对象 LSPosed 不认。
 */
public final class HotHelpers {

    // ---- 反射取类 / 字段 / 调用 ----

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return XposedHelpers.findClass(className, classLoader);
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return XposedHelpers.getObjectField(obj, fieldName);
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        XposedHelpers.setObjectField(obj, fieldName, value);
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        return XposedHelpers.getStaticObjectField(clazz, fieldName);
    }

    public static int getIntField(Object obj, String fieldName) {
        return XposedHelpers.getIntField(obj, fieldName);
    }

    public static long getLongField(Object obj, String fieldName) {
        return XposedHelpers.getLongField(obj, fieldName);
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return XposedHelpers.getBooleanField(obj, fieldName);
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return XposedHelpers.callMethod(obj, methodName, HotHooks.wrapArgs(args));
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return XposedHelpers.callStaticMethod(clazz, methodName, HotHooks.wrapArgs(args));
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        return XposedHelpers.newInstance(clazz, HotHooks.wrapArgs(args));
    }

    // ---- 装 hook ----

    public static HotUnhook findAndHookMethod(Class<?> clazz, String methodName,
                                              Object... parameterTypesAndCallback) {
        return HotHooks.wrapUnhook(XposedHelpers.findAndHookMethod(
                clazz, methodName, HotHooks.wrapArgs(parameterTypesAndCallback)));
    }

    public static HotUnhook findAndHookMethod(String className, ClassLoader classLoader, String methodName,
                                              Object... parameterTypesAndCallback) {
        Class<?> clazz = XposedHelpers.findClass(className, classLoader);
        return HotHooks.wrapUnhook(XposedHelpers.findAndHookMethod(
                clazz, methodName, HotHooks.wrapArgs(parameterTypesAndCallback)));
    }

    public static HotUnhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        return HotHooks.wrapUnhook(XposedHelpers.findAndHookConstructor(
                clazz, HotHooks.wrapArgs(parameterTypesAndCallback)));
    }

    private HotHelpers() { }
}