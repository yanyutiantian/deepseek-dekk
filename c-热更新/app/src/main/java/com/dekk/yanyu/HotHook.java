package com.dekk.yanyu;

/**
 * hook 回调门面，替代 {@code de.robv.android.xposed.XC_MethodHook}。
 *
 * <p><b>为什么需要</b>：LSPosed 加载模块时会重写模块 APK 的 dex，把模块代码里写的
 * {@code de.robv.android.xposed.*} 换成它内部的混淆名。壳自己的类因此能正常用 API；
 * 但我们下载的 payload dex 是**原始 dex**，直接引用那些公开类名会
 * {@code NoClassDefFoundError}（实测，而且 ART 会校验 loadClass 返回的类名，绕不过去）。
 *
 * <p>所以 payload 统一用这层门面，由壳（被重写过的那一侧）{@link HotHooks} 把 HotHook
 * 包成真正的 XC_MethodHook。方法名故意与 Xposed 保持一致
 * （{@code beforeHookedMethod} / {@code afterHookedMethod}），
 * 于是 payload 里原有的 hook 代码只需要换类名，回调体和 {@code @Override} 都不用动。
 */
public abstract class HotHook {

    /** Xposed 默认优先级是 50。 */
    public static final int PRIORITY_DEFAULT = 50;

    private final int priority;

    protected HotHook() {
        this(PRIORITY_DEFAULT);
    }

    protected HotHook(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    /** 与原 API 同名同形。 */
    protected void beforeHookedMethod(HotParam param) throws Throwable { }

    /** 与原 API 同名同形。 */
    protected void afterHookedMethod(HotParam param) throws Throwable { }

    /** 只有门面适配器（HotHooks）会调。 */
    final void dispatchBefore(HotParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    /** 只有门面适配器（HotHooks）会调。 */
    final void dispatchAfter(HotParam param) throws Throwable {
        afterHookedMethod(param);
    }
}