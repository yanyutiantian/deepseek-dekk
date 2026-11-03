package com.dekk.yanyu;

import android.content.Context;

/** 一起听的开关：迷你播放条、给 AI 的隐藏说明。 */
final class MusicSettings {
    private static final String PREF="dekk_music";
    private static final String KEY_MINI="mini_bar";
    private static final String KEY_AI="listen_together";
    private static final String KEY_QUALITY="quality";
    private static final String KEY_NOW="share_now";

    private MusicSettings() { }

    private static android.content.SharedPreferences prefs(Context context){
        return context.getSharedPreferences(PREF,0);
    }

    static boolean miniBar(Context context){
        return context!=null&&prefs(context).getBoolean(KEY_MINI,true);
    }

    static void setMiniBar(Context context,boolean value){
        if(context==null)return;
        prefs(context).edit().putBoolean(KEY_MINI,value).apply();
    }

    static boolean aiPrompt(Context context){
        return context!=null&&prefs(context).getBoolean(KEY_AI,true);
    }

    static void setAiPrompt(Context context,boolean value){
        if(context==null)return;
        prefs(context).edit().putBoolean(KEY_AI,value).apply();
    }

    /** 在播歌时，每条消息都自动带上"当前音乐"隐藏说明（默认开）。 */
    static boolean shareNow(Context context){
        return context!=null&&prefs(context).getBoolean(KEY_NOW,true);
    }

    static void setShareNow(Context context,boolean value){
        if(context==null)return;
        prefs(context).edit().putBoolean(KEY_NOW,value).apply();
    }

    static boolean repeatOne(Context context){
        return context!=null&&prefs(context).getBoolean("repeat_one",false);
    }

    static void setRepeatOne(Context context,boolean repeat){
        if(context!=null)prefs(context).edit().putBoolean("repeat_one",repeat).apply();
    }

    static int quality(Context context){
        return context==null?NeteaseApi.BR_HIGH:prefs(context).getInt(KEY_QUALITY,NeteaseApi.BR_HIGH);
    }

    static void setQuality(Context context,int br){
        if(context==null)return;
        prefs(context).edit().putInt(KEY_QUALITY,br).apply();
    }
}