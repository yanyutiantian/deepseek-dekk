package com.dekk.yanyu;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.List;
import java.util.Map;

/**
 * token 发送与当前会话读取。
 * 能拿到真实发送入口（nb5.J + 输入框状态）就直接发送，拿不到就退回剪贴板。
 */
final class MusicSend {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static Object sessionOwner;
    private static Object controller;
    private static Object composer;
    private static Object mode;
    private static java.lang.reflect.Method sendMethod;

    private MusicSend() { }

    /**
     * 绑定当前会话对象（宿主 ao1）。
     * 会话 id / 消息表 / 输入框状态都挂在它上面；jb1 只是发送入口用的接口，没有这些方法。
     */
    static void sessionOwner(Object owner){
        if(owner!=null)sessionOwner=owner;
    }

    static void bind(Object controllerArg,Object composerArg,Object modeArg){
        controller=controllerArg;
        composer=composerArg;
        mode=modeArg;
    }

    static void sendMethod(java.lang.reflect.Method method){sendMethod=method;}

    static Object controller(){return controller;}

    /** 当前会话对象（宿主 ao1）；转账等模块共用同一份会话快照。 */
    static Object owner(){return sessionOwner;}

    /** 读取当前会话：[会话ID, 最后一条有正文的消息]。 */
    static String[] readSession(){
        Object owner=sessionOwner;
        if(owner==null)return null;
        try{
            Object session=HotHelpers.callMethod(owner,"M");
            if(session==null)return null;
            Object id=HotHelpers.getObjectField(session,"a");
            Map<?,?> messages=(Map<?,?>)HotHelpers.getObjectField(session,"f");
            if(messages==null||messages.isEmpty())return new String[]{id==null?"":String.valueOf(id),""};
            String last="";
            for(Map.Entry<?,?> entry:messages.entrySet()){
                String text=textOf(entry.getValue());
                if(text.length()>0)last=text;
            }
            return new String[]{id==null?"":String.valueOf(id),last};
        }catch(Throwable t){
            return null;
        }
    }

    private static String textOf(Object message){
        try{
            Object snapshot=HotHelpers.callMethod(message,"a");
            List<?> fragments=(List<?>)HotHelpers.getObjectField(snapshot,"v");
            StringBuilder text=new StringBuilder();
            for(Object fragment:fragments){
                text.append(textOfFragment(fragment));
            }
            return text.toString();
        }catch(Throwable t){
            return "";
        }
    }

    /**
     * 单个消息片段的文本。
     * 正文片段是 tt8.c；但 AI 的句子有时会落在别的片段类型里（链接/代码/引用），
     * 所以这里退化为"找任何看起来像文本的字段"，避免漏掉 token。
     */
    private static String textOfFragment(Object fragment){
        if(fragment==null)return "";
        if(!"tt8".equals(fragment.getClass().getName()))return looseText(fragment);
        try{
            Object value=HotHelpers.getObjectField(fragment,"c");
            return value instanceof String?(String)value:"";
        }catch(Throwable t){
            return looseText(fragment);
        }
    }

    private static String looseText(Object fragment){
        for(String[] pair:new String[][]{{"c",""},{"b",""},{"d",""},{"e",""},{"f",""}}){
            try{
                Object value=HotHelpers.getObjectField(fragment,pair[0]);
                if(value instanceof String&&((String)value).length()>0)return (String)value;
            }catch(Throwable ignored){ }
        }
        return "";
    }

    /** 发送一行 token：优先真实发送入口；拿不到入口就填进输入框让用户点发送。 */
    static boolean send(final Context context,final String text){
        if(text==null||text.length()==0)return false;
        final Object target=composer!=null?composer:composerFromState();
        if(target==null){
            return clipboard(context,text,"没找到当前会话的输入框，已复制到剪贴板");
        }
        final boolean[] sent={false};
        Runnable task=new Runnable(){
            public void run(){
                boolean filled=false;
                try{
                    HotHelpers.callMethod(target,"c",text);
                    filled=true;
                }catch(Throwable t){
                    HotBridge.log("[DEKK][一起听] 写入输入框失败: "+t.getClass().getSimpleName()+" "+t.getMessage());
                }
                if(!filled){
                    clipboard(context,text,"写入输入框失败，已复制到剪贴板");
                    return;
                }
                if(sendMethod==null||mode==null||controller==null){
                    Toast.makeText(context,"已填入输入框，点发送即可",Toast.LENGTH_LONG).show();
                    return;
                }
                try{
                    sendMethod.invoke(null,controller,target,text,mode);
                    sent[0]=true;
                }catch(Throwable t){
                    HotBridge.log("[DEKK][一起听] 自动发送失败: "+t.getClass().getSimpleName()+" "+t.getMessage());
                    Toast.makeText(context,"已填入输入框，点发送即可",Toast.LENGTH_LONG).show();
                }
            }
        };
        if(Looper.myLooper()==Looper.getMainLooper())task.run();
        else MAIN.post(task);
        return sent[0];
    }

    /** 兜底：从会话状态里找输入框状态对象（ao1.Q().l() → yp1）。 */
    static Object composerFromState(){
        Object owner=sessionOwner;
        if(owner==null)return null;
        try{
            Object state=HotHelpers.callMethod(owner,"Q");
            if(state==null)return null;
            Object found=HotHelpers.callMethod(state,"l");
            if(found!=null)composer=found;
            return found;
        }catch(Throwable t){
            return null;
        }
    }

    /** 是否已抓到当前会话（用于诊断输出）。 */
    static boolean hasSession(){return sessionOwner!=null;}

    private static boolean clipboard(Context context,String text,String hint){
        if(context==null)return false;
        try{
            ClipboardManager manager=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
            if(manager==null)return false;
            manager.setPrimaryClip(ClipData.newPlainText("dekk-music",text));
            Toast.makeText(context,hint+"，粘贴发送即可",Toast.LENGTH_LONG).show();
            return true;
        }catch(Throwable t){
            return false;
        }
    }

    /** 判断当前是否为聊天页（有输入框）。 */
    static boolean hasComposer(Activity activity){
        if(activity==null)return false;
        android.view.View root=activity.findViewById(android.R.id.content);
        return findEditText(root)!=null;
    }

    private static android.view.View findEditText(android.view.View view){
        if(!(view instanceof android.view.ViewGroup))return null;
        android.view.ViewGroup group=(android.view.ViewGroup)view;
        for(int i=group.getChildCount()-1;i>=0;i--){
            android.view.View child=group.getChildAt(i);
            if(child instanceof android.widget.EditText)return child;
            android.view.View found=findEditText(child);
            if(found!=null)return found;
        }
        return null;
    }
}