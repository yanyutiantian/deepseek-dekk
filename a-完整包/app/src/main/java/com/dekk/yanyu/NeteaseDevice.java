package com.dekk.yanyu;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Random;

/**
 * 稳定的设备标识 Cookie。
 *
 * <p>网易云对"没有任何设备信息"的请求更容易判为异常环境（登录时会直接回
 * 「网络环境存在异常」），所以这里生成一次 _ntes_nuid / _ntes_nnid / NMTID
 * 并固定存在本地，之后所有请求都带上。
 */
final class NeteaseDevice {
    private static final String PREF="dekk_music";
    private static final String KEY_NUID="_ntes_nuid";
    private static final String KEY_NNID="_ntes_nnid";
    private static final String KEY_NMTID="NMTID";
    private static String cached;
    private static String value;

    private NeteaseDevice() { }

    static synchronized String cookies(){
        if(cached!=null)return cached;
        Context context=HotApp.currentApplication();
        if(context==null)return "";
        SharedPreferences prefs=context.getSharedPreferences(PREF,0);
        String nuid=prefs.getString(KEY_NUID,"");
        if(nuid.length()!=32){
            nuid=random(32);
            prefs.edit()
                    .putString(KEY_NUID,nuid)
                    .putString(KEY_NNID,nuid+","+System.currentTimeMillis())
                    .putString(KEY_NMTID,random(32))
                    .apply();
        }
        String nnid=prefs.getString(KEY_NNID,nuid+","+System.currentTimeMillis());
        String nmtid=prefs.getString(KEY_NMTID,random(32));
        value=nuid;
        cached="_ntes_nuid="+nuid+"; _ntes_nnid="+nnid+"; NMTID="+nmtid;
        return cached;
    }

    /** 客户端登录用的 deviceId（32 位十六进制，和设备标识同一份，固定不变）。 */
    static synchronized String deviceId(){
        if(value==null)cookies();
        return value==null?"":value;
    }

    private static String random(int length){
        Random random=new Random();
        StringBuilder text=new StringBuilder();
        String hex="0123456789abcdef";
        for(int i=0;i<length;i++)text.append(hex.charAt(random.nextInt(16)));
        return text.toString();
    }

    /** 设备标识被风控"记住"了？清掉重新生成一份（下次请求生效）。 */
    static synchronized void reset(){
        cached=null;
        Context context=HotApp.currentApplication();
        if(context==null)return;
        context.getSharedPreferences(PREF,0).edit()
                .remove(KEY_NUID).remove(KEY_NNID).remove(KEY_NMTID).apply();
    }
}