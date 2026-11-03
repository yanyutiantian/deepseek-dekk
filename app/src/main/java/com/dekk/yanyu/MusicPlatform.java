package com.dekk.yanyu;

import android.content.Context;
/** 一个进程只启用一个音乐来源；切换必须先结束当前会话和播放。 */
final class MusicPlatform {
    private static volatile boolean qq;
    static boolean qq(){return qq;}
    static boolean select(boolean value){
        if(value==qq)return true;
        if(ListenTogether.state()!=ListenTogether.OFF||MusicPlayer.ready())return false;
        MusicPlayer.get().queue().clear();qq=value;return true;
    }
    static boolean loggedIn(Context c){return qq?QqMusicAccount.loggedIn(c):NeteaseAccount.loggedIn(c);}
}