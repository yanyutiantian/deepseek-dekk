package com.dekk.yanyu;

import android.app.Activity;
import android.content.Intent;

/**
 * 头像选图的回执通道：把我们发的 ACTION_OPEN_DOCUMENT 结果转发给 {@link ChatAvatarStore}。
 * 模块没有自己的 Activity，所以只能挂宿主的 onActivityResult。
 */
final class ChatAvatarHook {
    private static boolean installed;

    private ChatAvatarHook() { }

    static synchronized void install(ClassLoader loader){
        if(installed)return;
        try{
            // 1) 基类：没覆写 onActivityResult 的 Activity 走这里
            HotHelpers.findAndHookMethod(Activity.class,"onActivityResult",
                    int.class,int.class,Intent.class,new HotHook(){
                @Override
                protected void afterHookedMethod(HotParam param){
                    if(!(param.thisObject instanceof Activity))return;
                    dispatch((Activity)param.thisObject,param);
                }
            });
            // 2) 覆写了 onActivityResult 的子类：Activity 一创建就补挂一次，保证一定收到回执
            HotHelpers.findAndHookMethod(Activity.class,"onCreate",android.os.Bundle.class,
                    new HotHook(){
                @Override
                protected void afterHookedMethod(HotParam param){
                    if(param.thisObject instanceof Activity)hookOverride(((Activity)param.thisObject).getClass());
                }
            });
            installed=true;
            HotBridge.log("[DEKK][头像] 选图回执已接入");
        }catch(Throwable t){
            HotBridge.log("[DEKK][头像] 安装失败: "+t);
        }
    }

    private static final java.util.Set<Class<?>> hooked=
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Class<?>,Boolean>());

    /** 把某个 Activity 子类自己声明的 onActivityResult 补挂上（只挂一次）。 */
    static void hookOverride(Class<?> type){
        if(type==null||type==Activity.class)return;
        hookOverride(type.getSuperclass());
        try{
            synchronized(hooked){if(hooked.contains(type))return;}
            java.lang.reflect.Method method=type.getDeclaredMethod("onActivityResult",
                    int.class,int.class,Intent.class);
            if(method.getDeclaringClass()==Activity.class)return;
            HotBridge.hookMethod(method,new HotHook(){
                @Override
                protected void beforeHookedMethod(HotParam param){
                    int request=(Integer)param.args[0];
                    if(request!=BackgroundImageImport.REQUEST&&request!=ChatAvatarStore.REQUEST_USER&&request!=ChatAvatarStore.REQUEST_AI&&request!=AvatarUi.REQUEST)return;
                    param.setResult(null);
                    dispatch((Activity)param.thisObject,param);
                }
            });
            synchronized(hooked){hooked.add(type);}
            HotBridge.log("[DEKK][头像] 已接管 "+type.getName()+" 的选图回执");
        }catch(NoSuchMethodException ignored){
            synchronized(hooked){hooked.add(type);}
        }catch(Throwable t){
            HotBridge.log("[DEKK][头像] 补挂失败: "+t);
        }
    }

    private static void dispatch(Activity activity,HotParam param){
        try{
            int request=(Integer)param.args[0];
            int result=param.args.length>1?(Integer)param.args[1]:0;
            Intent data=param.args.length>2&&param.args[2] instanceof Intent?(Intent)param.args[2]:null;
            if(BackgroundImageImport.handleResult(activity,request,result,data))return;
            if(request==AvatarUi.REQUEST){UserCenterPage.handleResult(activity,request,result,data);return;}
            ChatAvatarStore.handleResult(activity,request,result,data);
        }catch(Throwable t){
            HotBridge.log("[DEKK][头像] 回执处理失败: "+t);
        }
    }
}