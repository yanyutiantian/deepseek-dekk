package com.dekk.yanyu;

/**
 * 通话中的朗读。
 *
 * <p>一开始想走宿主的「自动朗读」开关（MMKV {@code key_auto_tts_enabled}），实测没声音——
 * 那个值只同步到内存里的 StateFlow，真正决定播不播的是别的链路。
 *
 * <p>后来在 {@code fg1}（ChatPageSpeechCapabilityImpl）里找到了宿主「点朗读按钮」
 * 自己走的方法：
 * <pre>
 *   fg1.n(Lvq message, Lwr conversation)   // 日志 "Manual tts request: sessionId=…, messageId=…"
 * </pre>
 * 它内部会：
 * <ol>
 *   <li>用会话 id + 消息 id 构造 {@code oi9}；</li>
 *   <li>从设置里取音色配置 {@code li9}；</li>
 *   <li>建 TTS 会话并开始拉流播放。</li>
 * </ol>
 * 所以通话里只要在 AI 回复完整生成后调一次 {@link #speak}，效果就等于用户手动点了朗读，
 * 音色、语速、格式全都跟宿主设置一致。
 */
final class VoiceCallTts {

    private static Object capability;  // fg1
    private static Object manager;     // bj9

    private VoiceCallTts() { }

    static void install(ClassLoader loader) {
        try {
            HotBridge.hookMethod(HotHelpers.findClass("fg1", loader)
                    .getConstructor(new Class<?>[]{
                            HotHelpers.findClass("d22", loader),
                            HotHelpers.findClass("c31", loader),
                            HotHelpers.findClass("hv3", loader)}), new HotHook() {
                protected void afterHookedMethod(HotParam param) {
                    capability = param.thisObject;
                }
            });
            HotBridge.log("[DEKK][通话] 朗读能力已挂钩（fg1）");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][通话] fg1 挂钩失败: " + t);
        }
        try {
            HotBridge.hookMethod(HotHelpers.findClass("bj9", loader)
                    .getConstructor(new Class<?>[]{
                            HotHelpers.findClass("ig2", loader),
                            HotHelpers.findClass("cg2", loader),
                            HotHelpers.findClass("xh9", loader),
                            HotHelpers.findClass("h02", loader)}), new HotHook() {
                protected void afterHookedMethod(HotParam param) {
                    manager = param.thisObject;
                }
            });
        } catch (Throwable t) {
            HotBridge.log("[DEKK][通话] bj9 挂钩失败: " + t);
        }
    }

    /** 通话开始。 */
    static void begin() {
        HotBridge.log("[DEKK][通话] 通话中朗读已启用（capability=" + (capability != null) + "）");
    }

    /** 通话结束。 */
    static void end() {
    }

    /**
     * 朗读一条 AI 消息（等价于用户点这条消息的朗读按钮）。
     *
     * @return 是否真的把请求发出去了
     */
    static boolean speak(Object message, Object conversation) {
        if (capability == null || message == null || conversation == null) {
            HotBridge.log("[DEKK][通话] 朗读不可用：能力实例还没拿到");
            return false;
        }
        try {
            HotHelpers.callMethod(capability, "n", message, conversation);
            HotBridge.log("[DEKK][通话] 已触发朗读");
            return true;
        } catch (Throwable t) {
            HotBridge.log("[DEKK][通话] 触发朗读失败: " + t);
            return false;
        }
    }

    /** 打断：用户开口时立刻掐掉正在播的那条。 */
    static void interrupt(int messageId) {
        // bj9.b(String cause)：停掉当前活动会话，参数是「原因」不是 id
        try {
            if (manager != null) HotHelpers.callMethod(manager, "b", "call-interrupt");
        } catch (Throwable ignored) { }
        try {
            if (capability != null) HotHelpers.callMethod(capability, "o", String.valueOf(messageId));
        } catch (Throwable ignored) { }
    }
}
