package com.dekk.yanyu;

import android.content.Context;
import android.graphics.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.lang.reflect.Method;

/** Independent text and thinking surfaces for DeepSeek 2.5.1. */
final class ChatBubbleHook {
    private static class Mark {WeakReference<Object> object;int kind;Mark(Object o,int k){object=new WeakReference<Object>(o);kind=k;}}
    private static final List<Mark> marks=new ArrayList<Mark>();
    private static Class<?> element,padding;
    private static Object rectangle;
    private static boolean installed;
    private static final ThreadLocal<Boolean> preview=new ThreadLocal<Boolean>();
    static boolean enabled(Context c){return c!=null&&c.getSharedPreferences("dekk_beautify",0).getBoolean("wechat_bubbles",false);}
    static void setEnabled(Context c,boolean value){c.getSharedPreferences("dekk_beautify",0).edit().putBoolean("wechat_bubbles",value).apply();}
    private static synchronized int kind(Object o){for(int i=marks.size()-1;i>=0;i--){Object v=marks.get(i).object.get();if(v==null)marks.remove(i);else if(v==o)return marks.get(i).kind;}return 0;}
    private static synchronized void mark(Object o,int k){for(int i=marks.size()-1;i>=0;i--){Object v=marks.get(i).object.get();if(v==null||v==o)marks.remove(i);}if(o!=null&&k!=0)marks.add(new Mark(o,k));}
    private static Object decorate(Object modifier,int k){
        Object node=HotHelpers.newInstance(element,0L,null,rectangle,2);mark(node,k);
        Object chain=HotHelpers.callMethod(modifier,"r",node);
        return HotHelpers.callStaticMethod(padding,"I",chain,k==1?12f:18f,10f,k==1?18f:12f,10f);
    }
    private static void component(ClassLoader loader,String owner,String name,final int index,final int k,List<HotUnhook> hooks)throws Exception {
        Class<?> modifier=HotHelpers.findClass("c76",loader);boolean found=false;
        for(Method m:HotHelpers.findClass(owner,loader).getDeclaredMethods()){
            if(!m.getName().equals(name)||m.getParameterTypes().length<=index||m.getParameterTypes()[index]!=modifier)continue;
            hooks.add(HotBridge.hookMethod(m,new HotHook(){protected void beforeHookedMethod(HotParam p){if(!enabled(HotApp.currentApplication())||p.args[index]==null)return;try{p.args[index]=decorate(p.args[index],k);}catch(Throwable e){HotBridge.log("[DEKK][气泡] 组件注入失败: "+e.getClass().getSimpleName());}}}));found=true;
        }
        if(!found)throw new NoSuchMethodException(owner+"."+name);
    }
    static synchronized void install(ClassLoader loader){
        if(installed)return;List<HotUnhook> hooks=new ArrayList<HotUnhook>();
        try{
            element=HotHelpers.findClass("se0",loader);padding=HotHelpers.findClass("jt5",loader);rectangle=HotHelpers.getStaticObjectField(HotHelpers.findClass("zc",loader),"o");
            final WechatBubbleLayout layout=new WechatBubbleLayout(loader);
            hooks.add(HotHelpers.findAndHookMethod(HotHelpers.findClass("zl9",loader),"S",
                HotHelpers.findClass("t62",loader),HotHelpers.findClass("c76",loader),HotHelpers.findClass("c76",loader),
                HotHelpers.findClass("wv3",loader),HotHelpers.findClass("wv3",loader),HotHelpers.findClass("ey3",loader),int.class,new HotHook(){
                    protected void beforeHookedMethod(HotParam p){if(!enabled(HotApp.currentApplication()))return;
                        try{Object row=layout.row(p.args[1],true),surface=layout.userSurface(p.args[2]);p.args[1]=row;p.args[2]=surface;p.args[6]=((Integer)p.args[6])&~0x3f0;}
                        catch(Throwable t){HotBridge.log("[DEKK][气泡] 用户气泡失败: "+t);}
                    }
                }));
            for(Method method:HotHelpers.findClass("ru0",loader).getDeclaredMethods())if(method.getName().equals("c")&&method.getParameterTypes().length==14){
                hooks.add(HotBridge.hookMethod(method,new HotHook(){protected void beforeHookedMethod(HotParam p){if(!enabled(HotApp.currentApplication()))return;
                    try{p.args[10]=layout.row(p.args[10],false);p.args[13]=((Integer)p.args[13])&~0xe;}
                    catch(Throwable t){HotBridge.log("[DEKK][气泡] 助手气泡失败: "+t);}
                }}));
            }
            component(loader,"ob2","c",4,3,hooks);
            // Collapsed thinking preview builds its Modifier internally, without a Modifier argument.
            hooks.add(HotHelpers.findAndHookMethod(HotHelpers.findClass("sga",loader),"l",
                int.class,HotHelpers.findClass("sv3",loader),HotHelpers.findClass("wv3",loader),
                HotHelpers.findClass("ey3",loader),int.class,new HotHook(){
                    protected void beforeHookedMethod(HotParam p){p.setObjectExtra("bubble.preview",preview.get());preview.set(enabled(HotApp.currentApplication()));}
                    protected void afterHookedMethod(HotParam p){Boolean old=(Boolean)p.getObjectExtra("bubble.preview");if(old==null)preview.remove();else preview.set(old);}
                }));
            hooks.add(HotHelpers.findAndHookMethod(HotHelpers.findClass("lx0",loader),"T",
                HotHelpers.findClass("ey3",loader),HotHelpers.findClass("c76",loader),new HotHook(){
                    protected void beforeHookedMethod(HotParam p){
                        if(!Boolean.TRUE.equals(preview.get()))return;preview.set(false);
                        try{p.args[1]=decorate(p.args[1],3);}catch(Throwable e){HotBridge.log("[DEKK][气泡] 思考标题适配失败");}
                    }
                }));
            hooks.add(HotHelpers.findAndHookMethod(element,"b",new HotHook(){protected void afterHookedMethod(HotParam p){mark(p.getResult(),kind(p.thisObject));}}));
            hooks.add(HotHelpers.findAndHookMethod(element,"c",HotHelpers.findClass("b76",loader),new HotHook(){protected void afterHookedMethod(HotParam p){mark(p.args[0],kind(p.thisObject));}}));
            hooks.add(HotHelpers.findAndHookMethod(element,"equals",Object.class,new HotHook(){protected void beforeHookedMethod(HotParam p){if(kind(p.thisObject)!=kind(p.args[0]))p.setResult(false);}}));
            hooks.add(HotHelpers.findAndHookMethod(HotHelpers.findClass("te0",loader),"k0",HotHelpers.findClass("da5",loader),new HotHook(){protected void beforeHookedMethod(HotParam p){
                int k=kind(p.thisObject);Context c=HotApp.currentApplication();if(k==0||!enabled(c))return;
                try{
                    Object scope=p.args[0],params=HotHelpers.getObjectField(HotHelpers.getObjectField(scope,"a"),"a");
                    Canvas canvas=(Canvas)HotHelpers.getObjectField(HotHelpers.getObjectField(params,"c"),"a");long size=HotHelpers.getLongField(params,"d");float w=Float.intBitsToFloat((int)(size>>>32)),h=Float.intBitsToFloat((int)size);
                    if(!(w>0&&h>0)||Float.isInfinite(w)||Float.isInfinite(h))return;
                    float d=c.getResources().getDisplayMetrics().density;boolean dark=(c.getResources().getConfiguration().uiMode&48)==32;
                    BubbleShape.draw(canvas,w,h,d,k,dark);
                }catch(Throwable e){HotBridge.log("[DEKK][气泡] 绘制失败: "+e.getClass().getSimpleName());return;}
                p.setResult(null);HotHelpers.callMethod(p.args[0],"a");
            }}));
            installed=true;HotBridge.log("[DEKK][气泡] 2.5.1 用户/正文/思考独立节点已安装");
        }catch(Throwable e){for(HotUnhook h:hooks)h.unhook();HotBridge.log("[DEKK][气泡] 安装失败: "+e);}
    }
}