package com.dekk.yanyu;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ilke 同款一起听协议，直接用聊天消息当通道：
 * [网易云#一起听] 发起邀请、[一起听#接受] / [一起听#拒绝] 回应、
 * [网易云#歌曲ID#标题] 分享歌曲（标题里的 # 会被拒绝，避免歧义）。
 */
final class MusicProtocol {
    static final int INVITE=1;
    static final int SONG=2;
    static final int ACCEPT=3;
    static final int REFUSE=4;
    static final String CLOUD="[网易云#";
    static final String TOGETHER="[一起听#";
    static final String INVITE_TEXT="[网易云#一起听]";
    static final String ACCEPT_TEXT="[一起听#接受]";
    static final String REFUSE_TEXT="[一起听#拒绝]";
    private static final int MAX_TITLE=120;

    static final class Token {
        final int kind;
        final String songId;
        final String title;
        Token(int kind,String songId,String title){this.kind=kind;this.songId=songId;this.title=title;}

        /** 扫描时用来去重。 */
        @Override
        public boolean equals(Object other){
            if(!(other instanceof Token))return false;
            Token token=(Token)other;
            return kind==token.kind&&songId.equals(token.songId)&&title.equals(token.title);
        }

        @Override
        public int hashCode(){return (kind*31+songId.hashCode())*31+title.hashCode();}
    }

    private MusicProtocol() { }

    static String invite(){return MusicPlatform.qq()?"[QQ音乐#一起听]":INVITE_TEXT;}
    static String accept(){return ACCEPT_TEXT;}
    static String refuse(){return REFUSE_TEXT;}
    static String share(String songId,String title){
        String safe=title==null?"":title.replace("#","").replace("]","").replace("[","").trim();
        if(safe.length()>MAX_TITLE)safe=safe.substring(0,MAX_TITLE);
        return (songId.startsWith("qq:")?"[QQ音乐#"+songId.substring(3):CLOUD+songId)+"#"+safe+"]";
    }

    /** 整行解析；不是合法 token 返回 null。 */
    static Token parse(String text){
        if(text==null||indented(text))return null;
        String line=text.trim();
        if(line.startsWith("[QQ音乐#")){Token t=parse("[网易云#"+line.substring(6));return t==null?null:new Token(t.kind,t.kind==SONG?"qq:"+t.songId:"qq:",t.title);}
        if(line.equals(INVITE_TEXT))return new Token(INVITE,"","");
        if(line.equals(ACCEPT_TEXT))return new Token(ACCEPT,"","");
        if(line.equals(REFUSE_TEXT))return new Token(REFUSE,"","");
        if(!line.startsWith(CLOUD)||!line.endsWith("]"))return null;
        String inner=line.substring(CLOUD.length(),line.length()-1);
        String[] parts=inner.split("#",-1);
        if(parts.length!=2)return null;
        if(!valid(parts[0])||!valid(parts[1]))return null;
        return new Token(SONG,parts[0].trim(),parts[1].trim());
    }

    /** 只判断"这句话看起来是不是想用协议"，用于隐藏气泡里的半截 token。 */
    static boolean incomplete(String text){
        if(text==null||indented(text))return false;
        String line=text.trim();
        if(line.length()==0||line.length()>270)return false;
        if(line.indexOf(']')>=0)return false;
        return line.startsWith(CLOUD)||line.startsWith("[QQ音乐#")||line.startsWith(TOGETHER);
    }

    private static final Pattern BRACKET=Pattern.compile("\\[([^\\[\\]]{1,80})\\]");

    /**
     * 在一段消息里找出所有能认出来的 token。
     *
     * <p>比 {@link #parse(String)} 宽松：AI 常常把 token 写成「**[一起听#接受]**」、
     * 或者在 token 前后带一句话，这里都认；代码块和缩进行里的仍然忽略，
     * 免得把"引用示例"当成真指令。回复词也容忍同义写法（同意/可以/好/拒绝/不了…）。
     */
    static java.util.List<Token> scan(String text){
        java.util.List<Token> out=new java.util.ArrayList<Token>();
        if(text==null||text.length()==0)return out;
        boolean fenced=false;
        for(String raw:text.split("\\r?\\n")){
            String line=raw.trim();
            if(line.startsWith("```")){fenced=!fenced;continue;}
            if(fenced||line.length()==0)continue;
            if(indented(raw))continue;
            collect(line,out);
            if(out.isEmpty()&&line.length()<80){
                // 整行没有括号但正文就是协议词：交给严格解析兜一下。
                Token token=parse(line);
                if(token!=null)out.add(token);
            }
        }
        return out;
    }

    private static void collect(String line,java.util.List<Token> out){
        Matcher matcher=BRACKET.matcher(line);
        while(matcher.find()){
            String inner=matcher.group(1)==null?"":matcher.group(1).replaceAll("\\s+","").trim();
            if(inner.length()==0)continue;
            Token token=tokenOf(inner);
            if(token!=null&&!out.contains(token))out.add(token);
        }
    }

    /** 把一个方括号里的内容翻成 token；不认识返回 null。 */
    static Token tokenOf(String inner){
        if(inner==null)return null;
        if(inner.startsWith("QQ音乐#")){Token t=tokenOf("网易云#"+inner.substring(5));return t==null?null:new Token(t.kind,t.kind==SONG?"qq:"+t.songId:"qq:",t.title);}
        String value=inner.replaceAll("\\s+","").trim();
        if(value.length()==0)return null;
        if(value.equals("网易云#一起听")||value.equals("一起听#一起听")
                ||value.equals("网易云#听歌")||value.equals("一起听#来")){
            return new Token(INVITE,"","");
        }
        int cut=value.indexOf('#');
        if(cut<0)return null;
        String head=value.substring(0,cut);
        String tail=value.substring(cut+1);
        if(value.startsWith("网易云#")){
            int next=tail.indexOf('#');
            if(next<0){
                String id=tail.trim();
                return valid(id)?new Token(SONG,id,""):null;
            }
            String id=tail.substring(0,next).trim();
            String title=tail.substring(next+1).trim();
            if(!valid(id))return null;
            if(title.length()==0)return new Token(SONG,id,"");
            return valid(title)?new Token(SONG,id,title):null;
        }
        if("一起听".equals(head)){
            if(acceptWord(tail))return new Token(ACCEPT,"","");
            if(refuseWord(tail))return new Token(REFUSE,"","");
            if("一起听".equals(tail))return new Token(INVITE,"","");
        }
        return null;
    }

    /** AI 的「接受」有很多说法，都算同意。 */
    static boolean acceptWord(String word){
        if(word==null)return false;
        String value=word.trim();
        return value.equals("接受")||value.equals("同意")||value.equals("可以")||value.equals("好的")
                ||value.equals("好")||value.equals("行")||value.equals("安排")||value.equals("来吧")
                ||value.equals("开始吧")||value.equals("开始")||value.equals("没问题")
                ||value.equalsIgnoreCase("ok")||value.equalsIgnoreCase("yes");
    }

    /** 同理，拒绝也容忍几种说法。 */
    static boolean refuseWord(String word){
        if(word==null)return false;
        String value=word.trim();
        return value.equals("拒绝")||value.equals("不了")||value.equals("不要")||value.equals("算了")
                ||value.equals("不行")||value.equals("没空")||value.equals("下次吧")||value.equals("下次")
                ||value.equalsIgnoreCase("no");
    }

    /**
     * 没有 token 时按文字粗略判断对方是否答应（只在"等待回复"状态下用，判定保守）。
     * AI 经常直接回"好啊，我们一起听吧"而不带标记，这里兜一层。
     */
    static int proseAnswer(String text){
        if(text==null)return 0;
        String value=text.replaceAll("\\s+","");
        if(value.length()==0||value.length()>80)return 0;
        if(value.contains("拒绝")||value.contains("不想听")||value.contains("算了")||value.contains("下次吧"))
            return REFUSE;
        if(value.contains("接受邀请")||value.contains("同意")||value.contains("一起听吧")
                ||value.contains("一起来听")||value.contains("可以啊")||value.contains("好啊")
                ||value.contains("开始吧")||value.contains("来吧")||value.contains("听歌吧"))
            return ACCEPT;
        return 0;
    }

    /** 列表里是否出现某个 kind。 */
    static boolean hasKind(java.util.List<Token> tokens,int kind){
        if(tokens==null)return false;
        for(int i=0;i<tokens.size();i++){
            if(tokens.get(i).kind==kind)return true;
        }
        return false;
    }

    /** 给日志用的可读名字。 */
    static String name(Token token){
        if(token==null)return "无";
        switch(token.kind){
            case INVITE:return "邀请";
            case ACCEPT:return "接受";
            case REFUSE:return "拒绝";
            case SONG:return "分享("+token.songId+(token.title.length()>0?" "+token.title:"")+")";
            default:return "未知";
        }
    }

    static String normalize(String text){
        if(text==null)text="";
        String value=Normalizer.normalize(text,Normalizer.Form.NFKC);
        return value.replaceAll("\\s+","").toLowerCase(Locale.ROOT);
    }

    static boolean valid(String text){
        if(text==null)return false;
        String value=text.trim();
        if(value.length()==0||value.length()>MAX_TITLE)return false;
        for(int i=0;i<value.length();i++){
            char ch=value.charAt(i);
            if(Character.isISOControl(ch)||Character.getType(ch)==Character.FORMAT)return false;
            if("[]#\\`".indexOf(ch)>=0)return false;
        }
        return true;
    }

    /** 缩进（≥4 空格或含 tab，即 Markdown 代码块/引用）里嵌的 token 一律不认。 */
    private static boolean indented(String text){
        int i=0;
        while(i<text.length()&&text.charAt(i)<=32)i++;
        int start=Math.max(0,text.lastIndexOf('\n',Math.max(0,i-1))+1);
        String lead=text.substring(start,i);
        return lead.length()>=4||lead.indexOf('\t')>=0;
    }

    static String time(long ms){
        long seconds=Math.max(0L,ms)/1000L;
        return String.format(Locale.ROOT,"%02d:%02d",seconds/60L,seconds%60L);
    }
}