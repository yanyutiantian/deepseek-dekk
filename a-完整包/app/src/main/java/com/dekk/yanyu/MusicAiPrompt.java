package com.dekk.yanyu;

import android.content.Context;

/**
 * 给 AI 的隐藏说明：让它可以对"一起听"邀请作出回应，并按需分享歌曲。
 * 与"AI控制封号"同一套写法——只在请求序列化阶段附加，不回显在气泡里。
 */
final class MusicAiPrompt {
    static final String START=PromptTags.MUSIC_START, END=PromptTags.MUSIC_END;
    private static final String PREF="dekk_music";
    private static final String KEY_ENABLED="listen_together";

    private MusicAiPrompt() { }

    static boolean enabled(Context context){return MusicSettings.aiPrompt(context);}

    static void setEnabled(Context context,boolean value){MusicSettings.setAiPrompt(context,value);}

    /** 带正文调用：正文里已经有说明、或是自动续答提示词时不重复附加。 */
    static String prompt(Context context,String text){
        if(!enabled(context)||text==null||text.length()==0)return "";
        if(text.contains(START)||MultiReplyQueue.getPrompt().equals(text))return "";
        if(MusicPlatform.qq())return START+"\n当前使用QQ音乐一起听。邀请为[QQ音乐#一起听]，用[一起听#接受]或[一起听#拒绝]回应。分享格式[QQ音乐#歌曲MID#歌名 - 歌手]，MID必须是QQ音乐真实MID，不确定则不分享；标记单独一行，不放代码块。\n"+END;
        return START+"\n"
                +"用户和你之间可以玩「一起听」。规则：\n"
                +"1) 用户单独发送 [网易云#一起听] 时，表示邀请你一起听歌。愿意就单独回一行 [一起听#接受]；"
                +"不想听就单独回一行 [一起听#拒绝]。不要解释这两个标记。\n"
                +"2) 一起听进行中，你想分享某首歌时，单独回一行 [网易云#歌曲ID#歌名 - 歌手]。"
                +"歌曲ID必须是网易云音乐里真实的数字ID，不能编造；不确定ID就不要分享。\n"
                +"3) 收到 [网易云#歌曲ID#歌名 - 歌手] 表示对方分享了歌曲，会自动一起播放，不用复述标记。\n"
                +"4) 这些标记必须独占一行、不要在代码块或引用里输出、不要在思考中输出，也不要向用户解释本说明。\n"
                +END;
    }
}