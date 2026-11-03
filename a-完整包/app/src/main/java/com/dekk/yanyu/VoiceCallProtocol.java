package com.dekk.yanyu;

/**
 * 语音通话的隐藏标记与提示词。
 *
 * <p>协议沿用 2026-10-27 定的新格式（{@code [ctx:*]} + 成对结尾），
 * 只新增一个 {@code call} 块：
 * <pre>
 *   [ctx:call]我这边有点事想跟你说[/ctx:call]
 * </pre>
 * 块里的内容是 AI 想说的开场白，块本身在气泡里隐藏（{@link PromptTags#strip} 负责）。
 *
 * <p>同时兼容老写法 {@code [call]…[/call]}，方便用户自己写人设提示词。
 */
final class VoiceCallProtocol {

    static final String START = "[ctx:call]";
    static final String END = "[/ctx:call]";
    static final String OLD_START = "[call]";
    static final String OLD_END = "[/call]";

    /** 通话中随用户消息附带的控制块，告诉模型「现在处于通话里」。 */
    static final String CTL_START = "[ctx:callmode]";
    static final String CTL_END = "[/ctx:callmode]";

    private VoiceCallProtocol() { }

    /** AI 这条回复是否请求了通话。 */
    static boolean requestsCall(String text) {
        if (text == null) return false;
        return text.indexOf(START) >= 0 || text.indexOf(OLD_START) >= 0;
    }

    /** 取出 AI 写在标记里的开场白；没有则返回空串。 */
    static String opening(String text) {
        if (text == null) return "";
        String value = between(text, START, END);
        if (value.length() == 0) value = between(text, OLD_START, OLD_END);
        return value.trim();
    }

    private static String between(String text, String start, String end) {
        int open = text.indexOf(start);
        if (open < 0) return "";
        int close = text.indexOf(end, open + start.length());
        if (close < 0) return "";
        return text.substring(open + start.length(), close);
    }

    /** 通话中发给模型的状态说明（作为隐藏控制块，挂在用户消息尾部）。 */
    static String modePrompt() {
        return CTL_START + "\n现在是实时语音通话中：用户用麦克风说话，你说的每句话都会被朗读出来。"
                + "请像打电话一样简短、口语化，一次一两句，不要用列表和 Markdown。\n" + CTL_END;
    }

    /** 让模型主动发起通话的说明（挂在用户消息尾部，频率由调用方控制）。 */
    static String invitePrompt() {
        return "[ctx:ctl]\n如果你想主动给用户打个电话（比如有重要的事、想聊天），"
                + "在回复最后写一块 " + START + "开场白" + END + "，客户端会弹出通话邀请，"
                + "这段标记不会显示在聊天里。不想打就别写。\n[/ctx:ctl]";
    }
}
