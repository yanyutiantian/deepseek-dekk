package com.dekk.yanyu;

import android.content.Context;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** DeepSeek 2.5.1 ModelConfig v56 and FileFeature a56. */
public final class VisionModeHook {
    private static final List<State> models=new ArrayList<State>();
    private static Field type,enabled,switchable,files;
    private static Class<?> fileClass;
    private static Object template;
    private static boolean installed;
    private static final class State {
        final WeakReference<Object> model;
        final Object enabled,switchable,files;
        Object replacement;
        State(Object o)throws Exception {
            model=new WeakReference<Object>(o);
            enabled=VisionModeHook.enabled.get(o);switchable=VisionModeHook.switchable.get(o);files=VisionModeHook.files.get(o);
        }
    }
    public static boolean isEnabled(Context c){return c!=null&&c.getSharedPreferences("dekk_prefs",0).getBoolean("vision_mode",false);}
    public static void setEnabled(Context c,boolean value){
        c.getSharedPreferences("dekk_prefs",0).edit().putBoolean("vision_mode",value).apply();
        synchronized(VisionModeHook.class){try{applyAll(value);}catch(Throwable t){log(t);}}
    }
    private static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void log(Throwable t){HotBridge.log("[DEKK][识图] 适配失败: "+t.getClass().getSimpleName());}
    private static synchronized void capture(Object model)throws Exception {
        if(model==null)return;
        if("vision".equals(type.get(model))){
            boolean found=false;for(State state:models)if(state.model.get()==model){found=true;break;}
            if(!found)models.add(new State(model));
        }else if(Boolean.TRUE.equals(enabled.get(model))){
            Object candidate=files.get(model);
            if(candidate!=null&&HotHelpers.getIntField(candidate,"c")>0&&HotHelpers.getIntField(candidate,"d")>0){
                if(template==null){template=candidate;for(State state:models)if(state.files==null)state.replacement=null;}
            }
        }
        applyAll(isEnabled(HotApp.currentApplication()));
    }
    private static Object makeFiles(Object original){
        Object base=original!=null?original:template;
        if(base==null)return null; // Do not invent server upload quotas when no file configuration exists.
        int count=HotHelpers.getIntField(base,"c"),size=HotHelpers.getIntField(base,"d");
        if(count<=0||size<=0)return original;
        Set<String> extensions=new HashSet<String>();
        Object raw=HotHelpers.getObjectField(base,"e");
        if(raw instanceof Set)for(Object ext:(Set<?>)raw)if(ext instanceof String)extensions.add((String)ext);
        extensions.add("jpg");extensions.add("jpeg");extensions.add("png");extensions.add("webp");
        // Copy instead of changing a shared FileFeature used by default/expert models.
        return HotHelpers.newInstance(fileClass,255,
            HotHelpers.getIntField(base,"a"),HotHelpers.getIntField(base,"b"),count,size,extensions,
            HotHelpers.getBooleanField(base,"f"),true,true);
    }
    private static void applyAll(boolean on)throws Exception {
        for(int i=models.size()-1;i>=0;i--){
            State s=models.get(i);Object model=s.model.get();if(model==null){models.remove(i);continue;}
            if(on&&s.replacement==null)s.replacement=makeFiles(s.files);
            enabled.set(model,on?Boolean.TRUE:s.enabled);switchable.set(model,on?Boolean.TRUE:s.switchable);
            files.set(model,on&&s.replacement!=null?s.replacement:s.files);
        }
    }
    public static synchronized void install(ClassLoader loader){
        if(installed)return;
        List<HotUnhook> hooks=new ArrayList<HotUnhook>();
        try{
            Class<?> model=HotHelpers.findClass("v56",loader);fileClass=HotHelpers.findClass("a56",loader);
            type=field(model,"a");enabled=field(model,"f");switchable=field(model,"g");files=field(model,"m");
            if(type.getType()!=String.class||enabled.getType()!=boolean.class||switchable.getType()!=boolean.class||files.getType()!=fileClass||field(fileClass,"g").getType()!=boolean.class)throw new IllegalStateException("ModelConfig signature mismatch");
            fileClass.getDeclaredConstructor(int.class,int.class,int.class,int.class,int.class,Set.class,boolean.class,boolean.class,boolean.class);
            hooks.addAll(HotBridge.hookAllConstructors(model,new HotHook(){protected void afterHookedMethod(HotParam p){if(p.hasThrowable())return;try{capture(p.thisObject);}catch(Throwable t){log(t);}}}));
            hooks.add(HotHelpers.findAndHookMethod(model,"a",boolean.class,HotHelpers.findClass("ey3",loader),new HotHook(){protected void beforeHookedMethod(HotParam p){try{capture(p.thisObject);}catch(Throwable t){log(t);}}}));
            hooks.add(HotHelpers.findAndHookMethod(HotHelpers.findClass("w46",loader),"e",HotHelpers.findClass("gp2",loader),new HotHook(){protected void afterHookedMethod(HotParam p){if(p.hasThrowable())return;try{capture(p.getResult());}catch(Throwable t){log(t);}}}));
            installed=true;HotBridge.log("[DEKK][识图] 2.5.1 vision 模型 Hook 已安装");
        }catch(Throwable t){for(HotUnhook h:hooks)h.unhook();log(t);}
    }
}
