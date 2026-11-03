package com.dekk.yanyu;

import android.app.AndroidAppHelper;
import android.content.Context;

/** 替代 {@code AndroidAppHelper}：拿当前 Application（模块代码跑在宿主进程里，拿到的就是宿主）。 */
public final class HotApp {

    public static Context currentApplication() {
        return AndroidAppHelper.currentApplication();
    }

    private HotApp() { }
}