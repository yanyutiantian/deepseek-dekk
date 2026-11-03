package com.dekk.yanyu;

/**
 * 客户端附加内容的统一标签表与清洗器。
 *
 * <p>两套边界并存：
 * <ul>
 *   <li><b>新格式</b>（{@code [ctx:env]} 这类小写标签）—— 之后发出去的请求都用它；</li>
 *   <li><b>旧格式</b>（{@code [DEKK ...]}）—— 历史消息里已经存在，气泡里必须照样隐藏，
 *       否则升级后老会话会看到一大段前缀。</li>
 * </ul>
 *
 * <p>发送端只挑新格式写，显示端（{@link LocalMessageText}）两套一起清。
 */
final class PromptTags {

    // ── 新格式（发送用）──────────────────────────────────────────────
    static final String ENV_START = "[ctx:env]",     ENV_END = "[/ctx:env]";
    static final String PREV_START = "[ctx:prev]",   PREV_END = "[/ctx:prev]";
    static final String ROLE_START = "[ctx:role]",   ROLE_END = "[/ctx:role]";
    static final String SKILL_START = "[ctx:skill]", SKILL_END = "[/ctx:skill]";
    static final String CTL_START = "[ctx:ctl]",     CTL_END = "[/ctx:ctl]";
    static final String CMD_START = "[ctx:cmd]",     CMD_END = "[/ctx:cmd]";
    static final String RES_START = "[ctx:res]",     RES_END = "[/ctx:res]";
    static final String USAGE_START = "[ctx:usage]", USAGE_END = "[/ctx:usage]";
    static final String MUSIC_START = "[ctx:music]", MUSIC_END = "[/ctx:music]";
    static final String NOW_START = "[ctx:now]",     NOW_END = "[/ctx:now]";
    static final String SEG_START = "[ctx:seg]",     SEG_END = "[/ctx:seg]";
    static final String PAY_START = "[ctx:pay]",     PAY_END = "[/ctx:pay]";
    /** 正文前面的「用户消息」头。 */
    static final String MSG = "[ctx:msg]";
    /** 语音通话：AI 主动来电的开场白块。 */
    static final String CALL_START = "[ctx:call]", CALL_END = "[/ctx:call]";
    /** 语音通话：通话中附在用户消息尾部的状态块。 */
    static final String CALL_MODE_START = "[ctx:callmode]", CALL_MODE_END = "[/ctx:callmode]";

    // ── 隐藏控制码（AI 回给客户端看的指令，全部在气泡里抹掉）──────────
    /** AI 禁言：{@code [ban:令牌:分钟]} */
    static final String CMD_BAN = "[ban:";
    /** 本机 ADB：{@code [adb:令牌:...]} */
    static final String CMD_ADB = "[adb:";
    /** 空闲主动互动：{@code [ping:令牌]} */
    static final String CMD_PING = "[ping:";

    // ── 旧格式（历史消息用，只用于隐藏）──────────────────────────────
    static final String OLD_ENV_START = "[DEKK 系统提示词：设备状态（客户端附加上下文，非 system 角色）]";
    static final String OLD_ENV_END = "[/DEKK 设备状态]";
    static final String OLD_PREV_START = "[DEKK 上下文补充（客户端附加，非 system 角色）]";
    static final String OLD_PREV_END = "[/DEKK 上下文补充]";
    static final String OLD_ROLE_START = "[DEKK 人设提示词（普通用户上下文）]";
    static final String OLD_ROLE_END = "[/DEKK 人设提示词]";
    static final String OLD_SKILL_START = "[DEKK 插件提示词（普通用户上下文）]";
    static final String OLD_SKILL_END = "[/DEKK 插件提示词]";
    static final String OLD_CTL_START = "[DEKK AI禁言控制（普通用户上下文）]";
    static final String OLD_CTL_END = "[/DEKK AI禁言控制]";
    static final String OLD_CMD_START = "[DEKK ADB指令（客户端附加，非 system角色）]";
    static final String OLD_CMD_END = "[/DEKK ADB指令]";
    static final String OLD_RES_START = "[DEKK ADB结果（客户端附加，非 system角色）]";
    static final String OLD_RES_END = "[/DEKK ADB结果]";
    static final String OLD_USAGE_START = "[DEKK 应用使用时长（客户端附加，非 system角色）]";
    static final String OLD_USAGE_END = "[/DEKK 应用使用时长]";
    static final String OLD_MUSIC_START = "[DEKK 一起听（客户端附加，非 system角色）]";
    static final String OLD_MUSIC_END = "[/DEKK 一起听]";
    static final String OLD_NOW_START = "[DEKK一起听·当前音乐（客户端自动附加，非 system角色）]";
    static final String OLD_NOW_END = "[/DEKK一起听·当前音乐]";
    static final String OLD_SEG_START = "[DEKK 回复分段]";
    static final String OLD_SEG_END = "[/DEKK 回复分段]";
    static final String OLD_PAY_START = "[DEKK虚拟转账协议（客户端附加上下文，非system角色）]";
    static final String OLD_PAY_END = "[/DEKK虚拟转账协议]";
    /** 旧正文头（注意：新格式是 {@link #MSG}）。 */
    static final String OLD_MSG = "[用户消息]";

    static final String OLD_CMD_BAN = "[DEKK_AI_BAN:";
    static final String OLD_CMD_ADB = "[DEKK_ADB:";
    static final String OLD_CMD_PING = "[DEKK主动互动:";

    private static final int MAX_ROUNDS = 8;

    /** 新 + 旧，成对出现。 */
    private static final String[][] PAIRS = {
            {ENV_START, ENV_END}, {OLD_ENV_START, OLD_ENV_END},
            {PREV_START, PREV_END}, {OLD_PREV_START, OLD_PREV_END},
            {ROLE_START, ROLE_END}, {OLD_ROLE_START, OLD_ROLE_END},
            {SKILL_START, SKILL_END}, {OLD_SKILL_START, OLD_SKILL_END},
            {CTL_START, CTL_END}, {OLD_CTL_START, OLD_CTL_END},
            {CMD_START, CMD_END}, {OLD_CMD_START, OLD_CMD_END},
            {RES_START, RES_END}, {OLD_RES_START, OLD_RES_END},
            {USAGE_START, USAGE_END}, {OLD_USAGE_START, OLD_USAGE_END},
            {MUSIC_START, MUSIC_END}, {OLD_MUSIC_START, OLD_MUSIC_END},
            {NOW_START, NOW_END}, {OLD_NOW_START, OLD_NOW_END},
            {SEG_START, SEG_END}, {OLD_SEG_START, OLD_SEG_END},
            {PAY_START, PAY_END}, {OLD_PAY_START, OLD_PAY_END},
            {CALL_START, CALL_END}, {"[call]", "[/call]"},
            {CALL_MODE_START, CALL_MODE_END},
    };

    private PromptTags() { }

    /** 正文里已经带过这个块了（避免重试时叠第二层）。 */
    static boolean has(String text, String start) {
        return text != null && start != null && text.indexOf(start) >= 0;
    }

    /** 任一格式出现过就算有。 */
    static boolean hasAny(String text, String start, String oldStart) {
        return has(text, start) || has(text, oldStart);
    }

    /** 删掉一对边界里的整块（含两端），连带尾随空白。 */
    static String stripPair(String text, String start, String end) {
        if (text == null || start == null || end == null || text.indexOf(start) < 0) return text;
        String value = text;
        for (int i = 0; i < MAX_ROUNDS; i++) {
            int open = value.indexOf(start);
            if (open < 0) break;
            int close = value.indexOf(end, open + start.length());
            if (close < 0) break;
            int pos = close + end.length();
            while (pos < value.length() && Character.isWhitespace(value.charAt(pos))) pos++;
            value = value.substring(0, open) + value.substring(pos);
        }
        return value;
    }

    /** 新 + 旧两种格式都清掉（同一块可能既出现过新版也出现过旧版）。 */
    static String stripPairs(String text, String start, String end, String oldStart, String oldEnd) {
        return stripPair(stripPair(text, start, end), oldStart, oldEnd);
    }

    /** 已知块全部清掉。 */
    static String strip(String text) {
        if (text == null) return null;
        String value = text;
        for (int i = 0; i < PAIRS.length; i++) {
            value = stripPair(value, PAIRS[i][0], PAIRS[i][1]);
        }
        return value;
    }

    // ── 设备状态块 + 正文头 ─────────────────────────────────────────

    /** 以设备状态块开头（新或旧）。 */
    static boolean isEnvBlock(String text) {
        if (text == null) return false;
        return text.startsWith(ENV_START) || text.startsWith(OLD_ENV_START);
    }

    /** 正文头标记出现的位置（新格式优先），没有返回 -1。 */
    static int msgIndex(String text) {
        if (text == null) return -1;
        int fresh = text.indexOf(MSG);
        int legacy = text.indexOf(OLD_MSG);
        if (fresh < 0) return legacy;
        if (legacy < 0) return fresh;
        return Math.min(fresh, legacy);
    }

    /** text 在 index 处那个正文头的实际长度。 */
    static int msgLength(String text, int index) {
        if (text == null || index < 0) return 0;
        if (text.startsWith(MSG, index)) return MSG.length();
        if (text.startsWith(OLD_MSG, index)) return OLD_MSG.length();
        return 0;
    }

    /** 设备状态块结束标记之后的位置（新或旧），没有返回 -1。 */
    static int envEnd(String text) {
        if (text == null) return -1;
        int fresh = text.indexOf(ENV_END);
        int legacy = text.indexOf(OLD_ENV_END);
        if (fresh < 0 && legacy < 0) return -1;
        int bestStart = fresh < 0 ? legacy : (legacy < 0 ? fresh : Math.min(fresh, legacy));
        int len = bestStart == fresh ? ENV_END.length() : OLD_ENV_END.length();
        return bestStart + len;
    }
}
