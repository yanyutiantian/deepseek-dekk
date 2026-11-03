package com.dekk.yanyu;

import java.lang.reflect.Member;

import de.robv.android.xposed.XC_MethodHook;

/**
 * hook 参数门面，替代 {@code de.robv.android.xposed.XC_MethodHook.MethodHookParam}。
 *
 * <p>字段名（args / thisObject / method）与原 API 一致，所以 payload 里
 * {@code p.args[0] = x}、{@code p.thisObject} 这类写法不用改。
 * {@code args} 直接指向真实参数里的**同一个数组**，元素改动立刻生效；
 * 万一 payload 整体替换了 args / thisObject，{@link #flush()} 会写回去。
 */
public class HotParam {

    /** 调用参数（与真实参数共享同一个数组）。 */
    public Object[] args;

    /** 被 hook 的对象。 */
    public Object thisObject;

    /** 被 hook 的方法 / 构造器。 */
    public Member method;

    private XC_MethodHook.MethodHookParam real;

    public HotParam() { }

    /** 绑定到真实参数对象（每次回调前由门面适配器调用）。 */
    void bind(XC_MethodHook.MethodHookParam target) {
        this.real = target;
        this.args = target == null ? null : target.args;
        this.thisObject = target == null ? null : target.thisObject;
        this.method = target == null ? null : target.method;
    }

    /** 把整体替换过的字段写回真实参数。 */
    void flush() {
        if (real == null) return;
        real.args = this.args;
        real.thisObject = this.thisObject;
        real.method = this.method;
    }

    public Object getResult() {
        return real == null ? null : real.getResult();
    }

    public void setResult(Object result) {
        if (real != null) real.setResult(result);
    }

    public boolean hasThrowable() {
        return real != null && real.hasThrowable();
    }

    public Throwable getThrowable() {
        return real == null ? null : real.getThrowable();
    }

    public void setThrowable(Throwable throwable) {
        if (real != null) real.setThrowable(throwable);
    }

    public Object getObjectExtra(String key) {
        return real == null ? null : real.getObjectExtra(key);
    }

    public void setObjectExtra(String key, Object value) {
        if (real != null) real.setObjectExtra(key, value);
    }
}