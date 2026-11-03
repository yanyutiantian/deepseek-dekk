package com.dekk.yanyu;


/**
 * 模块真正的功能入口。
 *
 * <p>两种跑法共用这里，保证行为一致：
 * <ul>
 *   <li>整包安装（老方式）：{@link HookInit} 直接调用；</li>
 *   <li>热更新壳：壳 APK 下载本模块打出来的 classes.dex，用 DexClassLoader 装进来，
 *       再反射调用 {@code DekkEntry.install(宿主的 ClassLoader)}。</li>
 * </ul>
 *
 * <p>每个 hook 单独 try/catch：某一个装不上只影响它自己，其余照常工作，
 * 日志里能直接看出是哪一个、失败原因是什么（整包安装时也能享受这个好处）。
 */
public final class DekkEntry {

    /** payload 版本标记，日志里能直接看出跑的是哪一版。 */
    public static final String VERSION = "2026-10-30-verifyfix";

    /** 单步安装的接口（Java 7，不用 lambda）。 */
    private interface Step {
        void install();
    }

    private static ClassLoader host;

    /** 热更新壳反射调用的入口：hostLoader 是宿主的 ClassLoader。 */
    public static void install(ClassLoader hostLoader) {
        host = hostLoader;
        HotBridge.log("[DEKK][热更新] payload 启动，版本 " + VERSION);

        step("FakeBanHook", new Step() { public void install() { FakeBanHook.install(host); } });
        step("AiBanControl", new Step() { public void install() { AiBanControl.install(host); } });
        step("ExpertModeHook", new Step() { public void install() { ExpertModeHook.install(host); } });
        step("VisionModeHook", new Step() { public void install() { VisionModeHook.install(host); } });
        step("AntiRecallHook", new Step() { public void install() { AntiRecallHook.install(host); } });
        step("ContextSummaryHook", new Step() { public void install() { ContextSummaryHook.install(host); } });
        step("AdbAiHook", new Step() { public void install() { AdbAiHook.install(host); } });
        step("AdbCleartextHook", new Step() { public void install() { AdbCleartextHook.install(); } });
        step("AdbBridge.probeAsync", new Step() { public void install() { AdbBridge.probeAsync(); } });
        step("MusicHook", new Step() { public void install() { MusicHook.install(host); } });
        step("TransferHook", new Step() { public void install() { TransferHook.install(host); } });
        step("DekkSettingsPage", new Step() { public void install() { DekkSettingsPage.install(host); } });
        step("DeepSeekSettingsHook", new Step() { public void install() { DeepSeekSettingsHook.install(host); } });
        step("DeepSeekSelectionHook", new Step() { public void install() { DeepSeekSelectionHook.install(host); } });
        // 强开登录（赞助限定）：邮箱登录解锁 —— 2.5.1 的 k95#c 地区能力接口 d() 改 false
        step("EmailLoginHook", new Step() { public void install() { EmailLoginHook.install(host); } });
        step("DeviceContextHook", new Step() { public void install() { DeviceContextHook.install(host); } });
        step("ChatBackgroundHook", new Step() { public void install() { ChatBackgroundHook.install(host); } });
        step("ChatBubbleHook", new Step() { public void install() { ChatBubbleHook.install(host); } });
        step("ChatAvatarHook", new Step() { public void install() { ChatAvatarHook.install(host); } });
        step("ChatAvatarStore.preload", new Step() { public void install() { ChatAvatarStore.preload(); } });
        // 语音通话：整块功能已关闭（VoiceCallStore.FEATURE_ON=false）—— 不装钩子，相机入口不动
        if (VoiceCallStore.FEATURE_ON) {
            step("VoiceCallHook", new Step() { public void install() { VoiceCallHook.install(host); } });
        }
        step("MultiReplyStateHook", new Step() { public void install() { MultiReplyStateHook.install(host); } });
        step("HtmlUiHook", new Step() { public void install() { HtmlUiHook.install(host); } });
        // 整页自定义：全屏覆盖层（纯 View，不碰 Compose → 不会崩、不吞消息）
        step("HtmlUiFull", new Step() { public void install() { HtmlUiFull.install(); } });
        // 消息分段：AI 一条回答拆成多个气泡（照 ilke 原版移植，见 tools/notes_ilke_port.md）
        step("ConversationTransfers", new Step() { public void install() { ConversationTransfers.install(host); } });
        step("ConversationSegments", new Step() { public void install() { ConversationSegments.install(host); } });
        step("ProactiveMessages", new Step() { public void install() { ProactiveMessages.install(); } });
        // 聊天记录迁移：真附件上传桥梁 + 原生消息注入（2.5.1）
        step("AttachmentBridge", new Step() { public void install() { AttachmentBridge.install(host); } });
        step("ConversationInjector", new Step() { public void install() { ConversationInjector.install(host); } });
        // 风控对抗（2.5.1 逆向）：
        //   ① AntiDetect      —— 伪装环境数据源（Xposed/Magisk/su/adb/debug/代理/包探测）
        //   ② SignatureMask   —— 签名伪装成官方签名（微信登录必需）
        //   ③ FingerprintReset —— 服务端下发禁言时重置数美设备指纹（避免小号被连坐）
        step("AntiDetect", new Step() { public void install() { AntiDetect.install(host); } });
        step("SignatureMask", new Step() { public void install() { SignatureMask.install(host); } });
        step("FingerprintReset", new Step() { public void install() { FingerprintReset.install(host); } });
        // 数美过检测（用户提供的 com.dsmod.smsbypass 那套，整体移植）：
        // Java 层三块 + native 层改 libsmsdk.so 代码段。详见 ShumeiBypass / native/dkcore.c
        step("ShumeiBypass", new Step() { public void install() { ShumeiBypass.install(host); } });

        HotBridge.log("[DEKK][热更新] payload 全部安装完成（失败项见上面的日志）");
    }

    /** 装一个 hook：失败了只记日志，不影响后面的。 */
    private static void step(String name, Step step) {
        try {
            step.install();
        } catch (Throwable t) {
            HotBridge.log("[DEKK][热更新] " + name + " 安装失败：" + t);
            try {
                HotBridge.log(t);
            } catch (Throwable ignored) { }
        }
    }

    /** 模块自己进程里用它把「已激活」显示成 true（壳和整包都叫 MainActivity，用字符串避免类重复加载）。 */
    public static void installSelf(ClassLoader self) {
        HotHelpers.findAndHookMethod(
                "com.dekk.yanyu.MainActivity",
                self,
                "isModuleActivated",
                HotMethodReplacement.returnConstant(true));
    }

    private DekkEntry() { }
}
