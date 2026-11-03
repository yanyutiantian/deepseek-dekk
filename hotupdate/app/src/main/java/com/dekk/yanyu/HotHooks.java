package com.dekk.yanyu;

import java.util.LinkedHashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 门面适配器：把 payload 的 {@link HotHook} 包成真正的 {@code XC_MethodHook}。
 *
 * <p>这一层**只能在壳里工作**（壳的 dex 会被 LSPosed 重写，所以能直接调真 API）。
 * 主模块里也放着同一份代码，是为了让 payload 能独立编译、并且在「整包安装」模式下也能跑。
 */
public final class HotHooks {

    /** 复用参数门面对象，避免每次回调都分配（热点 hook 会被调很多次）。 */
    private static final ThreadLocal<HotParam> PARAMS = new ThreadLocal<HotParam>();

    /** 包一层真 hook：回调里把真参数转成 {@link HotParam} 再交给 payload。 */
    public static XC_MethodHook wrap(final HotHook hook) {
        return new XC_MethodHook(hook.priority()) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                HotParam wrapper = wrapper();
                wrapper.bind(param);
                try {
                    hook.dispatchBefore(wrapper);
                } finally {
                    wrapper.flush();
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                HotParam wrapper = wrapper();
                wrapper.bind(param);
                try {
                    hook.dispatchAfter(wrapper);
                } finally {
                    wrapper.flush();
                }
            }
        };
    }

    /** 把参数表里的 HotHook 换成真 hook（findAndHookMethod/callMethod 这类变参都要过一遍）。 */
    public static Object[] wrapArgs(Object[] args) {
        if (args == null || args.length == 0) return args;
        Object[] copy = null;
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            if (value instanceof HotHook) {
                if (copy == null) {
                    copy = new Object[args.length];
                    System.arraycopy(args, 0, copy, 0, args.length);
                }
                copy[i] = wrap((HotHook) value);
            }
        }
        return copy == null ? args : copy;
    }

    /** 把真 hook 的集合（hookAllMethods / hookAllConstructors 的返回值）换成门面句柄。 */
    public static Set<HotUnhook> wrapUnhooks(Set<XC_MethodHook.Unhook> raw) {
        Set<HotUnhook> out = new LinkedHashSet<HotUnhook>();
        if (raw == null) return out;
        for (XC_MethodHook.Unhook unhook : raw) out.add(new HotUnhook(unhook));
        return out;
    }

    /** 把真句柄换成门面句柄。 */
    public static HotUnhook wrapUnhook(XC_MethodHook.Unhook raw) {
        return new HotUnhook(raw);
    }

    private static HotParam wrapper() {
        HotParam cached = PARAMS.get();
        if (cached == null) {
            cached = new HotParam();
            PARAMS.set(cached);
        }
        return cached;
    }

    private HotHooks() { }
}