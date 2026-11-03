package com.dekk.yanyu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.List;

/**
 * 模块功能页公告：抖音群被封，引导用户加入 KOOK 频道。
 *
 * <p>显示时机与 {@link SponsorPage#prompt(Activity)} 相同 —— 每次「打开」功能页时弹出
 * （见 {@link DekkSettingsPage#show()}）。
 *
 * <p>两个按钮：「关闭」直接收起；「添加」用系统 Intent 打开 KOOK 频道链接，
 * 若本机没有可处理该链接的应用，则退化为把链接复制到剪贴板。
 */
final class Announcement {

    /** KOOK 频道邀请链接（公告里「添加」按钮跳转的目标）。 */
    private static final String KOOK_URL = "https://kook.vip/YOUR_INVITE";

    /** 打开功能页时弹公告（无后续动作）。 */
    static void prompt(final Activity activity) {
        prompt(activity, null);
    }

    /**
     * 打开功能页时弹公告；公告被关掉之后执行 {@code next}。
     *
     * <p>用串行的方式是为了避免和别的弹窗（例如赞助提示）**同时**显示 —— 同时 show 两个
     * AlertDialog 时后弹的那个会盖在上面，看起来就成了"先赞助后公告"。
     */
    static void prompt(final Activity activity, final Runnable next) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            if (next != null) {
                next.run();
            }
            return;
        }
        AlertDialog dialog = new ModuleDialog(activity)
                .setTitle("公告")
                .setMessage("抖音群已被封禁，后续通知与交流请到 KOOK 频道。\n\n"
                        + "点「添加」直接打开 KOOK 频道：\n" + KOOK_URL)
                .setNegativeButton("关闭", null)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        open(activity);
                    }
                })
                .show();
        if (next != null) {
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface dialog) {
                    next.run();
                }
            });
        }
    }

    /** 常见浏览器包名（按优先级依次尝试，命中就直接用它打开，不弹应用选择器）。 */
    private static final String[] BROWSER_PACKAGES = {
            "com.android.chrome", "com.heytap.browser", "com.android.browser", "com.miui.browser",
            "com.huawei.browser", "com.vivo.browser", "com.sec.android.app.sbrowser",
            "com.quark.browser", "com.UCMobile", "com.tencent.mtt", "com.microsoft.emmx",
            "org.mozilla.firefox"
    };

    /** 点「添加」：直接用浏览器打开 KOOK 频道（优先明确指定浏览器，避免弹系统选择器）。 */
    static void open(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        final Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(KOOK_URL));
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            PackageManager manager = activity.getPackageManager();
            // 1) 已知浏览器包名优先 —— setPackage 后不会弹选择器
            for (int i = 0; i < BROWSER_PACKAGES.length; i++) {
                Intent target = new Intent(view);
                target.setPackage(BROWSER_PACKAGES[i]);
                if (manager.resolveActivity(target, 0) != null) {
                    activity.startActivity(target);
                    return;
                }
            }
            // 2) 退一步：找任意能打开 http 链接的应用，但排除 KOOK 客户端本身
            //    （用户要的是"跳浏览器"，不是被 KOOK App 接管）
            List<ResolveInfo> handlers = manager.queryIntentActivities(view, 0);
            if (handlers != null) {
                for (int i = 0; i < handlers.size(); i++) {
                    ResolveInfo info = handlers.get(i);
                    if (info == null || info.activityInfo == null) {
                        continue;
                    }
                    String pkg = info.activityInfo.packageName;
                    if (pkg == null || pkg.toLowerCase().contains("kook")) {
                        continue;
                    }
                    Intent target = new Intent(view);
                    target.setPackage(pkg);
                    try {
                        activity.startActivity(target);
                        return;
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {
            HotBridge.log("[DEKK][公告] 打开浏览器失败: " + t);
        }
        // 3) 实在没有浏览器：复制链接兜底
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("dekk-kook", KOOK_URL));
            }
            android.widget.Toast.makeText(activity, "已复制 KOOK 频道链接", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    private Announcement() { }
}