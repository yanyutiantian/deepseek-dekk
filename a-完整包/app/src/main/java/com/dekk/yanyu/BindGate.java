package com.dekk.yanyu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/**
 * 登录 + KOOK 账号绑定门禁。
 *
 * <p>规则（按需求）：
 * <ul>
 *   <li>打开模块必须已登录 DEKK 账号，否则直接打开登录页；</li>
 *   <li>登录后必须绑定 KOOK 账号，否则弹窗提示；点任何功能都会再次弹窗；</li>
 *   <li>绑定码 6 位（字母+数字），1 小时有效；「去绑定」自动复制 {@code /code XXXXXX} 并打开浏览器；</li>
 *   <li>绑定在 KOOK「#账号绑定」频道（000000000084876）发送 /code 完成；绑定后即刻生效。</li>
 * </ul>
 *
 * <p>接口全部走 {@code dekk.example.com/API}（该目录没有 WAF 人机验证）：
 * {@code bind_code}（生成/复用绑定码）、{@code bind_status}（查询绑定状态）。
 */
final class BindGate {

    /** KOOK 频道名（提示文案用名字，不用数字 ID）。 */
    static final String BIND_CHANNEL = "#账号绑定";

    /** 默认邀请链接（服务端返回 invite 时以服务端为准）。 */
    private static final String DEFAULT_INVITE = "https://kook.vip/YOUR_INVITE";

    private static final String PREF = "dekk_bind_gate";

    /** 本地绑定状态的有效期：超过就重新向服务端确认（保证「绑定后即刻生效」）。 */
    private static final long CACHE_MS = 5000L;

    private static long lastCheckAt = 0L;
    private static boolean cachedBound = false;
    private static String pendingCode = "";
    private static String pendingInvite = DEFAULT_INVITE;
    private static boolean dialogShowing = false;

    private BindGate() { }

    // ─────────────────────────── 对外门禁 ───────────────────────────

    /**
     * 功能入口门禁。
     *
     * @param next 通过后要做的事；可为 null（只做放行/拦截判断）
     * @return true = 已登录且已绑定（next 会被执行）；false = 已弹窗/已打开登录页
     */
    static boolean require(final Activity activity, final Runnable next) {
        if (activity == null || activity.isFinishing()) return false;

        // ① 必须登录：直接打开登录页
        if (AccountStore.token(activity).length() == 0) {
            AccountPages.open(activity);
            return false;
        }

        // ② 绑定状态：缓存过期就异步刷新
        long now = System.currentTimeMillis();
        if (now - lastCheckAt > CACHE_MS) refresh(activity, true);
        if (bound(activity)) {
            if (next != null) next.run();
            return true;
        }

        // ③ 未绑定：弹窗（点任何功能都会走到这里）
        showBindDialog(activity, next);
        return false;
    }

    /** 是否已绑定（本地缓存 + 存储）。 */
    static boolean bound(Context c) {
        if (cachedBound) return true;
        return c != null && prefs(c).getBoolean("bound", false);
    }

    /** 供「我的」页显示绑定状态。 */
    static String boundText(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("bound", false)) return "未绑定";
        String name = p.getString("kook_name", "");
        return name.length() > 0 ? ("已绑定：" + name) : "已绑定";
    }

    // ─────────────────────────── 状态刷新 ───────────────────────────

    /** 异步向服务端确认绑定状态。silent=true 时不弹错误提示（用于后台刷新）。 */
    static void refresh(final Activity activity, final boolean silent) {
        if (activity == null) return;
        final Context ctx = activity.getApplicationContext();
        PersonaApi.request(activity, "bind_status", null, new PersonaApi.Callback() {
            public void done(JSONObject result, String error) {
                if (error != null) {
                    if (!silent) toast(ctx, "绑定状态查询失败：" + error);
                    return;
                }
                if (result == null) return;
                setBound(ctx, result.optBoolean("bound"), result.optString("kook_name", ""));
                if (!silent) {
                    toast(ctx, result.optBoolean("bound") ? "已确认绑定成功，全部功能已启用" : "还没有查到绑定记录");
                }
            }
        });
    }

    private static void setBound(Context c, boolean value, String kookName) {
        cachedBound = value;
        lastCheckAt = System.currentTimeMillis();
        if (c != null) {
            SharedPreferences.Editor e = prefs(c).edit().putBoolean("bound", value);
            if (kookName != null && kookName.length() > 0) e.putString("kook_name", kookName);
            e.apply();
        }
    }

    // ─────────────────────────── 绑定弹窗 ───────────────────────────

    private static void showBindDialog(final Activity activity, final Runnable next) {
        if (dialogShowing || activity == null || activity.isFinishing()) return;
        dialogShowing = true;

        final AlertDialog dialog = new ModuleDialog(activity)
                .setTitle("需要绑定 KOOK 账号")
                .setMessage("使用模块前，需要把当前 DEKK 账号绑定到你的 KOOK 账号。\n\n正在获取绑定码…")
                .setPositiveButton("去绑定", null)
                .setNeutralButton("我已绑定", null)
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) {
                dialogShowing = false;
            }
        });
        dialog.show();

        // 「去绑定」：复制 /code XXXXXX + 打开浏览器
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String code = pendingCode;
                if (code == null || code.length() == 0) {
                    toast(activity, "绑定码还没取到，请稍候再试");
                    return;
                }
                copy(activity, "/code " + code);
                openInvite(activity);
                toast(activity, "已复制「/code " + code + "」，请在 KOOK " + BIND_CHANNEL + " 频道发送");
            }
        });

        // 「我已绑定」：立刻查询，成功则放行
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final Context ctx = activity.getApplicationContext();
                PersonaApi.request(activity, "bind_status", null, new PersonaApi.Callback() {
                    public void done(JSONObject result, String error) {
                        if (result == null) {
                            toast(ctx, error == null ? "查询失败" : error);
                            return;
                        }
                        boolean ok = result.optBoolean("bound");
                        setBound(ctx, ok, result.optString("kook_name", ""));
                        if (ok) {
                            toast(ctx, "绑定成功，已启用全部功能");
                            dialog.dismiss();
                            if (next != null) next.run();
                        } else {
                            dialog.setMessage("还没有查到绑定记录。\n\n请在 KOOK " + BIND_CHANNEL + " 频道里发送：\n"
                                    + "/code " + (pendingCode.length() > 0 ? pendingCode : "XXXXXX")
                                    + "\n\n绑定码 1 小时内有效。");
                        }
                    }
                });
            }
        });

        // 异步取绑定码，拿到后刷新弹窗正文
        PersonaApi.request(activity, "bind_code", null, new PersonaApi.Callback() {
            public void done(JSONObject result, String error) {
                if (error != null) {
                    dialog.setMessage("绑定码获取失败：" + error + "\n\n请检查网络后重新打开模块。");
                    return;
                }
                if (result == null) return;
                if (result.optBoolean("bound")) {
                    // 已经绑定了（比如刚在频道里发过）
                    setBound(activity.getApplicationContext(), true, result.optString("kook_name", ""));
                    toast(activity, "已绑定成功");
                    dialog.dismiss();
                    if (next != null) next.run();
                    return;
                }
                pendingCode = result.optString("code", "");
                String invite = result.optString("invite", DEFAULT_INVITE);
                if (invite != null && invite.length() > 0) pendingInvite = invite;
                String expire = result.optString("expires_at", "");
                dialog.setMessage("使用模块前，需要把当前 DEKK 账号绑定到你的 KOOK 账号。\n\n"
                        + "① 点「去绑定」：会自动复制下面的指令并打开 KOOK\n"
                        + "② 在 " + BIND_CHANNEL + " 频道里发送：\n"
                        + "/code " + pendingCode + "\n\n"
                        + "绑定码 1 小时内有效"
                        + (expire.length() > 0 ? ("（" + expire + " 到期）") : "") + "。");
            }
        });
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, 0);
    }

    private static void toast(Context c, String text) {
        try {
            Toast.makeText(c, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private static void copy(Context c, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("dekk-bind", text));
        } catch (Throwable ignored) {
        }
    }

    /** 打开 KOOK 邀请页：优先直接用已知浏览器，失败再退化为通用 ACTION_VIEW。 */
    private static void openInvite(final Activity activity) {
        final String url = pendingInvite != null && pendingInvite.length() > 0 ? pendingInvite : DEFAULT_INVITE;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String[] browsers = {
                    "com.android.chrome", "com.android.browser", "com.tencent.mtt",
                    "com.UCMobile", "com.quark.browser", "com.heytap.browser",
                    "com.miui.browser", "com.huawei.browser", "com.vivo.browser", "org.mozilla.firefox",
            };
            for (int i = 0; i < browsers.length; i++) {
                try {
                    Intent direct = new Intent(view);
                    direct.setPackage(browsers[i]);
                    activity.startActivity(direct);
                    return;
                } catch (Throwable ignored) {
                }
            }
            activity.startActivity(view);
        } catch (Throwable t) {
            copy(activity, url);
            toast(activity, "没有可用浏览器，已复制邀请链接：" + url);
        }
    }

    /** 供列表项判断用（预留）。 */
    static List<Object> none() {
        return FingerprintReset.emptyList();
    }
}