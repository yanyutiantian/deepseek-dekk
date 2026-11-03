package com.dekk.yanyu;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 模块自己的启动页：只负责说明与跳转 DeepSeek，界面按 Miuix 规范。 */
public class MainActivity extends Activity {

    /**
     * 抓包调试开关：
     * - true  =关闭 Java 的 HTTPS 证书校验（信任所有证书），黄鸟/Charles 才能解密
     * - false =启用系统证书校验（**发版必须是 false**）
     *
     * 现在按要求设为 true 供抓包使用。
     */
    public static boolean INSECURE_TLS = true;

    private static final String HOST_PACKAGE = "com.deepseek.chat";
    private static final String HOST_ACTIVITY = "com.deepseek.chat.MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final boolean dark = ModuleTheme.dark(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(ModuleTheme.background(dark));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = MiuixUi.dp(this, ModuleTheme.PAGE_PADDING);
        content.setPadding(pad, MiuixUi.dp(this, 28), pad, MiuixUi.dp(this, 40));
        scroll.addView(content);
        setContentView(scroll);

        // 仅保留红圈内的状态卡片
        content.addView(MiuixUi.statusCard(this, dark, "DEKK模块",
                "模块功能与设置都在 DeepSeek 里", true), blockParams(14));

        // 仅保留红圈内的“打开 DeepSeek”操作按钮
        LinearLayout actions = MiuixUi.card(this, dark);
        MiuixUi.row(this, actions, dark, MiuixIcon.CHAT, "打开 DeepSeek",
                "进入「我的 → 设置」即可看到模块入口", true, new View.OnClickListener() {
            public void onClick(View v) { openHost(); }
        });
        content.addView(actions, blockParams(14));
    }

    private LinearLayout.LayoutParams blockParams(int bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = MiuixUi.dp(this, bottomDp);
        return params;
    }

    /** 跳转 DeepSeek：先按包名找启动页，找不到再按组件名直接拉起。 */
    private void openHost() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(HOST_PACKAGE);
        if (launch == null) {
            launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setComponent(new ComponentName(HOST_PACKAGE, HOST_ACTIVITY));
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(launch);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开 DeepSeek：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    /** 模块管理器加载本进程时会被 DekkEntry 挂钩成 true。 */
    public static boolean isModuleActivated() {
        return false;
    }
}
