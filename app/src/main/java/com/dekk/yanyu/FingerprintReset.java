package com.dekk.yanyu;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 设备指纹重置：检测到「服务端下发处罚（禁言）」时，把宿主编入的数美设备指纹整体清掉，
 * 避免换小号后因为同一台设备的指纹被连坐。
 *
 * <p>逆向依据（2.5.1，classes_smali）：
 * <ul>
 *   <li>宿主使用【数美反欺诈 SDK】：{@code qi8.smali} 里
 *       {@code SmAntiFraud.create(ctx, SmOption{Organization=P9usCUBauxft8eAmUXaZ})}；</li>
 *   <li>设备指纹的本地缓存有两处：
 *       <ul>
 *         <li>{@code context.getFilesDir()/.smtfb}（{@code l1l11I11l.smali}）；</li>
 *         <li>SharedPreferences {@code "bd"}，键 {@code "bdk"} / {@code "lc"}
 *             （{@code l11l111ll1Il.smali}）。</li>
 *       </ul>
 *   </li>
 *   <li>环境采集（Java 侧）出口：{@code com.ishumei.smantifraud.l1111l111111Il}
 *       的静态方法（返回 Map / List）；Xposed / Magisk / adb / debug / hookJava 都在其中。</li>
 *   <li>上报地址：{@code fengkongcloud.com/deviceprofile/v4}（字段编号 a1…a167+）。</li>
 * </ul>
 *
 * <p>2.5.1 的禁言状态链路（与 {@link FakeBanHook} 一致）：
 * {@code gy.i(Flow) -> at9(状态类, 字段 b=Double 结束时间) -> rs8.m(写入) -> nb5.J(发送入口)}。
 */
final class FingerprintReset {

    /** 数美设备指纹的 SharedPreferences 名（逆向：l11l111ll1Il 里的 "bd"）。 */
    private static final String SM_PREF = "bd";

    /** 数美缓存文件（逆向：filesDir/.smtfb）。 */
    private static final String SM_FILE = ".smtfb";

    /** 本模块自己的状态存储。 */
    private static final String PREF = "dekk_fp_reset";

    /** 防抖：同一波处罚只在首次触发（10 分钟内不重复重置）。 */
    private static final long DEBOUNCE_MS = 10 * 60 * 1000L;

    private static ClassLoader hostLoader;

    // ─────────────────────────── 开关 / 统计 ───────────────────────────

    /** 功能开关（默认开）。 */
    static boolean enabled(Context c) {
        return c == null || c.getSharedPreferences(PREF, 0).getBoolean("enabled", true);
    }

    static void setEnabled(Context c, boolean on) {
        if (c == null) return;
        c.getSharedPreferences(PREF, 0).edit().putBoolean("enabled", on).apply();
    }

    /** 是否连「数美环境伪装」一起做（默认开）。 */
    static boolean maskEnabled(Context c) {
        return c == null || c.getSharedPreferences(PREF, 0).getBoolean("mask", true);
    }

    static void setMaskEnabled(Context c, boolean on) {
        if (c == null) return;
        c.getSharedPreferences(PREF, 0).edit().putBoolean("mask", on).apply();
        AntiDetect.setEnabled(on);
    }

    static long lastResetAt(Context c) {
        return c == null ? 0 : c.getSharedPreferences(PREF, 0).getLong("last_at", 0);
    }

    static int resetCount(Context c) {
        return c == null ? 0 : c.getSharedPreferences(PREF, 0).getInt("count", 0);
    }

    static String lastReason(Context c) {
        return c == null ? "" : c.getSharedPreferences(PREF, 0).getString("last_reason", "");
    }

    // ─────────────────────────── 核心：重置 ───────────────────────────

    /**
     * 清理数美本地缓存（{@code filesDir/.smtfb} / sp {@code "bd"} / sp {@code "sm"}）。
     *
     * <p><b>重要(2026-10 实测)</b>：数美真正的设备 ID 是 sp {@code "auc"} 里的 {@code "di"}，
     * 由服务端下发 —— 清空本地缓存后重新初始化，服务端会凭硬件指纹再下发同一个值
     * （实测 puuid/seq/auc 归零，di 一字节未变）。所以本方法
     * <b>不能更换设备指纹</b>，只做本地残留清理。
     *
     * @return true 表示这次真的执行了清理（被开关/防抖挡掉时返回 false）
     */
    static synchronized boolean reset(Context c, String reason) {
        if (c == null) return false;
        if (!enabled(c)) {
            HotBridge.log("[DEKK][指纹] 功能已关闭，跳过重置（" + reason + "）");
            return false;
        }

        long now = System.currentTimeMillis();
        long last = lastResetAt(c);
        if (last > 0 && now - last < DEBOUNCE_MS) {
            HotBridge.log("[DEKK][指纹] 防抖跳过（" + reason + "），距上次 "
                    + ((now - last) / 1000) + "s");
            return false;
        }

        boolean ok = false;
        StringBuilder detail = new StringBuilder();

        // 1) filesDir/.smtfb
        try {
            File f = new File(c.getFilesDir(), SM_FILE);
            if (f.exists()) {
                boolean deleted = f.delete();
                ok |= deleted;
                detail.append("smtfb=").append(deleted ? "已删" : "删失败").append(' ');
            } else {
                detail.append("smtfb=不存在 ");
            }
        } catch (Throwable t) {
            detail.append("smtfb异常=").append(t.getClass().getSimpleName()).append(' ');
        }

        // 2) SharedPreferences "bd"（数美把 deviceId / 上报时间放这里）
        try {
            SharedPreferences sp = c.getSharedPreferences(SM_PREF, 0);
            Map<String, ?> all = sp.getAll();
            if (all != null && !all.isEmpty()) {
                sp.edit().clear().commit();
                ok = true;
                detail.append("sp(bd)=").append(all.size()).append("项已清 ");
            } else {
                detail.append("sp(bd)=空 ");
            }
        } catch (Throwable t) {
            detail.append("sp(bd)异常=").append(t.getClass().getSimpleName()).append(' ');
        }

        // 2.1) 数美在部分版本里还会用 sp "sm"（一并清，避免只清一半）
        try {
            SharedPreferences sp2 = c.getSharedPreferences("sm", 0);
            Map<String, ?> all2 = sp2.getAll();
            if (all2 != null && !all2.isEmpty()) {
                sp2.edit().clear().commit();
                detail.append("sp(sm)=").append(all2.size()).append("项已清 ");
            }
        } catch (Throwable ignored) {
        }

        // 3) 【已移除】原来的 SmAntiFraud.destroy() 调用。
        //    实测证据（2026-10，设备 /data/data/com.deepseek.chat）：
        //      · 设备 ID 存在 sp "auc" 的 "di"，值是 base64(SHA-256) 摘要 + "___"，
        //        由数美服务端下发；删掉后重新初始化，服务端凭硬件指纹下发同一个值；
        //      · 清空 .smtfb / sp "bd" / sp "seq" 后重启：puuid 与 seq/auc 归零，
        //        但 di 一个字节都没变；
        //      · destroy() 会让 SDK 重新 create → 重新采集 + 重新上报，
        //        数美侧看到的是「同一台设备在短期内反复重置」，只会加重风险画像。
        //    结论：这一步既无效又负面，故不再调用。若需要彻底换设备身份，
        //    只能改变硬件指纹组合（ANDROID_ID 等），不属于本类的职责。
        detail.append("destroy=skipped ");

        // 4) 记账
        try {
            SharedPreferences s = c.getSharedPreferences(PREF, 0);
            s.edit()
                    .putLong("last_at", now)
                    .putInt("count", resetCount(c) + 1)
                    .putString("last_reason", String.valueOf(reason))
                    .apply();
        } catch (Throwable ignored) {
        }

        HotBridge.log("[DEKK][指纹] 已清理数美本地缓存（" + reason + "），ok=" + ok
                + "，累计=" + resetCount(c) + "，明细：" + detail
                + "［注意：设备 ID(di) 由服务端下发，本地清理不会改变设备指纹］");
        notifyUser("已清理本地缓存（设备指纹由服务端下发，此操作无法更换设备）");
        return true;
    }

    /** 主线程 Toast 提示（重置成功后提醒用户）。 */
    static void notifyUser(final String text) {
        try {
            final Context ctx = HotApp.currentApplication();
            if (ctx == null) return;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ─────────────────────────── 状态判定 ───────────────────────────

    /**
     * 这个流值是不是「服务端下发的、仍然生效的处罚」？
     *
     * <p>{@code at9} 是 2.5.1 的禁言状态 data class：字段 {@code b} 是结束时间（秒，Double）。
     * null / 非 at9 / 已过期 都返回 false。
     */
    static boolean isActivePenalty(Object status, Class<?> statusClass) {
        if (status == null || statusClass == null || !statusClass.isInstance(status)) return false;
        try {
            Object end = HotHelpers.getObjectField(status, "b");
            if (end instanceof Double) {
                return ((Double) end).doubleValue() * 1000.0 > System.currentTimeMillis();
            }
            if (end instanceof Number) {
                return ((Number) end).doubleValue() * 1000.0 > System.currentTimeMillis();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ─────────────────────────── 安装 ───────────────────────────

    static synchronized void install(ClassLoader loader) {
        hostLoader = loader;

        // 开关落到各子模块。AntiDetect / SignatureMask 继续生效：
        // 实测数美 native 层并不交叉校验这些数据源（196 次 access 里没有 su/magisk 路径），
        // 所以对 Java 侧数据源做伪装是「有效且低风险」的，保留。
        try {
            Context c = HotApp.currentApplication();
            AntiDetect.setEnabled(maskEnabled(c));
            SignatureMask.setEnabled(c == null || c.getSharedPreferences(PREF, 0).getBoolean("sign", true));
        } catch (Throwable ignored) {
        }

        // 【已移除】x01（ChatCompletionResponseMutedJsonData）构造器 hook。
        //
        // 它原来的唯一作用：在解析到「服务端下发禁言」响应时，赶在 UI 之前调用 reset()。
        // 但 reset() 已被实测证明无效（数美设备 ID di 由服务端下发，删本地缓存改不掉），
        // 且 destroy() 会触发重新采集上报、加重风险画像。
        // 既然重置无效，这个 hook 就只剩「多改宿主一个方法的 ArtMethod」这个纯粹的暴露面，
        // 故整体移除。伪封号的展示逻辑仍由 FakeBanHook 的 rs8.m 承担，功能不受影响。

        HotBridge.log("[DEKK][指纹]设备指纹重置已改为手动模式（不再 hook 禁言响应链）");
    }

    private static ClassLoader loader() {
        ClassLoader l = hostLoader;
        return l != null ? l : FingerprintReset.class.getClassLoader();
    }

    private FingerprintReset() { }

    // 供 AntiDetect 复用的列表工具（Java 7 没有 List.of）
    static List<Object> emptyList() {
        return new ArrayList<Object>();
    }
}