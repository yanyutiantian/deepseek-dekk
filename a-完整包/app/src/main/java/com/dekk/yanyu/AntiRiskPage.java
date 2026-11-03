package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 风控对抗设置页：环境伪装 / 原版签名 / 指纹重置（开关 + 状态 + 手动重置）。
 *
 * <p>入口在「我的 → 账号 → 风控对抗」。
 */
public final class AntiRiskPage {

    private AntiRiskPage() { }

    public static void show(final Activity activity) {
        // KOOK 绑定门禁：未绑定则弹窗
        if(!BindGate.require(activity,null))return;
        final CenterPage page = new CenterPage(activity, "风控对抗", null);
        final Context app = activity.getApplicationContext();
        final android.content.SharedPreferences sp = app.getSharedPreferences("dekk_fp_reset", 0);

        // ── 开关 ──
        page.section("对抗开关");
        final LinearLayout card = page.card();

        MiuixUi.switchRow(activity, card, page.night, MiuixIcon.SHIELD,
                "环境检测伪装", FingerprintReset.maskEnabled(app),
                new M3SwitchView.OnCheckedChangeListener() {
                    public void onCheckedChanged(M3SwitchView view, boolean checked) {
                        FingerprintReset.setMaskEnabled(app, checked);
                        AntiDetect.setEnabled(checked);
                        page.message(checked
                                ? "已开启：Xposed / Magisk / su / adb / 调试 / 代理等特征将被隐藏"
                                : "已关闭环境检测伪装");
                    }
                });

        MiuixUi.switchRow(activity, card, page.night, MiuixIcon.CHECK,
                "原版签名伪装", sp.getBoolean("sign", true),
                new M3SwitchView.OnCheckedChangeListener() {
                    public void onCheckedChanged(M3SwitchView view, boolean checked) {
                        sp.edit().putBoolean("sign", checked).apply();
                        SignatureMask.setEnabled(checked);
                        page.message(checked
                                ? "已开启：包签名伪装为官方 DeepSeek 签名（微信登录必需）"
                                : "已关闭原版签名伪装 —— 微信登录可能失败");
                    }
                });

        MiuixUi.switchRow(activity, card, page.night, MiuixIcon.GAUGE,
                "封禁自动重置指纹", FingerprintReset.enabled(app),
                new M3SwitchView.OnCheckedChangeListener() {
                    public void onCheckedChanged(M3SwitchView view, boolean checked) {
                        FingerprintReset.setEnabled(app, checked);
                        page.message(checked
                                ? "已开启：检测到账号被限制时自动重置设备指纹"
                                : "已关闭自动重置");
                    }
                });

        page.caption(card, "环境伪装：把数美（反欺诈 SDK）能采集到的 Xposed / Magisk / su / adb / 调试 / 代理"
                + "等特征隐藏，并隐藏 Magisk、LSPatch 等管理器的安装痕迹。\n\n"
                + "原版签名：重打包 / LSPatch 会改变包签名，微信登录会校验签名 —— 开启后读到的仍是官方签名。\n\n"
                + "指纹重置：检测到服务端下发“禁言 / 限制”时，清空数美的设备指纹缓存（.smtfb 与 \"bd\" 配置），"
                + "避免换小号后因同一台设备被连坐。");

        // ── 状态 ──
        page.section("状态");
        LinearLayout status = page.card();
        TextView info = page.label(statusText(app), 13);
        info.setTextColor(page.secondary);
        status.addView(info);

        page.action("立即重置设备指纹", new Runnable() {
            public void run() {
                boolean ok = FingerprintReset.reset(app, "手动重置");
                page.message(ok ? "已重置设备指纹，建议重启 App 后使用" : "未重置（可能被 10 分钟防抖挡住）");
            }
        });

        page.show();
    }

    private static String statusText(Context app) {
        int count = FingerprintReset.resetCount(app);
        long last = FingerprintReset.lastResetAt(app);
        String reason = FingerprintReset.lastReason(app);
        StringBuilder sb = new StringBuilder();
        sb.append("累计重置：").append(count).append(" 次");
        if (count > 0 && last > 0) {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(last));
            sb.append("\n上次重置：").append(time);
            if (reason != null && reason.length() > 0) sb.append("（").append(reason).append("）");
        }
        sb.append("\n\n说明：重置后需要重启 App，数美才会以新设备重新初始化；"
                + "本功能只改客户端可见的设备特征，服务端判定（内容违规等）不受影响。");
        return sb.toString();
    }
}