package com.dekk.yanyu;

/**
 * 多答自动用户消息判定。仅匹配完整固定指令，避免误伤真实用户消息。
 * 渲染层适配时应调用 shouldHide(Object)；不要在请求层删除消息。
 */
public final class MultiReplyMessageFilter {
    private MultiReplyMessageFilter() { }

    public static boolean shouldHide(Object value) {
        if (value == null) return false;
        if (value instanceof CharSequence) {
            return MultiReplyQueue.getPrompt().contentEquals((CharSequence) value);
        }
        String text = readString(value, "content");
        if (text == null) text = readString(value, "text");
        if (text == null) text = readString(value, "prompt");
        return text != null && MultiReplyQueue.getPrompt().equals(text);
    }

    private static String readString(Object value, String field) {
        try {
            java.lang.reflect.Field f = value.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object result = f.get(value);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
