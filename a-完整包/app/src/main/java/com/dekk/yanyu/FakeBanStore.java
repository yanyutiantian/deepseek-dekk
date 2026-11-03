package com.dekk.yanyu;

import android.content.Context;

/** Local simulation only; expiry survives host process restarts. */
final class FakeBanStore {
    static final int MAX_MINUTES=43200;
    private static final String PREF="dekk_fake_ban";
    static int minutes(Context c){return c.getSharedPreferences(PREF,0).getInt("minutes",60);}
    static long until(Context c){return Math.max(c.getSharedPreferences(PREF,0).getLong("until",0),AiBanControl.until(c));}
    static long remaining(Context c){return remainingAt(until(c),System.currentTimeMillis());}
    static long remainingAt(long end,long now){return end>now?end-now:0;}
    static boolean active(Context c){return c!=null&&remaining(c)>0;}
    static void start(Context c,int minutes){
        if(minutes<1||minutes>MAX_MINUTES)throw new IllegalArgumentException("duration");
        c.getSharedPreferences(PREF,0).edit().putInt("minutes",minutes).putLong("until",System.currentTimeMillis()+minutes*60000L).apply();
        MultiReplyQueue.cancel();
    }
    static void startUntil(Context c,long end){
        if(end<=System.currentTimeMillis())throw new IllegalArgumentException("请选择未来时间");
        c.getSharedPreferences(PREF,0).edit().putLong("until",end).apply();
        MultiReplyQueue.cancel();FakeBanHook.refresh();
    }
    static void stop(Context c){c.getSharedPreferences(PREF,0).edit().remove("until").apply();FakeBanHook.refresh();}
    static String remainingText(Context c){long seconds=(remaining(c)+999)/1000;return (seconds/3600)+"小时"+((seconds/60)%60)+"分"+(seconds%60)+"秒";}
}