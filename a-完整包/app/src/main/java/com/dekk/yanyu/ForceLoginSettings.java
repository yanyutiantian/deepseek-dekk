package com.dekk.yanyu;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「强开登录」的开关。
 *
 * <p>规则（2026-11-01 起<b>不再是赞助限定</b>，普通用户同样可用）：
 * <ol>
 *   <li><b>默认关闭</b>：任何用户都可以自己打开，打开即生效；</li>
 *   <li>生效条件只看开关值本身，不再向服务器验身份（{@link SponsorApi} 与本功能无关了）；</li>
 *   <li>没有任何「非赞助就压回关闭」的逻辑，普通用户的开关不会被清掉。</li>
 * </ol>
 */
final class ForceLoginSettings {

    private static final String PREF = "dekk_prefs";
    private static final String KEY_GOOGLE = "force_login_google";
    private static final String KEY_EMAIL = "force_login_email";

    private ForceLoginSettings() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /**
     * 强开谷歌账号登录：开关值（默认关）。
     *
     * <p>2026-10-28 起 UI 上已经没有这个开关了（强开登录页只留邮箱那一行），
     * 方法保留是为了手机端那份 {@code GoogleLoginHook} 还能编译；新代码不要再接它。
     */
    static boolean google(Context c) { return prefs(c).getBoolean(KEY_GOOGLE, false); }

    static void setGoogle(Context c, boolean value) {
        prefs(c).edit().putBoolean(KEY_GOOGLE, value).apply();
    }

    /** 强开邮箱账号登录：开关值（默认关）。 */
    static boolean email(Context c) { return prefs(c).getBoolean(KEY_EMAIL, false); }

    static void setEmail(Context c, boolean value) {
        prefs(c).edit().putBoolean(KEY_EMAIL, value).apply();
    }

    /** 是否真正生效：只看开关值，与赞助身份无关。 */
    static boolean googleActive(Context c) {
        return c != null && google(c);
    }

    static boolean emailActive(Context c) {
        return c != null && email(c);
    }
}
