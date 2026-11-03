package com.dekk.yanyu;

import android.content.Context;

/** QQ 官网播放器使用的随机 guid；与扫码 Cookie 中的腾讯设备凭据分开。 */
final class QqMusicDevice {
    static synchronized String guid(Context c){
        android.content.SharedPreferences p=c.getSharedPreferences("dekk_qqmusic_device",0);
        String value=p.getString("guid","");
        if(value.length()==0){value=new java.math.BigInteger(63,new java.security.SecureRandom()).toString();p.edit().putString("guid",value).apply();}
        return value;
    }
    static synchronized void reset(Context c){c.getSharedPreferences("dekk_qqmusic_device",0).edit().remove("guid").apply();guid(c);}
    static String description(Context c){
        return "设备："+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+"\nAndroid "+android.os.Build.VERSION.RELEASE
            +"\n播放器 GUID："+guid(c)+"\n重置只改变本模块播放器 GUID，不清除账号，也不修改腾讯登录设备凭据。";
    }
}