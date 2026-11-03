package com.dekk.yanyu;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.*;

/** Version-specific draw nodes, independent of the chat background hook. */
public final class ChatEffectsHook {
    private static final List<Entry> entries=new ArrayList<Entry>();
    private static final Map<String,Effect> seen=new LinkedHashMap<String,Effect>();
    private static final android.os.Handler frames=new android.os.Handler(android.os.Looper.getMainLooper());
    private static Class<?> element, invalidator;
    private static Object rectangle;
    private static long sendUntil;
    private static boolean installed;
    private static final float[] composerOrigin=new float[2], sendOrigin=new float[2];
    private static boolean hasComposer,hasSendOrigin;
    private static final class Effect { int kind; long start; boolean outgoing; float dx,dy; boolean positioned; Effect(int k,long t){kind=k;start=t;} }
    private static final class Entry { WeakReference<Object> object; Effect effect; Entry(Object o,Effect e){object=new WeakReference<Object>(o);effect=e;} }
    private static synchronized Effect get(Object o){for(int i=entries.size()-1;i>=0;i--){Object x=entries.get(i).object.get();if(x==null)entries.remove(i);else if(x==o)return entries.get(i).effect;}return null;}
    private static synchronized void put(Object o,Effect e){for(int i=entries.size()-1;i>=0;i--){Object x=entries.get(i).object.get();if(x==null||x==o)entries.remove(i);}if(e!=null)entries.add(new Entry(o,e));}
    static boolean ownsNode(Object node){return get(node)!=null;}
    public static void install(final ClassLoader loader){
        if(installed)return;installed=true;
        final List<HotUnhook> hooks=new ArrayList<HotUnhook>();
        try{
            element=HotHelpers.findClass("se0",loader);invalidator=HotHelpers.findClass("yya",loader);
            rectangle=HotHelpers.getStaticObjectField(HotHelpers.findClass("zc",loader),"o");
            hooks.addAll(HotBridge.hookAllMethods(HotHelpers.findClass("nb5",loader),"J",new HotHook(){protected void beforeHookedMethod(HotParam p){sendUntil=SystemClock.uptimeMillis()+120000;hasSendOrigin=hasComposer;System.arraycopy(composerOrigin,0,sendOrigin,0,2);}}));
            installCell(loader,"vs9",10,hooks);
            hooks.addAll(HotBridge.hookAllConstructors(element,new HotHook(){protected void afterHookedMethod(HotParam p){
                // dm0.n has a rounded input background and a separate rectangular footer.
                if(p.args.length<3||p.args[2]==rectangle)return;
                for(StackTraceElement f:Thread.currentThread().getStackTrace())if(f.getClassName().equals("dm0")&&f.getMethodName().equals("n")){put(p.thisObject,new Effect(2,0));break;}
            }}));
            hooks.add(HotHelpers.findAndHookMethod(element,"b",new HotHook(){protected void afterHookedMethod(HotParam p){if(p.getResult()!=null)put(p.getResult(),get(p.thisObject));}}));
            hooks.add(HotHelpers.findAndHookMethod(element,"c",HotHelpers.findClass("b76",loader),new HotHook(){protected void afterHookedMethod(HotParam p){put(p.args[0],get(p.thisObject));}}));
            hooks.add(HotHelpers.findAndHookMethod(element,"equals",Object.class,new HotHook(){protected void beforeHookedMethod(HotParam p){if(get(p.thisObject)!=get(p.args[0]))p.setResult(false);}}));
            hooks.add(HotHelpers.findAndHookMethod(HotHelpers.findClass("te0",loader),"k0",HotHelpers.findClass("da5",loader),new HotHook(){protected void beforeHookedMethod(HotParam p)throws Throwable{
                Effect e=get(p.thisObject);Context c=HotApp.currentApplication();if(e==null||c==null)return;
                if(e.kind==1&&!BeautifyStore.animations(c))return;
                Object scope=p.args[0];Object params=HotHelpers.getObjectField(HotHelpers.getObjectField(scope,"a"),"a");
                Canvas canvas=(Canvas)HotHelpers.getObjectField(HotHelpers.getObjectField(params,"c"),"a");long size=HotHelpers.getLongField(params,"d");
                float w=Float.intBitsToFloat((int)(size>>>32)),h=Float.intBitsToFloat((int)size);if(w<=0||h<=0)return;
                if(e.kind==2){Matrix matrix=new Matrix();canvas.getMatrix(matrix);float[] origin={w/2,h/2};matrix.mapPoints(origin);System.arraycopy(origin,0,composerOrigin,0,2);hasComposer=true;return;}
                int save=canvas.save();
                try{
                    if(e.kind==1){
                        if(e.start<0)e.start=SystemClock.uptimeMillis();
                        float t=Math.min(1f,(SystemClock.uptimeMillis()-e.start)/650f);
                        if(t>=1)return;
                        float progress=1-(float)Math.pow(1-t,3);
                        if(e.outgoing&&hasSendOrigin){
                            if(!e.positioned){Matrix matrix=new Matrix(),inverse=new Matrix();canvas.getMatrix(matrix);if(matrix.invert(inverse)){float[] start={sendOrigin[0],sendOrigin[1]};inverse.mapPoints(start);e.dx=start[0]-w/2;e.dy=start[1]-h/2;}e.positioned=true;}
                            canvas.translate(e.dx*(1-progress),e.dy*(1-progress));
                            float scale=.78f+.22f*progress;canvas.scale(scale,scale,w/2,h/2);
                        }else{
                            return; // No composer position: keep the host's original rendering.
                        }
                    }
                    p.setResult(null);HotHelpers.callMethod(scope,"a");
                }finally{canvas.restoreToCount(save);}
                if(e.kind==1){final WeakReference<Object> target=new WeakReference<Object>(p.thisObject);frames.postDelayed(new Runnable(){public void run(){Object n=target.get();if(n!=null)try{HotHelpers.callStaticMethod(invalidator,"M",n);}catch(Throwable ignored){}}},16);}
            }}));
        }catch(Throwable t){for(HotUnhook h:hooks)h.unhook();HotBridge.log("[DEKK][特效] 适配失败："+t);}
    }
    private static void installCell(ClassLoader loader,final String owner,final int modifier,List<HotUnhook> hooks){
        hooks.addAll(HotBridge.hookAllMethods(HotHelpers.findClass(owner,loader),"a",new HotHook(){protected void beforeHookedMethod(HotParam p){
            Context c=HotApp.currentApplication();if(c==null||!BeautifyStore.animations(c)||p.args.length<=modifier)return;
            long now=SystemClock.uptimeMillis();String key=owner+":"+System.identityHashCode(p.args[2]);Effect effect;
            synchronized(seen){effect=seen.get(key);if(effect==null){effect=new Effect(1,now<=sendUntil?-1:0);seen.put(key,effect);if(seen.size()>512)seen.remove(seen.keySet().iterator().next());}}
            if(effect.start==0||(effect.start>0&&now-effect.start>=650))return;
            effect.outgoing=owner.equals("vs9");
            Object node=HotHelpers.newInstance(element,0L,null,rectangle,2);put(node,effect);
            p.args[modifier]=HotHelpers.callMethod(node,"r",p.args[modifier]);
        }}));
    }
}