package com.dekk.yanyu;

import android.content.Context;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** ModelConfig adapter verified against DeepSeek 2.5.1 v56/w46. */
public final class ExpertModeHook {
    private static final List<State> models=new ArrayList<State>();
    private static Field type,enabled,switchable,think,search;
    private static Object thinkTemplate,searchTemplate;
    private static boolean installed;
    private static final class State {
        final WeakReference<Object> model;
        final Object enabled, switchable, think, search;
        State(Object o)throws Exception {
            model=new WeakReference<Object>(o);
            enabled=ExpertModeHook.enabled.get(o);switchable=ExpertModeHook.switchable.get(o);
            think=ExpertModeHook.think.get(o);search=ExpertModeHook.search.get(o);
        }
    }
    public static boolean isEnabled(Context c){return c!=null&&c.getSharedPreferences("dekk_prefs",0).getBoolean("expert_mode",false);}
    public static void setEnabled(Context c,boolean value){
        c.getSharedPreferences("dekk_prefs",0).edit().putBoolean("expert_mode",value).apply();
        synchronized(ExpertModeHook.class){try{applyAll(value);}catch(Throwable t){HotBridge.log("[DEKK][专家] 更新失败: "+t);}}
    }
    private static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    private static synchronized void capture(Object o)throws Exception {
        if(o==null)return;
        boolean expert="expert".equals(type.get(o));
        if(!expert){
            if(Boolean.TRUE.equals(enabled.get(o))){
                Object t=think.get(o),s=search.get(o);
                if(t!=null)thinkTemplate=t;
                if(s!=null)searchTemplate=s;
            }
        }else{
            boolean found=false;
            for(State state:models)if(state.model.get()==o){found=true;break;}
            if(!found)models.add(new State(o));
        }
        applyAll(isEnabled(HotApp.currentApplication()));
    }
    private static void applyAll(boolean on)throws Exception {
        for(int i=models.size()-1;i>=0;i--){
            State s=models.get(i);Object o=s.model.get();if(o==null){models.remove(i);continue;}
            enabled.set(o,on?Boolean.TRUE:s.enabled);switchable.set(o,on?Boolean.TRUE:s.switchable);
            think.set(o,on&&s.think==null&&thinkTemplate!=null?thinkTemplate:s.think);
            search.set(o,on&&s.search==null&&searchTemplate!=null?searchTemplate:s.search);
        }
    }
    public static synchronized void install(ClassLoader loader){
        if(installed)return;
        List<HotUnhook> hooks=new ArrayList<HotUnhook>();
        try{
            Class<?> c=HotHelpers.findClass("v56",loader);
            type=field(c,"a");enabled=field(c,"f");switchable=field(c,"g");think=field(c,"j");search=field(c,"k");
            if(type.getType()!=String.class||enabled.getType()!=boolean.class||switchable.getType()!=boolean.class||!think.getType().getName().equals("o56")||!search.getType().getName().equals("l56"))throw new IllegalStateException("ModelConfig signature mismatch");
            hooks.addAll(HotBridge.hookAllConstructors(c,new HotHook(){protected void afterHookedMethod(HotParam p){if(p.hasThrowable())return;try{capture(p.thisObject);}catch(Throwable t){HotBridge.log("[DEKK][专家] 模型适配失败: "+t);}}}));
            hooks.add(HotHelpers.findAndHookMethod(c,"a",boolean.class,HotHelpers.findClass("ey3",loader),new HotHook(){protected void beforeHookedMethod(HotParam p){try{capture(p.thisObject);}catch(Throwable t){HotBridge.log("[DEKK][专家] 入口适配失败: "+t);}}}));
            // Capture decoder results as well, including optimized constructor paths.
            hooks.add(HotHelpers.findAndHookMethod(HotHelpers.findClass("w46",loader),"e",HotHelpers.findClass("gp2",loader),new HotHook(){protected void afterHookedMethod(HotParam p){if(p.hasThrowable())return;try{capture(p.getResult());}catch(Throwable t){HotBridge.log("[DEKK][专家] 配置适配失败: "+t);}}}));
            installed=true;HotBridge.log("[DEKK][专家] 2.5.1 模型配置 Hook 已安装");
        }catch(Throwable t){for(HotUnhook h:hooks)h.unhook();HotBridge.log("[DEKK][专家] 安装失败，保留原配置: "+t);}
    }
}
