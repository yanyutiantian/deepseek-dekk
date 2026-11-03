package com.dekk.yanyu;

/** RESULT block appended to the next request so the AI can see what the extension returned. */
final class AdbResultBlock {
    static final String START = PromptTags.RES_START;
    static final String END = PromptTags.RES_END;

    private AdbResultBlock() { }

    static String build(String command, String detail) {
        if (detail == null || detail.trim().length() == 0) return "";
        return START + "\n本次指令：" + command + "\n结果：" + detail.trim() + "\n" + END + "\n";
    }

    static String strip(String text) {
        return PromptTags.stripPairs(text, START, END, PromptTags.OLD_RES_START, PromptTags.OLD_RES_END);
    }
}