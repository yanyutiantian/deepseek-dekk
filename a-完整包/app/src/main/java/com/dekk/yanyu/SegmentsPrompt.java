package com.dekk.yanyu;

import android.content.Context;

/**
 * 「回复分段」的提示词 —— 和 {@link AiBanControl} / MusicAiPrompt 同一套机制：
 * 由 {@code DeviceContextHook} 在发送前拼到正文前面，让 AI 按 {@link ReplySegments} 的协议写标记。
 *
 * <p>为什么必须有这一半：ilke 的 smali 里<b>只有解析</b>（{@code [SGM1]…[SGM9]} 正则），
 * 没有任何提示词文本 —— 不让模型输出标记，模块就无从切分（实测：AI 不写标记时完全不生效）。
 *
 * <p>协议要求（与 {@link ReplySegments#parse} 的判定完全对应）：
 * <ul>
 * <li>标记形如 {@code [SGM1]}、{@code [SGM2]}…，从 1 开始递增，最多 9 段；</li>
 * <li><b>标记独占一行</b>，放在该段内容之后（第一个标记也必须在行首，否则不会被识别）；</li>
 * <li>不要放进代码块或行内代码（那两种情况原版明确不切）；</li>
 * <li>不要在思考过程里输出，也不要复述本说明。</li>
 * </ul>
 */
final class SegmentsPrompt {

    static final String START = PromptTags.SEG_START;
    static final String END = PromptTags.SEG_END;

    private SegmentsPrompt() { }

    /** 与原版一致：未开启 / 未开气泡 / 空文本 / 已经带过说明 → 返回空串。 */
    static String prompt(Context context, String text) {
        if (context == null || text == null || text.length() == 0
                || !ConversationSegments.enabled(context)
                || !ChatBubbleHook.enabled(context)     // 分段渲染挂在气泡节点上，没开气泡不会生效
                || text.contains(START)) {
            return "";
        }
        return START + "\n"
                + "用户已开启「回复分段」。请把这条回答拆成若干条短消息，"
                + "每段以独占一行的标记开头：[SGM1]、[SGM2]…（从 1 开始依次递增，最多 9 段）。\n"
                + "硬性要求：标记必须独占一行、写在所属那一段内容的最前面；"
                + "不要放进代码块或行内代码；不要在思考过程里输出；不要复述本说明；"
                + "每段尽量短（1～2 句），像连续发了好几条消息。\n"
                + "示例（两段，标记都在段首）：\n"
                + "[SGM1]\n"
                + "你好呀！\n"
                + "[SGM2]\n"
                + "今天想听点什么歌吗？\n"
                + END;
    }
}