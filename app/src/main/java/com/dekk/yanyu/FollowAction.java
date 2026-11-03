package com.dekk.yanyu;

import android.app.Activity;
import org.json.JSONObject;

/** 关注 / 取关（详情页与创作者主页共用）。 */
final class FollowAction {
    interface Listener { void done(boolean following, String error); }

    private FollowAction() { }

    /** 关注 / 取关：取关先二次确认（像抖音那样）。 */
    static void ask(final Activity activity,final int target,final String name,
                    final boolean following,final Listener listener){
        if(target<=0){listener.done(false,"找不到这个创作者");return;}
        if(!following){toggle(activity,target,true,listener);return;}
        new ModuleDialog(activity)
                .setTitle("取消关注")
                .setMessage("确定不再关注“"+name+"”吗？")
                .setNegativeButton("取消",null)
                .setPositiveButton("确定取消",new android.content.DialogInterface.OnClickListener(){
                    public void onClick(android.content.DialogInterface dialog,int which){
                        toggle(activity,target,false,listener);
                    }})
                .show();
    }

    static void toggle(final Activity activity,final int target,final boolean on,final Listener listener){
        if(target<=0){listener.done(false,"找不到这个创作者");return;}
        if(AccountStore.token(activity).length()==0){AccountPages.open(activity);return;}
        PersonaApi.request(activity,"follow",PersonaApi.object("id",String.valueOf(target),"on",on?"1":"0"),
                new PersonaApi.Callback(){public void done(JSONObject result,String error){
            if(error!=null){listener.done(!on,error);return;}
            listener.done(result!=null&&result.optBoolean("following",on),null);
        }});
    }
}