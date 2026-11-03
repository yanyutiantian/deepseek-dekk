package com.dekk.yanyu;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 正文的「原文」缓存。
 *
 * <p>气泡里那一块 {@code [ctx:call]} 标记要藏起来，所以 {@code nx.C()} 被 hook 成返回清洗过的文本；
 * 但通话状态机还要靠原文里的标记判断「AI 想打电话」，于是把清洗前的原文按消息对象记一份。
 * 用 LRU 只留最近 64 条，避免会话开久了内存涨。
 */
final class VoiceCallText {

    private static final int MAX = 64;

    private static final LinkedHashMap<Object, String> RAW =
            new LinkedHashMap<Object, String>(16, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, String> eldest) {
                    return size() > MAX;
                }
            };

    private VoiceCallText() { }

    static synchronized void remember(Object message, String raw) {
        if (message != null && raw != null) RAW.put(message, raw);
    }

    static synchronized String raw(Object message) {
        return message == null ? null : RAW.get(message);
    }

    static synchronized void clear() {
        RAW.clear();
    }

    /** 去掉通话标记（显示用）。 */
    static String strip(String text) {
        if (text == null) return null;
        if (text.indexOf("[ctx:call]") < 0 && text.indexOf("[call]") < 0) return text;
        String value = PromptTags.stripPair(text, VoiceCallProtocol.START, VoiceCallProtocol.END);
        return PromptTags.stripPair(value, VoiceCallProtocol.OLD_START, VoiceCallProtocol.OLD_END);
    }
}
