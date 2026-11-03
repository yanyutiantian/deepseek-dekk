package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Map;
import java.util.UUID;

/** 前台空闲主动互动；一次用户消息最多触发一次，不形成无限自聊。 */
final class ProactiveMessages {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static boolean installed;
    private static String observed="";
    private static long since;
    static SharedPreferences preferences(Context c){return c.getSharedPreferences("dekk_proactive",0);}
    static boolean enabled(Context c){return c!=null&&preferences(c).getBoolean("enabled",false);}
    static void setEnabled(Context c,boolean value){preferences(c).edit().putBoolean("enabled",value).apply();observed="";since=SystemClock.elapsedRealtime();}
    static int minutes(Context c){return Math.max(1,Math.min(120,preferences(c).getInt("minutes",5)));}
    static void setMinutes(Context c,int value){preferences(c).edit().putInt("minutes",Math.max(1,Math.min(120,value))).apply();observed="";}
    static void install(){if(installed)return;installed=true;MAIN.post(new Runnable(){public void run(){
        try{tick();}catch(Throwable e){HotBridge.log("[DEKK][主动消息] "+e.getClass().getSimpleName());}
        MAIN.postDelayed(this,10000);
    }});}
    private static void tick()throws Exception{
        Context context=HotApp.currentApplication();Activity activity=DekkSettingsPage.currentActivity();
        HostMessageDispatch dispatch=ConversationTransfers.dispatcher();
        if(!enabled(context)||dispatch==null||activity==null||activity.isFinishing()||!activity.hasWindowFocus()||CenterPage.top(activity)!=null){observed="";return;}
        Object owner=MusicSend.owner();if(owner==null){observed="";return;}
        String session=(String)HotHelpers.callMethod(owner,"e");
        if(session==null||session.length()==0)return;
        try{dispatch.ready(session);}catch(Exception busy){observed="";return;}
        Object input=HotHelpers.callMethod(HotHelpers.callMethod(owner,"Q"),"l");
        String draft=(String)HotHelpers.callMethod(input,"b");
        if(draft!=null&&draft.trim().length()>0){observed="";return;}
        Object state=HotHelpers.callMethod(owner,"M");
        Map<?,?> messages=(Map<?,?>)HotHelpers.getObjectField(state,"f");
        int userId=0,lastId=0;String lastRole="";
        for(Object message:messages.values()){
            int id=(Integer)HotHelpers.callMethod(message,"x");String role=(String)HotHelpers.callMethod(message,"D");
            if(id>lastId){lastId=id;lastRole=role;}
            if("USER".equals(role)&&id>userId){String text=(String)HotHelpers.callMethod(message,"r");
                if(text!=null&&!text.contains(PromptTags.CMD_PING)&&!text.contains(PromptTags.OLD_CMD_PING)&&VirtualTransferProtocol.eventToken(text)==null)userId=id;
            }
        }
        if(userId<=0||!"ASSISTANT".equals(lastRole))return;
        final String key=session+":"+userId;
        if(preferences(context).getBoolean("sent:"+key,false))return;
        String fingerprint=key+":"+lastId;
        if(!fingerprint.equals(observed)){observed=fingerprint;since=SystemClock.elapsedRealtime();return;}
        if(SystemClock.elapsedRealtime()-since<minutes(context)*60000L)return;
        final SharedPreferences prefs=preferences(context);
        String token=UUID.randomUUID().toString();
        String text=PromptTags.CMD_PING+token+"]\n用户开启了空闲主动互动。请结合当前话题自然地发起一句关心或新话题，不要冒充用户，不要重复上一条回复。";
        // 先记已尝试；失败不自动循环重发，下一次真实用户发言后重新获得机会。
        if(!prefs.edit().putBoolean("sent:"+key,true).commit())return;
        try{dispatch.send(session,text,token,new HostMessageDispatch.Completion(){public void done(boolean delivered){
            HotBridge.log("[DEKK][主动消息] "+(delivered?"触发消息已确认":"触发消息未确认，可在会话中重试"));
        }});}catch(Exception e){HotBridge.log("[DEKK][主动消息] 发送未完成: "+e.getMessage());}
    }
    private ProactiveMessages(){}
}