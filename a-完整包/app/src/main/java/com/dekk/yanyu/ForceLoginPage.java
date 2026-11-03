package com.dekk.yanyu;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * 「强开登录」二级页：只有一个开关（邮箱账号登录）。
 *
 * <p>2026-11-01 起<b>取消赞助限制</b>，普通用户也能看到、也能打开：
 * <ul>
 *   <li>首页「登录」分类对所有人可见（入口在 {@link DekkSettingsPage}）；</li>
 *   <li>开关<b>默认关闭</b>，用户自己打开即生效；</li>
 *   <li>打开不再向服务器验身份（sponsor_check），不弹「仅赞助用户可用」。</li>
 * </ul>
 */
final class ForceLoginPage {

    private ForceLoginPage() { }

    static void show(final Activity activity) {
        final CenterPage page = new CenterPage(activity, "强开登录", null).navigationKey("force-login");
        page.show();
        render(activity, page);
    }

    private static void render(final Activity activity, final CenterPage page) {
        page.body.removeAllViews();

        LinearLayout card = page.card();
        MiuixUi.switchRow(activity, card, page.night, MiuixIcon.DOC, "强开邮箱账号登录",
                ForceLoginSettings.email(activity),
                new M3SwitchView.OnCheckedChangeListener() {
                    public void onCheckedChanged(M3SwitchView view, boolean checked) {
                        toggle(activity, checked);
                    }
                });
    }

    /** 开关点击：直接写开关值，不做任何身份校验。 */
    private static void toggle(final Activity activity, final boolean checked) {
        ForceLoginSettings.setEmail(activity, checked);
        if (checked) {
            toast(activity, "已开启，重新进入 DeepSeek 登录页即可看到效果");
        }
    }

    private static void toast(Activity activity, String text) {
        try {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 界面已销毁时忽略
        }
    }
}
