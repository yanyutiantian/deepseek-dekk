package com.dekk.yanyu;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;

/**
 * 壳的首页：界面和 dekk 模块首页一致（同一个 {@code activity_main} 布局 + 同一条
 * ActionBar 激活状态）。
 */
public class MainActivity extends Activity {

    public static boolean isModuleActivated() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setSubtitle(isModuleActivated() ? R.string.xposed_activated : R.string.xposed_unactivated);
        }
    }
}
