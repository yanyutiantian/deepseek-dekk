package com.dekk.yanyu;

/** Usage snapshot attached to every request while the switch is on. */
final class AdbUsageBlock {
    static final String START = PromptTags.USAGE_START;
    static final String END = PromptTags.USAGE_END;

    private AdbUsageBlock() { }

    static String build(String takenAt, String detail) {
        if (detail == null || detail.trim().length() == 0) return "";
        return START + "\n统计时间：" + takenAt + "\n" + detail.trim() + "\n" + END + "\n";
    }

    static String strip(String text) {
        return PromptTags.stripPairs(text, START, END, PromptTags.OLD_USAGE_START, PromptTags.OLD_USAGE_END);
    }
}