package com.dekk.yanyu;

/** Pure text rules for the supplement block. Display stripping is local only. */
final class ContextSummaryBlock {
    static final String START = PromptTags.PREV_START;
    static final String END = PromptTags.PREV_END;
    private static final String QUESTION = "上一轮提问：";
    private static final String NOTES = "已确认要点：";
    private ContextSummaryBlock() { }

    /** Block only, or empty text when there is nothing to carry. */
    static String block(String previousQuestion, String notes) {
        String p = previousQuestion == null ? "" : previousQuestion.trim();
        String n = notes == null ? "" : notes.trim();
        if (p.length() == 0 && n.length() == 0) return "";
        StringBuilder text = new StringBuilder();
        text.append(START).append('\n');
        if (p.length() > 0) text.append(QUESTION).append(p).append('\n');
        if (n.length() > 0) text.append(NOTES).append('\n').append(n).append('\n');
        text.append(END).append('\n');
        return text.toString();
    }

    /** Only the user's own text is carried: previous question and user-authored notes. */
    static String compose(String previousQuestion, String notes, String question) {
        String block = block(previousQuestion, notes);
        return block.length() == 0 ? question : block + question;
    }

    /** Local bubble display: hide the block (new and legacy format), keep the real message. */
    static String strip(String text) {
        return PromptTags.stripPairs(text, START, END, PromptTags.OLD_PREV_START, PromptTags.OLD_PREV_END);
    }
}