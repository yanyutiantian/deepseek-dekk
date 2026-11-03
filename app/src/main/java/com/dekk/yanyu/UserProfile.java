package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** 个人资料：服务端读取/保存 + 本地缓存（昵称、头像地址、四个计数）。 */
final class UserProfile {
    private static final String PREF="dekk_profile";
    private static JSONObject cached;

    interface Callback { void done(JSONObject profile, String error); }

    private UserProfile() { }

    private static SharedPreferences prefs(Context context){
        return context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }

    /** 资料缓存（先读本地，页面不闪）。 */
    static JSONObject get(){
        if(cached==null){
            Context context=HotApp.currentApplication();
            String text=context==null?"":prefs(context).getString("profile","");
            try{cached=text.length()==0?new JSONObject():new JSONObject(text);}
            catch(Exception e){cached=new JSONObject();}
        }
        return cached;
    }

    private static void remember(Context context,JSONObject profile){
        cached=profile;
        if(context!=null)prefs(context).edit().putString("profile",profile==null?"":profile.toString()).apply();
    }

    /** 昵称：先看服务端资料，再退回登录时保存的账号信息。 */
    static String nickname(Context context){
        String name=get().optString("nickname","");
        if(name.length()==0&&context!=null)name=AccountStore.user(context).optString("nickname","");
        return name.length()==0?"未设置昵称":name;
    }

    static String avatarUrl(JSONObject profile){
        JSONObject value=profile==null?get():profile;
        return value.optString("avatar_url","");
    }

    static int value(JSONObject profile,String key){
        JSONObject value=profile==null?get():profile;
        return value.optInt(key,0);
    }

    /** 服务端确认过赞助状态后回写资料缓存（{@link SponsorApi} 用）。 */
    static void rememberSponsor(Context context,boolean sponsor){
        try{
            JSONObject profile=new JSONObject(get().toString());
            profile.put("sponsor",sponsor?1:0);
            remember(context,profile);
        }catch(Exception ignored){ }
    }

    /** 是否赞助用户（服务端 profile 的 sponsor 字段）。 */
    static boolean isSponsor(Context context){
        JSONObject profile=get();
        if(profile.optBoolean("sponsor",false))return true;
        return profile.optInt("sponsor",0)==1;
    }

    /** 本地缓存里是否已经有 sponsor 字段；没有就说明该重新拉一次资料。 */
    static boolean hasSponsorInfo(){
        return get().has("sponsor");
    }

    static void load(final Activity activity,final Callback callback){
        PersonaApi.request(activity,"profile_get",null,new PersonaApi.Callback(){
            public void done(JSONObject result,String error){
                if(error!=null){callback.done(null,error);return;}
                JSONObject profile=result==null?null:result.optJSONObject("profile");
                if(profile==null){callback.done(null,"资料响应格式错误");return;}
                remember(activity.getApplicationContext(),profile);
                callback.done(profile,null);
            }
        });
    }

    /** 修改昵称（服务端 nickname 接口），同时更新本地缓存与登录信息。 */
    static void saveName(final Activity activity,final String name,final Callback callback){
        PersonaApi.request(activity,"nickname",PersonaApi.object("nickname",name),new PersonaApi.Callback(){
            public void done(JSONObject result,String error){
                if(error!=null){callback.done(null,error);return;}
                try{
                    JSONObject profile=new JSONObject(get().toString());
                    profile.put("nickname",name);
                    remember(activity.getApplicationContext(),profile);
                    JSONObject user=new JSONObject(AccountStore.user(activity).toString());
                    user.put("nickname",name);
                    AccountStore.updateUser(activity.getApplicationContext(),user);
                }catch(Exception ignored){ }
                callback.done(get(),null);
            }
        });
    }

    /** 上传头像：客户端已压到最长边 512 的 JPEG，再 base64 传给服务端。 */
    static void uploadAvatar(final Activity activity,final String base64,final Callback callback){
        PersonaApi.request(activity,"profile_avatar",PersonaApi.object("image",base64),new PersonaApi.Callback(){
            public void done(JSONObject result,String error){
                if(error!=null){callback.done(null,error);return;}
                JSONObject profile=get();
                try{
                    profile=new JSONObject(profile.toString());
                    String name=result==null?"":result.optString("avatar","");
                    if(name.length()>0)
                        profile.put("avatar_url","https://dekk.example.com/API/?action=avatar&id="
                                +profile.optInt("id",0)+"&v="+System.currentTimeMillis());
                    remember(activity.getApplicationContext(),profile);
                }catch(Exception ignored){ }
                callback.done(get(),null);
            }
        });
    }
}