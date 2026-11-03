package com.dekk.yanyu;

import de.robv.android.xposed.XC_MethodHook;

/** hook 句柄门面，替代 {@code XC_MethodHook.Unhook}。 */
public class HotUnhook {

    private final XC_MethodHook.Unhook real;

    HotUnhook(XC_MethodHook.Unhook real) {
        this.real = real;
    }

    public void unhook() {
        if (real != null) real.unhook();
    }
}