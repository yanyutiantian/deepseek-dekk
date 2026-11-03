package com.dekk.yanyu;

import java.util.LinkedHashMap;
import java.util.Map;

/** CK 解析与拼装：登录只需 MUSIC_U，__csrf 用来带 csrf_token。 */
final class NeteaseCookie {
    static final String KEY_MUSIC_U="MUSIC_U";
    static final String KEY_CSRF="__csrf";

    private NeteaseCookie() { }

    static Map<String,String> pairs(String cookie){
        Map<String,String> result=new LinkedHashMap<String,String>();
        if(cookie==null)return result;
        for(String part:cookie.split(";")){
            String pair=part.trim();
            int cut=pair.indexOf('=');
            if(cut<=0)continue;
            String name=pair.substring(0,cut).trim();
            String value=pair.substring(cut+1).trim();
            if(name.length()>0)result.put(name,value);
        }
        return result;
    }

    static String build(Map<String,String> pairs){
        StringBuilder text=new StringBuilder();
        for(Map.Entry<String,String> entry:pairs.entrySet()){
            if(entry.getKey()==null||entry.getKey().length()==0)continue;
            if(text.length()>0)text.append("; ");
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.toString();
    }

    static String normalize(String raw){
        Map<String,String> pairs=pairs(raw);
        pairs.remove("Path");
        pairs.remove("path");
        pairs.remove("Domain");
        pairs.remove("domain");
        pairs.remove("Expires");
        pairs.remove("expires");
        return build(pairs);
    }

    static String value(String cookie,String key){
        return cookie==null?null:pairs(cookie).get(key);
    }

    static boolean valid(String cookie){
        String musicU=value(cookie,KEY_MUSIC_U);
        return musicU!=null&&musicU.length()>=16;
    }

    /** weapi 需要 csrf_token，值就是 __csrf。 */
    static String csrf(String cookie){
        String value=value(cookie,KEY_CSRF);
        return value==null?"":value;
    }

    /** 日志里只显示掩码，避免把可用 CK 写进日志。 */
    static String masked(String cookie){
        if(cookie==null||cookie.length()==0)return "(空)";
        String musicU=value(cookie,KEY_MUSIC_U);
        if(musicU==null)return "无 MUSIC_U（"+cookie.length()+" 字符）";
        String head=musicU.length()>6?musicU.substring(0,6):musicU;
        return "MUSIC_U="+head+"…（"+pairs(cookie).size()+" 项）";
    }
}