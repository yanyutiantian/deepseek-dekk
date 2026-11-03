package com.dekk.yanyu;

import android.content.Context;
import org.json.JSONObject;

/** 登录与刷新共用的账号资料解析，不覆盖失败前的有效账号。 */
final class QqMusicProfile {
    static QqMusicAccount.Info fetch(String cookie)throws Exception{
        if(!QqMusicApi.valid(cookie))throw new java.io.IOException("请先登录 QQ 音乐");
        JSONObject data=QqMusicApi.call("music.UserInfo.userInfoServer","GetLoginUserInfo",new JSONObject(),cookie);
        QqMusicAccount.Info info=new QqMusicAccount.Info();info.cookie=cookie;
        info.name=find(data,new String[]{"nick","nickname","nickName","name"},0);
        info.avatar=find(data,new String[]{"headurl","headUrl","headpic","headPic","head_pic","avatarUrl","avatar","picurl","face"},0);
        if(info.avatar.startsWith("//"))info.avatar="https:"+info.avatar;
        if(info.avatar.startsWith("http://"))info.avatar="https://"+info.avatar.substring(7);
        if(!info.avatar.startsWith("https://"))info.avatar="";
        String uin=QqMusicApi.value(cookie,"uin");
        if(info.avatar.length()==0&&uin.matches("[0-9]+"))
            info.avatar="https://c.y.qq.com/rsc/fcgi-bin/fcg_get_qqhead_image.fcg?jump=1&uin="+uin;
        try{info.userId=Long.parseLong(uin);}catch(NumberFormatException ignored){}
        if(info.name.length()==0)info.name="QQ音乐用户 "+uin;
        return info;
    }
    /** 各登录类型的 userInfo/baseInfo 层级不同，只解析资料对象。 */
    static String find(JSONObject data,String[] keys,int depth){
        if(data==null||depth>4)return "";
        for(String key:keys){Object value=data.opt(key);if(value instanceof String&&((String)value).trim().length()>0)return ((String)value).trim();}
        for(String key:new String[]{"userInfo","user_info","baseInfo","base_info","info","profile","data","creator"}){
            String value=find(data.optJSONObject(key),keys,depth+1);if(value.length()>0)return value;
        }
        return "";
    }
    static void refresh(Context context)throws Exception{
        String cookie=QqMusicAccount.cookie(context);
        QqMusicAccount.Info info=fetch(cookie);
        // 刷新期间退出或换号时，不把旧账号写回来。
        if(!cookie.equals(QqMusicAccount.cookie(context)))throw new java.io.IOException("账号已改变，请重新刷新");
        QqMusicAccount.Info old=QqMusicAccount.load(context);
        if(info.avatar.length()==0)info.avatar=old.avatar;
        QqMusicAccount.save(context,info);
    }
}