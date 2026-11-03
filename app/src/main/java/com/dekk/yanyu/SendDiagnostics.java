package com.dekk.yanyu;

import android.content.Context;
import java.lang.reflect.Method;

/** 被动诊断，不改变参数、返回值或异常；不记录消息正文和会话标识。 */
public final class SendDiagnostics {
    private static final String KEY = "send_diagnostics_enabled";
    private SendDiagnostics() { }

    public static boolean enabled(Context context) {
        return context.getSharedPreferences("dekk_prefs", Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    public static void setEnabled(Context context, boolean value) {
        context.getSharedPreferences("dekk_prefs", Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply();
    }

    public static void install(ClassLoader loader) {
        observe(loader, "hx7", "t", 10, "发送协程入口");
        observe(loader, "op2", "b", 2, "创建请求流");
    }

    private static void observe(ClassLoader loader, String type, String name, int count, final String label) {
        try {
            Class<?> target = HotHelpers.findClass(type, loader);
            for (Method method : target.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterTypes().length != count) continue;
                HotBridge.hookMethod(method, new HotHook() {
                    protected void beforeHookedMethod(HotParam param) {
                        try {
                            Context context = HotApp.currentApplication();
                            if (context != null && enabled(context)) {
                                param.setObjectExtra("dekk_diag", Boolean.TRUE);
                                HotBridge.log("[DEKK][发送诊断] " + label + "：进入");
                            }
                        } catch (Throwable ignored) { }
                    }
                    protected void afterHookedMethod(HotParam param) {
                        try {
                            if (!Boolean.TRUE.equals(param.getObjectExtra("dekk_diag"))) return;
                            if (param.hasThrowable()) {
                                HotBridge.log("[DEKK][发送诊断] " + label + "：同步异常类型="
                                        + param.getThrowable().getClass().getName());
                            } else {
                                HotBridge.log("[DEKK][发送诊断] " + label
                                        + "：已返回或挂起（不代表流式请求成功）");
                            }
                        } catch (Throwable ignored) { }
                    }
                });
                return;
            }
            HotBridge.log("[DEKK][发送诊断] 未匹配：" + label);
        } catch (Throwable t) {
            HotBridge.log("[DEKK][发送诊断] 安装失败：" + label + " / " + t.getClass().getName());
        }
    }
}