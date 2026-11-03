package com.dekk.yanyu;

import android.graphics.*;
import java.lang.reflect.*;
import java.util.*;

/** Java reconstruction of ilke ConversationLayout/ConversationDrawing, no avatars; 2.5.1 bindings. */
final class IlkeBubbleLayout {
    private final ClassLoader loader;
    private final Object empty,unit;
    private interface Body {Object run(Object[] args)throws Throwable;}
    IlkeBubbleLayout(ClassLoader l){loader=l;empty=HotHelpers.getStaticObjectField(type("z66"),"a");unit=HotHelpers.getStaticObjectField(type("lp9"),"a");}
    private Class<?> type(String n){return HotHelpers.findClass(n,loader);}
    private Object stat(String c,String m,Object... args){return HotHelpers.callStaticMethod(type(c),m,args);}
    private Object fn(String iface,final Body body){return Proxy.newProxyInstance(loader,new Class[]{type(iface)},new InvocationHandler(){public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
        if(method.getDeclaringClass()==Object.class){if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);if(method.getName().equals("equals"))return proxy==args[0];return "DEKK.IlkeBubble";}
        return body.run(args);
    }});}
    private float density(){return HotApp.currentApplication().getResources().getDisplayMetrics().density;}
    private Object shape(){return stat("tt7","b",(float)BubbleStyle.radius());}
    Object userSurface(Object modifier){
        final Object rounded=shape();
        Object fold=fn("wv3",new Body(){public Object run(Object[] a){
            Object item=a[1];String name=item.getClass().getName();
            if(name.equals("se0"))return stat("tia","v",a[0],((long)BubbleStyle.userColor()&0xffffffffL)<<32,rounded);
            if(name.equals("o24")&&HotHelpers.getObjectField(item,"g")==HotHelpers.getStaticObjectField(type("kd1"),"s"))return stat("u9a","A",a[0],rounded);
            return HotHelpers.callMethod(a[0],"r",item);
        }});
        return HotHelpers.callMethod(modifier,"X",fold,empty);
    }
    private final Set<Object> decorated=Collections.newSetFromMap(new WeakHashMap<Object,Boolean>());
    Object row(Object modifier,final boolean user){
        if(modifier==null)modifier=empty;
        synchronized(decorated){if(decorated.contains(modifier))return modifier;}
        Object layout=fn("xv3",new Body(){public Object run(Object[] a){
            final Object scope=a[0],measurable=a[1];long constraints=HotHelpers.getLongField(a[2],"a");
            int maxW=(Integer)stat("cc2","i",constraints),maxH=(Integer)stat("cc2","h",constraints),minH=(Integer)stat("cc2","j",constraints),minW=(Integer)stat("cc2","k",constraints);
            int intrinsic=maxW;try{intrinsic=(Integer)HotHelpers.callMethod(measurable,"o",maxH);}catch(Throwable ignored){}
            float d=density();int screen=HotApp.currentApplication().getResources().getConfiguration().screenWidthDp;
            int limit=BubbleStyle.width(maxW,intrinsic,d,screen,user);
            long child=(Long)stat("cc2","a",0,limit,minH,maxH);
            final Object placeable=HotHelpers.callMethod(measurable,"r",child);
            int measured=HotHelpers.getIntField(placeable,"a"),height=HotHelpers.getIntField(placeable,"b");final int width=Math.max(minW,measured),childWidth=measured;
            Object placement=fn("sv3",new Body(){public Object run(Object[] args){HotHelpers.callMethod(args[0],"g",placeable,user?width-childWidth:0,0,0f);return unit;}});
            return HotHelpers.callMethod(scope,"x0",width,height,Collections.emptyMap(),placement);
        }});
        Object chain=stat("a18","J",modifier,layout);
        if(!user){
            Object draw=fn("sv3",new Body(){public Object run(Object[] a){
                Object scope=a[0];
                try{
                    Object params=HotHelpers.getObjectField(HotHelpers.getObjectField(scope,"a"),"a");
                    Canvas canvas=(Canvas)HotHelpers.getObjectField(HotHelpers.getObjectField(params,"c"),"a");long size=HotHelpers.getLongField(params,"d");
                    float w=Float.intBitsToFloat((int)(size>>>32)),h=Float.intBitsToFloat((int)size);float radius=BubbleStyle.radius()*density();
                    Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(BubbleStyle.assistantColor());canvas.drawRoundRect(new RectF(0,0,w,h),radius,radius,paint);
                }catch(Throwable error){HotBridge.log("[DEKK][ilke] 气泡绘制失败: "+error.getClass().getSimpleName());}
                finally{HotHelpers.callMethod(scope,"a");}
                return unit;
            }});
            chain=stat("ru0","y",chain,draw);chain=stat("jt5","I",chain,16f,8.5f,16f,8.5f);
        }
        synchronized(decorated){decorated.add(chain);}return chain;
    }
}