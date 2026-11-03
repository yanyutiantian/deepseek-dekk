package com.dekk.yanyu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息分段（AI 一条回答 → 多个气泡）—— 逐指令移植自
 * {@code ilke/dev/codex/deepseektweaks/ReplySegments.smali}（ilke 目标宿主 DeepSeek 2.5.2）。
 *
 * <p>协议：AI 在回答里写 {@code [SGM1]}、{@code [SGM2]} …（正则 {@code \[SGM[1-9][0-9]{0,8}\]}），
 * 本类把它切成若干 {@link Part}，每个 Part 对应一个气泡。
 *
 * <p>为保证「功能一样」，本类是**直译**：局部变量名沿用原版寄存器语义，
 * 注释里的行号对应 ilke 的 smali 行号，方便逐条比对：
 * <pre>
 *   v4 = i（行游标）      v5 = transformed      v6 = key（段序号）
 *   v7 = segStart        v8 = fence（代码围栏长度）   v9 = fenceChar（围栏字符）
 *   v10 = codeMark（行内代码标记）  v11 = 行尾换行位置  v12 = raw（原始行）
 *   v13 = next（下一行起点）        v14 = contentEnd     v15 = indent
 *   v17 = i 备份         v18 = tabbed         v19/v20 = fence / fenceChar 备份
 * </pre>
 */
final class ReplySegments {

    /** smali &lt;clinit&gt;：Pattern.compile("\\[SGM[1-9][0-9]{0,8}\\]") */
    private static final Pattern MARKER = Pattern.compile("\\[SGM[1-9][0-9]{0,8}\\]");

    /** ReplySegments$Part：key + 文本 + 是否已完整（流式中最后一段为 false）。 */
    static final class Part {

        final int key;
        final String text;
        final boolean complete;

        Part(int key, String text, boolean complete) {
            this.key = key;
            this.text = text;
            this.complete = complete;
        }
    }

    /** ReplySegments$Result：不可变结果 + 是否发生了分段（transformed）。 */
    static final class Result {

        final List<Part> parts;
        final boolean transformed;

        Result(List<Part> parts, boolean transformed) {
            this.parts = Collections.unmodifiableList(parts);
            this.transformed = transformed;
        }

        /** 原版 Result.part(int)：按 key 找段，找不到返回 null。 */
        Part part(int key) {
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).key == key) {
                    return parts.get(i);
                }
            }
            return null;
        }
    }

    private ReplySegments() { }

    /**
     * 原版 add(List,int,String,boolean)：去掉首尾空行后，非空才加一段。
     * （smali 第 45-164 行）
     */
    private static void add(List<Part> parts, int key, String text, boolean complete) {
        int end = text.length();
        int start = 0;
        while (start < end) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) {
                break;
            }
            if (!text.substring(start, nl).trim().isEmpty()) {
                break;
            }
            start = nl + 1;
        }
        while (end > start) {
            char c = text.charAt(end - 1);
            if (c != '\n' && c != '\r') {
                break;
            }
            end--;
        }
        if (start < end && !text.substring(start, end).trim().isEmpty()) {
            parts.add(new Part(key, text.substring(start, end), complete));
        }
    }

    /** 原版 horizontalSpace(C)：空格或制表符（smali 第 165-191 行）。 */
    private static boolean horizontalSpace(char c) {
        return c == ' ' || c == '\t';
    }

    /** 原版 run(String,int,C)：从 from 起同字符连续个数（smali 第 1303-1332 行）。 */
    private static int run(String s, int from, char c) {
        int i = from;
        while (i < s.length() && s.charAt(i) == c) {
            i++;
        }
        return i - from;
    }

    /**
     * 原版 linkSuffix：判断「这个位置开始的是不是链接/特殊协议」，用来决定要不要在该处切段。
     * streaming=true 时会容忍只写了一半的协议头（smali 第 192-388 行）。
     */
    private static boolean linkSuffix(String s, int start, int end, boolean streaming) {
        if (start == end) {
            return false;
        }
        char c = s.charAt(start);
        boolean transfer = s.startsWith("[转账&", start)
                || (streaming && "[转账&".startsWith(s.substring(start, end)));
        boolean sticker = s.startsWith("[表情包#", start)
                || (streaming && "[表情包#".startsWith(s.substring(start, end)));
        String[] music = {"[网易云#", "[一起听#"};
        boolean musicHit = false;
        for (int i = 0; i < music.length; i++) {
            musicHit = musicHit || s.startsWith(music[i], start)
                    || (streaming && music[i].startsWith(s.substring(start, end)));
        }
        if (c == ':' || c == '(') {
            return true;
        }
        if (c == '[') {
            if (s.startsWith("[SGM", start)) {
                return false;
            }
            if (transfer || sticker || musicHit) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * 原版 pendingMarker：流式过程中判断「行尾这截是不是还没写完的 [SGMn]」，
     * 是的话这一行先不切段，等下一帧补齐（smali 第 1152-1302 行）。
     */
    private static boolean pendingMarker(String s) {
        if (s.isEmpty()) {
            return false;
        }
        if ("[SGM".startsWith(s)) {
            return true;
        }
        if (!s.startsWith("[SGM")) {
            return false;
        }
        int i = 4;
        if (i == s.length()) {
            return true;
        }
        char c = s.charAt(i);
        if (c < '1' || c > '9') {
            return false;
        }
        while (i < s.length()) {
            char d = s.charAt(i);
            if (d < '0' || d > '9') {
                break;
            }
            i++;
            if (i > 13) {
                return false;
            }
        }
        if (i == s.length()) {
            return true;
        }
        if (s.charAt(i) == ']' && s.substring(i + 1).trim().isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * 原版 parse(String,boolean)：按行扫描，遇到 {@code [SGMn]} 标记就切段。
     *
     * <p>扫描规则（都从原版直译）：
     * <ul>
     * <li>行尾 <b>标记后面只剩空白</b> 时才算一次切分，且要满足 {@link #linkSuffix}；
     * 流式中行尾可能是没写完的标记（{@link #pendingMarker}）则整行不切。</li>
     * <li>缩进 &gt;=4 或行首是 tab 的内容（tabbed）不参与切分；markdown 引用（{@code >}）也不参与。</li>
     * <li>代码围栏（{@code ```} / {@code ~~~}）内的行整行跳过；
     * 行内代码（{@code `}）用 codeMark 记录，开着的时候不切段。</li>
     * </ul>
     */
    static Result parse(String s, boolean streaming) {
        List<Part> parts = new ArrayList<Part>();
        Matcher marker = MARKER.matcher(s);

        int i = 0;              // v4
        int transformed = 0;    // v5
        int key = 0;            // v6
        int segStart = 0;       // v7
        int fence = 0;          // v8
        char fenceChar = 0;     // v9
        int codeMark = 0;       // v10
        int previousFence = 0;      // v19（行首备份 v8）
        char previousFenceChar = 0; // v20（行首备份 v9）

        while (i < s.length()) {                                    // :goto_1a
            int lineEnd = s.indexOf('\n', i);                       // v11
            int next = lineEnd < 0 ? s.length() : lineEnd + 1;      // v13
            int contentEnd = lineEnd < 0 ? s.length() : lineEnd;    // v14
            if (contentEnd > i && s.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String raw = s.substring(i, contentEnd);                // v12

            int indent = 0;                                         // v15
            while (indent < raw.length() && raw.charAt(indent) == ' ') {
                indent++;
            }
            boolean tabbed;                                         // v18
            if (indent < 4) {
                tabbed = indent < raw.length() && raw.charAt(indent) == '\t';
            } else {
                tabbed = true;
            }
            String content = raw.substring(indent);                 // v4（复用）
            boolean lineFinished = false;

            // .line 64-67：围栏内只判断「这一行是否闭合围栏」，然后整行跳过
            if (fence > 0) {
                int runLen = run(content, 0, fenceChar);            // v14
                if (indent <= 3 && runLen >= fence && content.substring(runLen).trim().isEmpty()) {
                    fence = 0;
                }
                lineFinished = true;
            } else if (!tabbed && !content.startsWith(">")) {
                char c = content.isEmpty() ? 0 : content.charAt(0);  // v3

                // .line 71：行首连续 '`' / '~' 的个数 → v8（同时备份 v19 = 旧 fence）
                previousFence = fence;
                int fenceRun;
                if (c == '`' || c == '~') {
                    fenceRun = run(content, 0, c);
                } else {
                    fenceRun = 0;
                }
                fence = fenceRun;

                // .line 72-75：codeMark 关闭时，≥3 个反引号且行内还有反引号 → 记为围栏开始
                boolean fenceOpened = false;
                if (codeMark == 0 && fence >= 3) {
                    previousFenceChar = fenceChar;
                    if (c == '`') {
                        int close = content.indexOf('`', fence);
                        if (close >= 0) {
                            fenceChar = c;
                            fenceOpened = true;
                        }
                    } else {
                        fenceChar = c;
                        fenceOpened = true;
                    }
                } else {
                    previousFenceChar = fenceChar;
                }
                if (fenceOpened) {
                    lineFinished = true;
                }

                if (!lineFinished) {
                    // .line 77-124：逐字符扫描这一行，找行尾的 [SGMn]
                    int scan = i + indent;                          // v3 = v17 + v15
                    boolean restored = false;
                    while (scan < contentEnd) {                     // :goto_e0
                        char ch = s.charAt(scan);                   // v8（复用）

                        // .line 79-80：反斜杠转义，跳过 2 个字符
                        if (ch == '\\' && codeMark == 0) {
                            scan += 2;
                            continue;
                        }
                        // .line 81-86：行内代码 ` 开/关
                        if (ch == '`') {
                            int runLen = run(s, scan, '`');
                            if (codeMark == runLen) {
                                codeMark = 0;
                            } else if (codeMark == 0) {
                                codeMark = runLen;
                            }
                            scan += runLen;
                            continue;
                        }
                        // .line 87-114：'[' 且不在行内代码里 → 试着匹配标记
                        if (ch == '[' && codeMark == 0) {
                            // .line 86/:cond_111：还没切过段时（transformed == 0），
                            // 只认「行首」的标记 —— 也就是原版的分段写法是
                            //   第一段
                            //   [SGM1]
                            //   第二段
                            //   [SGM2]
                            // 第一个标记必须在行首，之后的标记行内任意位置都行。
                            if (transformed == 0 && scan != i + indent) {
                                scan++;
                                continue;
                            }
                            marker.region(scan, contentEnd);         // .line 88
                            boolean matched = marker.lookingAt();    // .line 89
                            boolean partialTail = streaming && lineEnd < 0;
                            boolean isLink = matched
                                    && linkSuffix(s, marker.end(), contentEnd, partialTail);
                            if (!matched || isLink) {
                                // .line 112-118：行尾是没写完的标记 → 整行不切，直接收尾
                                if (lineEnd < 0 && streaming
                                        && pendingMarker(s.substring(scan, contentEnd))) {
                                    int trimEnd = scan;
                                    while (trimEnd > segStart
                                            && horizontalSpace(s.charAt(trimEnd - 1))) {
                                        trimEnd--;
                                    }
                                    add(parts, key, s.substring(segStart, trimEnd), false);
                                    return new Result(parts, true);
                                }
                                scan++;
                                continue;
                            }
                            // .line 91-99：切段
                            int cut = scan;
                            while (cut > segStart && horizontalSpace(s.charAt(cut - 1))) {
                                cut--;
                            }
                            add(parts, key, s.substring(segStart, cut), true);
                            key++;
                            int after = marker.end();
                            while (after < contentEnd && horizontalSpace(s.charAt(after))) {
                                after++;
                            }
                            segStart = after == contentEnd ? next : after;
                            // .line 100-110：标记后面若紧跟围栏（``` / ~~~），记为围栏开始并收尾
                            if (after < contentEnd) {
                                char afterChar = s.charAt(after);    // v5
                                int afterRun = (afterChar == '`' || afterChar == '~')
                                        ? run(s, after, afterChar) : 0;
                                if (afterRun >= 3) {
                                    if (afterChar == '`') {
                                        int close = s.substring(after + afterRun, contentEnd)
                                                .indexOf('`');
                                        if (close >= 0) {
                                            fence = afterRun;
                                            fenceChar = afterChar;
                                            transformed = 1;
                                            lineFinished = true;
                                            restored = true;
                                        }
                                    } else {
                                        fence = afterRun;
                                        fenceChar = afterChar;
                                        transformed = 1;
                                        lineFinished = true;
                                        restored = true;
                                    }
                                }
                            }
                            if (lineFinished) {
                                break;
                            }
                            transformed = 1;
                            scan++;
                            continue;
                        }
                        scan++;
                    }
                    if (!restored) {
                        fence = previousFence;                       // :cond_1d5
                        fenceChar = previousFenceChar;
                    }
                }
            }

            // :goto_1e2：行结束
            if (raw.trim().isEmpty()) {
                codeMark = 0;                                            // .line 127
            }
            i = next;                                                    // .line 129
        }

        // .line 130-135：收尾
        if (transformed == 0) {
            if (!s.trim().isEmpty()) {
                parts.add(new Part(0, s, !streaming));
            }
        } else {
            add(parts, key, s.substring(segStart), !streaming);
        }
        return new Result(parts, transformed != 0);
    }
}