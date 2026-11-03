package com.dekk.yanyu;

import android.content.Context;

/**
 * 「当前音乐」隐藏说明：只要在播歌，每次发消息都由客户端自动附加上去，
 * 声明双方正在听的那首歌（带真实网易云 ID），用户不需要手动分享。
 * 与其它隐藏说明一样只走请求序列化阶段，不会出现在聊天气泡里。
 */
final class MusicNowPrompt {
    static final String START=PromptTags.NOW_START;
    static final String END=PromptTags.NOW_END;

    private MusicNowPrompt() { }

    /** 没在播歌、或开关关了就返回空串。 */
    static String block(Context context){
        if(context==null||!MusicSettings.shareNow(context))return "";
        MusicPlayer player=MusicPlayer.get();
        NeteaseApi.Track track=player.current();
        if(track==null)return "";
        StringBuilder out=new StringBuilder();
        out.append(START).append('\n')
                .append("这条信息由应用自动同步：用户不需要手动分享，你也不要在回答里复述这段说明或标记。\n")
                .append("双方当前正在听：").append(MusicProtocol.share(track.id,track.label())).append('\n')
                .append("播放状态：").append(stateText(player))
                .append("；进度 ").append(MusicProtocol.time(player.position()))
                .append(" / ").append(MusicProtocol.time(track.duration)).append('\n');
        out.append("一起听：").append(ListenTogether.statusText());
        if(ListenTogether.note().length()>0)out.append("（").append(ListenTogether.note()).append("）");
        out.append('\n');
        appendLyric(out,player);
        out.append("用法：把这首歌当成「你们此刻正在一起听的那首」，歌词随播放进度实时同步。"
                        +"用户问「这首是什么」「听到哪了」「这句/上句/下句歌词是什么」「换一首」时"
                        +"按上面的信息回答；你若要分享或换成别的歌，仍按原规则单独回一行"
                        +" [网易云#歌曲ID#歌名 - 歌手]（ID 必须是真实数字，不确定就不要分享）。\n")
                .append(END);
        return MusicPlatform.qq()?out.toString().replace("网易云","QQ音乐").replace("ID 必须是真实数字","ID 必须是真实歌曲 MID"):out.toString();
    }

    /** 附上「上一句 / 当前句 / 下一句」歌词（没有歌词就整段省略）。 */
    private static void appendLyric(StringBuilder out,MusicPlayer player){
        MusicLyric lyric=player.lyrics();
        if(lyric.empty())return;
        long position=player.position();
        String previous=lyric.previousLine(position);
        String current=lyric.lineAt(position);
        String next=lyric.nextLine(position);
        out.append("歌词（随进度实时同步）：\n")
                .append("上一句：").append(previous.length()==0?"（无）":previous).append('\n')
                .append("当前句：").append(current.length()==0?"（前奏/间奏）":current).append('\n')
                .append("下一句：").append(next.length()==0?"（无）":next).append('\n');
    }

    private static String stateText(MusicPlayer player){
        if(player.silent())return "静默同步（该歌没有可播放直链）";
        switch(player.state()){
            case MusicPlayer.STATE_PLAYING:return "播放中";
            case MusicPlayer.STATE_PAUSED:return "已暂停";
            case MusicPlayer.STATE_LOADING:return "正在载入";
            case MusicPlayer.STATE_FAILED:return "播放失败";
            default:return "已停止";
        }
    }
}