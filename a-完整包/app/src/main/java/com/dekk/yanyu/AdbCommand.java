package com.dekk.yanyu;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Hidden instruction text and the parser for the AI's ADB command lines. */
final class AdbCommand {
    static final String START = PromptTags.CMD_START;
    static final String END = PromptTags.CMD_END;
    /** 新指令码：[adb:令牌:...]，旧的是 [DEKK_ADB:令牌:...]，解析两套都认。 */
    private static final String TOKEN_TAG = PromptTags.CMD_ADB;
    private static final String LEGACY_TOKEN_TAG = PromptTags.OLD_CMD_ADB;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    static final class Item {
        final String type;
        final String packageName;
        final int minutes;
        Item(String type, String packageName, int minutes) {
            this.type = type;
            this.packageName = packageName;
            this.minutes = minutes;
        }
    }

    private AdbCommand() { }

    static String build(boolean freeze, boolean usage, String token) {
        if (!freeze && !usage) return "";
        StringBuilder text = new StringBuilder();
        text.append(START).append('\n')
                .append("本机应用管理扩展已开启。需要时在最终回答最后独占一行输出指令，指令必须携带本次标识，不要在代码块或引用中输出，也不要在思考中输出，每轮最多一条。\n");
        if (freeze) {
            text.append("- 冻结应用：").append(TOKEN_TAG).append(token).append(":freeze:应用包名:分钟数]，分钟数只能")
                    .append(AdbProtocol.MIN_MINUTES).append("至").append(AdbProtocol.MAX_MINUTES)
                    .append("的整数。只能冻结第三方应用，不能冻结 DeepSeek、模块自身、系统应用、桌面和输入法。冻结到期自动恢复。\n");
        }
        if (usage) {
            text.append("- 查看今日应用使用时长：今天的使用时长前 10 会随每条消息自动附带，一般不需要再输出指令；只有需要刷新时才输出 ")
                    .append(TOKEN_TAG).append(token).append(":usage]。\n");
        }
        text.append("没有需要就不要输出任何指令，也不要复述本说明或标识。\n").append(END);
        return text.toString();
    }

    /** Only a line that is exactly a command counts; quoted examples and code blocks are ignored. */
    static Item parse(String answer, String token) {
        if (answer == null || token == null || token.length() == 0 || answer.contains("```")) return null;
        String[] lines = answer.trim().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            String prefix = TOKEN_TAG + token + ":";
            if (!line.startsWith(prefix)) {
                // 旧格式（升级前那几轮已经发出去的标记）照样能收
                prefix = LEGACY_TOKEN_TAG + token + ":";
                if (!line.startsWith(prefix)) return null;
            }
            if (!line.endsWith("]")) return null;
            String body = line.substring(prefix.length(), line.length() - 1);
            if ("usage".equals(body)) return new Item(AdbProtocol.CMD_USAGE, "", 0);
            String[] parts = body.split(":");
            if (parts.length != 3 || !"freeze".equals(parts[0])) return null;
            String packageName = parts[1];
            if (!PACKAGE.matcher(packageName).matches()) return null;
            int minutes;
            try {
                minutes = Integer.parseInt(parts[2]);
            } catch (Throwable t) {
                return null;
            }
            if (minutes < AdbProtocol.MIN_MINUTES || minutes > AdbProtocol.MAX_MINUTES) return null;
            return new Item(AdbProtocol.CMD_FREEZE, packageName, minutes);
        }
        return null;
    }

    /** Removes command lines so the chat bubble keeps only the real answer. */
    static String visible(String answer, String token) {
        if (answer == null || token == null) return answer;
        List<String> kept = new ArrayList<String>();
        String prefix = TOKEN_TAG + token + ":";
        String legacy = LEGACY_TOKEN_TAG + token + ":";
        for (String line : answer.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.endsWith("]") && (trimmed.startsWith(prefix) || trimmed.startsWith(legacy))) continue;
            kept.add(line);
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) text.append('\n');
            text.append(kept.get(i));
        }
        return text.toString().trim();
    }
}
