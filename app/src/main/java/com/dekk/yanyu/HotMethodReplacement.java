package com.dekk.yanyu;

/** 替代 {@code XC_MethodReplacement}：把结果直接替换成常量。 */
public class HotMethodReplacement extends HotHook {

    protected HotMethodReplacement() { }

    /** 与原 API 同名：直接返回一个把结果写死的 hook。 */
    public static HotHook returnConstant(final Object value) {
        return new HotHook() {
            @Override
            protected void beforeHookedMethod(HotParam param) {
                param.setResult(value);
            }
        };
    }
}