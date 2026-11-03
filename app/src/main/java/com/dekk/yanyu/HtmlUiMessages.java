package com.dekk.yanyu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 从宿主会话对象读消息（含历史），并支持用正则重新归类。
 *
 * <p>结论全部对着 2.5.1 的 smali 核对过（不是猜的）：
 * <ul>
 * <li>会话 {@code wr.f} 是 {@code LinkedHashMap<Integer, vq>}，<b>key = 消息 id（vq.x()）</b>，
 * 插入顺序就是时间顺序（宿主 UI 也是直接遍历 {@code f.values()}）。</li>
 * <li>装载会话时（{@code wr.z}/{@code wr.A}）会先塞一条<b>哨兵</b>：key = Integer.MIN_VALUE、
 * 值 = 一个空的 {@code nx}（AI 角色）—— 就是它让「第一条消息」变成了空的 AI 消息，
 * 这里按「正文为空就丢弃」过滤掉。</li>
 * <li>角色：{@code vq.D()} 返回角色字符串，用户 = {@code "USER"}，AI = {@code "ASSISTANT"}
 * （{@code nx} 的构造器里默认就是 "ASSISTANT"）。</li>
 * <li>正文：{@code vq.l()} 是宿主统一的取文本入口 ——
 * {@code l() = (D()=="USER") ? r() : C()}；其中 {@code r()} 取分段列表里第一个
 * {@code qt8} 的字段 {@code c}（用户文本），{@code C()} 拼接所有 {@code gg0} 的 {@code j()}
 * （AI 文本）。所以只要用 {@code l()} 就不会像以前那样抓到 DEFAULT/REQUEST 这类枚举。</li>
 * <li>时间 {@code vq.u()}（double）、序号 {@code vq.z()}（Integer，可能为 null）。</li>
 * </ul>
 */
final class HtmlUiMessages {

    /** 一条消息（已按宿主真实顺序排好）。 */
    static final class Msg {
        final String role;    // user / assistant
        final String text;
        final int id;         // vq.x()
        final long seq;       // vq.z()，没有就是 Long.MIN_VALUE
        final double time;    // vq.u()

        Msg(String role, String text) {
            this(role, text, 0, Long.MIN_VALUE, 0d);
        }

        Msg(String role, String text, int id, long seq, double time) {
            this.role = role;
            this.text = text;
            this.id = id;
            this.seq = seq;
            this.time = time;
        }

        boolean isUser() {
            return "user".equals(role);
        }
    }

    private HtmlUiMessages() { }

    /**
     * 会话对象 → 完整消息列表（用户与 AI 都在，顺序 = 宿主存储顺序）。
     */
    static List<Msg> fromSession(Object session) {
        List<Msg> list = new ArrayList<Msg>();
        if (session == null) {
            return list;
        }
        try {
            Object map = HotHelpers.getObjectField(session, "f");
            if (!(map instanceof Map)) {
                return list;
            }
            Set<Integer> seen = new HashSet<Integer>();
            for (Object value : ((Map<?, ?>) map).values()) {
                Msg message = read(value);
                if (message == null) {
                    continue;   // 哨兵 / 空消息 / 占位
                }
                if (message.id != 0 && !seen.add(Integer.valueOf(message.id))) {
                    continue;   // 同一 id 只留一条（宿主更新消息时 key 不变）
                }
                list.add(message);
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    /** 单条消息 → Msg；不是有效消息（空正文、哨兵）返回 null。 */
    static Msg read(Object message) {
        if (message == null) {
            return null;
        }
        String text = textOf(message);
        if (text.trim().length() == 0) {
            return null;
        }
        return new Msg(roleOf(message), text, intOf(message, "x"), seqOf(message), timeOf(message));
    }

    /**
     * 角色：首选 {@code vq.D()}（宿主自己的实现），失败再退回字段 {@code i}，
     * 最后按实现类判断（{@code nx}=AI、{@code px}=用户）。
     */
    static String roleOf(Object message) {
        Object value = call(message, "D");
        if (!(value instanceof String)) {
            value = field(message, "i");
        }
        if (value instanceof String) {
            String role = (String) value;
            if (role.length() > 0) {
                return "ASSISTANT".equalsIgnoreCase(role) ? "assistant" : "user";
            }
        }
        String name = message.getClass().getSimpleName();
        return "nx".equals(name) ? "assistant" : "user";
    }

    /**
     * 正文：{@code vq.l()} 是宿主统一入口，优先用它；
     * 失败再按角色回退到 {@code r()}（用户）/ {@code C()}（AI），最后扫分段列表。
     */
    static String textOf(Object message) {
        if (message == null) {
            return "";
        }
        // 1) 宿主统一入口
        Object unified = call(message, "l");
        if (unified instanceof String && ((String) unified).length() > 0) {
            return (String) unified;
        }
        // 2) 按角色分支
        boolean user = "user".equals(roleOf(message));
        String[] branch = user ? new String[]{"r"} : new String[]{"C"};
        for (int i = 0; i < branch.length; i++) {
            Object value = call(message, branch[i]);
            if (value instanceof String && ((String) value).length() > 0) {
                return (String) value;
            }
        }
        // 3) 直接扫分段列表（s() → qt8.c / gg0.j()）
        String fromParts = textOfParts(call(message, "s"));
        if (fromParts.length() > 0) {
            return fromParts;
        }
        // 4) 兜底：少量字符串方法（G() 有时直接放纯文本）
        String[] fallback = {"G", "d", "t", "k", "A"};
        for (int i = 0; i < fallback.length; i++) {
            Object value = call(message, fallback[i]);
            if (value instanceof String && plausible((String) value)) {
                return (String) value;
            }
        }
        return "";
    }

    /** 分段列表（b41）：用户文本在 qt8.c，AI 文本在 gg0.j()。 */
    private static String textOfParts(Object parts) {
        if (parts == null) {
            return "";
        }
        Iterable<?> items = null;
        if (parts instanceof Iterable) {
            items = (Iterable<?>) parts;
        } else {
            Object list = call(parts, "b");   // b41 内部持有 List，兜底试一下
            if (list instanceof Iterable) {
                items = (Iterable<?>) list;
            }
        }
        if (items == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object part : items) {
            if (part == null) {
                continue;
            }
            Object piece = null;
            String name = part.getClass().getSimpleName();
            if ("qt8".equals(name)) {
                piece = field(part, "c");
            } else if ("gg0".equals(name)) {
                piece = call(part, "j");
            } else {
                piece = field(part, "c");
                if (!(piece instanceof String)) {
                    piece = call(part, "j");
                }
            }
            if (piece instanceof String && ((String) piece).length() > 0) {
                builder.append((String) piece);
            }
        }
        return builder.toString();
    }

    /**
     * 过滤枚举式假正文：DEFAULT / REQUEST / RESPONSE 这种全大写短词是消息的"种类/状态"字段，
     * 不是聊天内容。正常正文要么含小写字母，要么含非 ASCII（中文等），要么足够长。
     */
    private static boolean plausible(String value) {
        String text = value.trim();
        if (text.length() == 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLowerCase(text.charAt(i))) {
                return true;
            }
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return true;
            }
        }
        return text.length() > 24;
    }

    private static int intOf(Object message, String method) {
        Object value = call(message, method);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static long seqOf(Object message) {
        Object value = call(message, "z");
        return value instanceof Number ? ((Number) value).longValue() : Long.MIN_VALUE;
    }

    private static double timeOf(Object message) {
        Object value = call(message, "u");
        return value instanceof Number ? ((Number) value).doubleValue() : 0d;
    }

    private static Object field(Object target, String name) {
        try {
            return HotHelpers.getObjectField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object call(Object target, String name) {
        try {
            return HotHelpers.callMethod(target, name, new Object[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 拼成带标记的纯文本，供正则归类（也方便用户看懂匹配什么）。 */
    static String transcript(List<Msg> messages) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Msg message = messages.get(i);
            boolean user = message.isUser();
            builder.append(user ? "[DEEK-USER]" : "[DEEK-AI]")
                    .append(message.text)
                    .append(user ? "[/DEEK-USER]" : "[/DEEK-AI]")
                    .append('\n');
        }
        return builder.toString();
    }

    /** 用用户给的两个正则从全文里挑出消息，并按出现顺序合并（未命中就返回空）。 */
    static List<Msg> classify(String transcript, String userRegex, String aiRegex) {
        List<Hit> hits = new ArrayList<Hit>();
        collect(transcript, userRegex, "user", hits);
        collect(transcript, aiRegex, "assistant", hits);
        java.util.Collections.sort(hits, new java.util.Comparator<Hit>() {
            public int compare(Hit a, Hit b) { return a.at - b.at; }
        });
        List<Msg> list = new ArrayList<Msg>();
        for (int i = 0; i < hits.size(); i++) {
            list.add(hits.get(i).msg);
        }
        return list;
    }

    private static final class Hit {
        final int at;
        final Msg msg;
        Hit(int at, Msg msg) { this.at = at; this.msg = msg; }
    }

    private static void collect(String text, String pattern, String role, List<Hit> out) {
        if (text == null || text.length() == 0 || pattern == null || pattern.length() == 0) {
            return;
        }
        try {
            Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(text);
            while (matcher.find()) {
                String value = matcher.groupCount() >= 1 && matcher.group(1) != null
                        ? matcher.group(1) : matcher.group(0);
                if (value != null && value.trim().length() > 0) {
                    out.add(new Hit(matcher.start(), new Msg(role, value.trim())));
                }
                if (matcher.end() == matcher.start()) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 消息数组 → JSON（自己转义，不依赖页面里额外的库）。 */
    static String toJson(List<Msg> messages) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            Msg message = messages.get(i);
            builder.append("{\"role\":\"").append(escape(message.role))
                    .append("\",\"text\":\"").append(escape(message.text))
                    .append("\",\"id\":").append(message.id)
                    .append(",\"index\":").append(i)
                    .append("}");
        }
        return builder.append(']').toString();
    }

    /** 一行摘要，用于真机日志核对角色/顺序。 */
    static String summary(List<Msg> messages, int max) {
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(messages.size(), max);
        for (int i = 0; i < limit; i++) {
            Msg message = messages.get(i);
            String text = message.text.replace('\n', ' ');
            if (text.length() > 18) {
                text = text.substring(0, 18) + "…";
            }
            builder.append('#').append(i + 1).append(' ')
                    .append(message.isUser() ? "user" : "ai").append('「').append(text).append('」');
            if (i + 1 < limit) {
                builder.append(' ');
            }
        }
        return builder.toString();
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': builder.append("\\\""); break;
                case '\\': builder.append("\\\\"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        builder.append("\\u").append(String.format("%04x", Integer.valueOf(c)));
                    } else {
                        builder.append(c);
                    }
            }
        }
        return builder.toString();
    }

    /** 预览用示例消息（还没进过聊天页时也能看效果）。 */
    static List<Msg> sample() {
        List<Msg> list = new ArrayList<Msg>();
        list.add(new Msg("assistant", "（开场白）我是你的专属助手，今天想聊点什么？"));
        list.add(new Msg("user", "帮我把这段话改得更客气一点"));
        list.add(new Msg("assistant", "好的，改成这样：\n\n「方便的话，可以帮我看一下这个问题吗？谢谢！」\n\n需要更正式或更随意的版本也可以告诉我。"));
        list.add(new Msg("user", "再短一点"));
        list.add(new Msg("assistant", "「方便帮我看下吗？谢谢！」"));
        return list;
    }
}