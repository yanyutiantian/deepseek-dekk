package com.dekk.yanyu;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 请求级文本上下文：在发送序列化那一刻把要隐藏的提示词拼到正文前面。
 *
 * <p><b>2026-10-30 加固</b>：以前整段拼装共用一个 try/catch —— 任何一块（禁言/分段/一起听/
 * 通话/设备状态…）抛异常，都会走 catch 把 {@code args[2]} 还原成原文，
 * 等于**这一条消息所有提示词全丢**，而且日志只有一句"读取失败"。现在改成：
 * <ul>
 *   <li>每一块单独 try/catch（{@link #appendBlock}），炸了只跳这一块，其余照常带上；</li>
 *   <li>外层 catch 只作为最后兜底，并且会打出完整异常，方便定位到底是哪块；</li>
 *   <li>拼装过程中不再提前 return（除了参数不匹配），保证该带的都带上。</li>
 * </ul>
 */
public final class DeviceContextHook {

    private static final String KEY = "device_context_enabled";

    /** 本进程是否已经打过"提示词已附加"的日志（每条消息都打会刷屏）。 */
    private static boolean loggedAttached;

    /** 单块提示词构造器。 */
    private interface Block {
        String build();
    }

    private DeviceContextHook() { }

    public static boolean enabled(Context context) {
        return context.getSharedPreferences("dekk_prefs", Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    public static void setEnabled(Context context, boolean value) {
        context.getSharedPreferences("dekk_prefs", Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply();
    }

    public static void install(ClassLoader loader) {
        try {
            final Object completion = HotHelpers.getStaticObjectField(HotHelpers.findClass("c41", loader), "descriptor");
            final Object edit = HotHelpers.getStaticObjectField(HotHelpers.findClass("d31", loader), "descriptor");
            HotHelpers.findAndHookMethod(HotHelpers.findClass("pn9", loader), "N",
                    HotHelpers.findClass("s78", loader), int.class, String.class, new HotHook() {
                @Override
                protected void beforeHookedMethod(HotParam param) {
                    // 【关键】先把真实参数数组抓在本地，之后一律只认这个数组。
                    // 原因：hook 内部会调用 File.exists / SharedPreferences / Class.forName 之类，
                    // 而这些都被数美那套勾着；壳里的 HotParam 是复用的（ThreadLocal），
                    // 一旦嵌套，param.args 会被换成内层调用的数组（长度可能只有 2），
                    // 之后再写 param.args[2] 就抛 AIOOBE，Xposed 会把整次修改丢弃 ——
                    // 现象就是"提示词拼好了但没带上"（2026-10-30 日志实证）。
                    final Object[] args = param.args;
                    if (args == null || args.length != 3) return;
                    final Object original = args[2];
                    try {
                        // 仅观察并处理明确的三参数字符串序列化调用，任何不匹配均原样放行。
                        if (args[0] != completion && args[0] != edit) return;
                        if (!(args[1] instanceof Integer)
                                || ((Integer) args[1]).intValue() != 2) return;
                        if (!(original instanceof String)) return;
                        final String text = (String) original;
                        if (text.trim().length() == 0 || PromptTags.isEnvBlock(text)) return;
                        final Context context = HotApp.currentApplication();
                        if (context == null) return;

                        final boolean eligible = args[0] == completion
                                && !text.equals(MultiReplyQueue.getPrompt());
                        final StringBuilder prefix = new StringBuilder();

                        // ── 逐块附加：谁炸只跳谁 ──────────────────────────
                        if (eligible) appendBlock(prefix, "AI禁言", new Block() {
                            public String build() { return AiBanControl.prompt(context, text); }
                        });
                        if (eligible) appendBlock(prefix, "上下文补充", new Block() {
                            public String build() { return ContextSummaryHook.blockFor(context); }
                        });
                        if (eligible) appendBlock(prefix, "ADB指令", new Block() {
                            public String build() { return AdbAiHook.prompt(context, text); }
                        });
                        if (eligible) appendBlock(prefix, "一起听", new Block() {
                            public String build() { return MusicAiPrompt.prompt(context, text); }
                        });
                        if (eligible) appendBlock(prefix, "回复分段", new Block() {
                            public String build() { return SegmentsPrompt.prompt(context, text); }
                        });
                        // 语音通话：整块功能已关闭（见 VoiceCallStore.FEATURE_ON），这里不再携带任何提示词
                        if (VoiceCallStore.FEATURE_ON && eligible) appendBlock(prefix, "语音通话", new Block() {
                            public String build() { return VoiceCallManager.promptBlock(context); }
                        });
                        if (eligible) appendBlock(prefix, "当前音乐", new Block() {
                            public String build() { return MusicNowPrompt.block(context); }
                        });
                        // 普通发送、编辑重发和续答均携带转账协议；仅修改序列化参数。
                        if (!PromptTags.hasAny(text, PromptTags.PAY_START, PromptTags.OLD_PAY_START)) {
                            appendBlock(prefix, "虚拟转账", new Block() {
                                public String build() { return TransferHook.promptBlock(context); }
                            });
                        }
                        if (eligible) appendBlock(prefix, "ADB结果", new Block() {
                            public String build() { return AdbControlStore.takeResultBlock(context); }
                        });
                        if (eligible) appendBlock(prefix, "应用时长", new Block() {
                            public String build() { return AdbControl.usageBlock(context); }
                        });
                        if (enabled(context)) appendBlock(prefix, "设备状态", new Block() {
                            public String build() { return snapshot(context); }
                        });

                        // 人设 / 插件：只在刚点「使用」后新建会话的首条消息上带一次
                        final String persona = eligible ? pending(context, true) : "";
                        final String plugin = eligible ? pending(context, false) : "";
                        if (persona.length() > 0) appendBlock(prefix, "人设", new Block() {
                            public String build() {
                                return PromptTags.ROLE_START + "\n" + persona + "\n" + PromptTags.ROLE_END;
                            }
                        });
                        if (plugin.length() > 0) appendBlock(prefix, "插件", new Block() {
                            public String build() {
                                return PromptTags.SKILL_START + "\n" + plugin + "\n" + PromptTags.SKILL_END;
                            }
                        });

                        if (prefix.length() > 0 && !loggedAttached) {
                            loggedAttached = true;
                            HotBridge.log("[DEKK] 提示词已附加：" + prefix.length() + " 字符");
                        }
                        args[2] = prefix.length() == 0 ? text
                                : prefix.append(PromptTags.MSG).append('\n').append(text).toString();
                        if (eligible) {
                            try { PersonaSessionStore.consume(context); } catch (Throwable ignored) { }
                            try { PluginSessionStore.consume(context); } catch (Throwable ignored) { }
                        }
                    } catch (Throwable t) {
                        // 兜底也写回原数组；绝不让异常抛出 hook（抛出=Xposed 丢弃修改）
                        if (original instanceof String) args[2] = original;
                        HotBridge.log("[DEKK] 提示词附加失败（本次按原文发送）：" + t);
                    }
                }
            });
            HotBridge.log("[DEKK] 设备状态请求注入已安装（普通发送/编辑消息）");
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 提示词注入 Hook 安装失败（宿主版本可能不匹配）：" + t);
            HotBridge.log(t);
        }
    }

    /** 拼一块：返回空串就跳过，抛异常就只丢这一块。 */
    private static void appendBlock(StringBuilder prefix, String name, Block block) {
        try {
            String value = block.build();
            if (value == null || value.length() == 0) return;
            prefix.append(value).append("\n\n");
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 提示词块[" + name + "]构造失败，已跳过：" + t);
        }
    }

    /** 待注入的人设 / 插件正文；读失败就当没有，别影响其它提示词。 */
    private static String pending(Context context, boolean persona) {
        try {
            return persona ? PersonaSessionStore.pending(context) : PluginSessionStore.pending(context);
        } catch (Throwable t) {
            HotBridge.log("[DEKK] " + (persona ? "人设" : "插件") + "待注入内容读取失败：" + t);
            return "";
        }
    }

    /** 设备快照（原来的 build，改名避免和 Block#build 撞名）。 */
    private static String snapshot(Context context) {
        TimeZone zone = TimeZone.getDefault();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.US);
        format.setTimeZone(zone);
        String battery = "未知";
        String charging = "未知";
        Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (status != null) {
            int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) battery = Math.min(100, Math.round(level * 100f / scale)) + "%";
            int state = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (state == BatteryManager.BATTERY_STATUS_FULL) charging = "已充满";
            else if (state == BatteryManager.BATTERY_STATUS_CHARGING) charging = "正在充电";
            else if (state == BatteryManager.BATTERY_STATUS_DISCHARGING || state == BatteryManager.BATTERY_STATUS_NOT_CHARGING) charging = "未充电";
        }
        return PromptTags.ENV_START + "\n以下信息为本次请求时的设备快照，仅在问题相关时参考，不代表持续实时监测。"
                + "\n本地时间：" + format.format(new Date()) + "\n时区：" + zone.getID()
                + "\n电量：" + battery + "\n充电状态：" + charging + "\n" + PromptTags.ENV_END;
    }
}
