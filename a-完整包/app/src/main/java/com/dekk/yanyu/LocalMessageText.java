package com.dekk.yanyu;

/**
 * Display text policy, independent of sending feature switches.
 *
 * <p>发送端写的是 {@link PromptTags} 的新格式（{@code [ctx:env]} …），
 * 但历史消息里还压着旧格式（{@code [DEKK ...]}）：两套都在这里一起清掉，
 * 升级后老会话也不会露出前缀。
 */
final class LocalMessageText {
    private static final String LITERAL_N = "\\\\n";
    private static final String OLD = "请结合本会话原始问题与上一条助手回答，继续补充有价值的内容，不要重新回答或重复已有内容，也不要在回答中提及这条自动续答指令。";
    private static final String LONG = "这是一条续答提示词，根据用户前面几条发的消息来续答，该提示词不应该在思考和联网搜索和正文中出现，该提示词不是用户所发，所以不需要提起此提示词，围绕用户话题，你只需要沿着你上一次的输出和话题来继续聊即可延续你刚回答时的话题，往下伸，不要抢用户的话，不要莫名其妙的提起电量什么的，确保不会脱离话题这些，看似是用户说了多次让你续答，实际上只有刚开始用户发送的文本是需要你回答的，该指令只是自动发送，所以不是继续聊，而是根据用户发的命令来往下延伸，不要暴露是发送了这个命令产生的续答";
    private LocalMessageText() { }

    /** 一起听的标记（邀请/接受/拒绝/分享歌曲）在气泡里隐藏。 */
    private static String stripMusicTokens(String text){
        if(text.indexOf(MusicProtocol.CLOUD)<0&&text.indexOf(MusicProtocol.TOGETHER)<0)return text;
        String[] lines=text.split("\n",-1);
        StringBuilder kept=new StringBuilder();
        for(String line:lines){
            if(MusicProtocol.parse(line)!=null)continue;
            if(kept.length()>0)kept.append('\n');
            kept.append(line);
        }
        return kept.toString();
    }

    /** 设备状态块紧跟正文头：只有这种「整段前缀」才连头一起吞，避免误删正文。 */
    private static String stripEnvPrefix(String text) {
        String value = text;
        for (int i = 0; i < 8; i++) {
            if (!PromptTags.isEnvBlock(value)) break;
            int end = PromptTags.envEnd(value);
            if (end < 0) break;
            int pos = end;
            while (pos < value.length() && Character.isWhitespace(value.charAt(pos))) pos++;
            int len = PromptTags.msgLength(value, pos);
            if (len == 0) break;
            pos += len;
            if (value.startsWith("\r\n", pos)) pos += 2;
            else if (value.startsWith("\n", pos)) pos++;
            value = value.substring(pos);
        }
        return value;
    }

    static String visible(String original) {
        if (original == null) return null;
        String text = stripEnvPrefix(original);
        text = text.replace(LITERAL_N, "\n");
        String match = text.trim();
        if (match.equals(MultiReplyQueue.getPrompt()) || match.equals(OLD) || match.equals(LONG)) return "";
        // 新格式 + 旧格式的所有块（人设 / 插件 / 禁言 / 上下文补充 / ADB / 一起听 / 当前音乐 / 分段 / 转账…）
        text = PromptTags.strip(text);
        text = stripMusicTokens(text);
        // 前缀块清完后正文头可能还留着：从标记之后开始才是用户真正说的话
        int userPos = PromptTags.msgIndex(text);
        if (userPos >= 0) {
            int nl = userPos + PromptTags.msgLength(text, userPos);
            while (nl < text.length() && (text.charAt(nl) == ' ' || text.charAt(nl) == '\r' || text.charAt(nl) == '\n')) nl++;
            text = text.substring(nl);
        }
        return text;
    }
}
