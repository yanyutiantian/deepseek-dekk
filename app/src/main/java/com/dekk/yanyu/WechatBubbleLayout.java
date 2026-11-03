package com.dekk.yanyu;

import android.graphics.*;
import java.lang.reflect.*;
import java.util.*;

/** Conversation bubble layout without avatars; DeepSeek 2.5.1 bindings. */
final class WechatBubbleLayout {
    private final ClassLoader loader;
    private final Object empty,unit;
    private interface Body {Object run(Object[] args)throws Throwable;}
    WechatBubbleLayout(ClassLoader l){loader=l;empty=HotHelpers.getStaticObjectField(type("z66"),"a");unit=HotHelpers.getStaticObjectField(type("lp9"),"a");}
    private Class<?> type(String n){return HotHelpers.findClass(n,loader);}
    private Object stat(String c,String m,Object... args){return HotHelpers.callStaticMethod(type(c),m,args);}
    private Object fn(String iface,final Body body){return Proxy.newProxyInstance(loader,new Class[]{type(iface)},new InvocationHandler(){public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
        if(method.getDeclaringClass()==Object.class){if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);if(method.getName().equals("equals"))return proxy==args[0];return "DEKK.WechatBubble";}
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
    // 记录实际布局 Element，而非整个链。then/padding 会创建新链，但保留其中的 Element。
    private final Set<Object> rowElements=Collections.newSetFromMap(new WeakHashMap<Object,Boolean>());
    private boolean containsRow(Object modifier){
        Object found=HotHelpers.callMethod(modifier,"X",fn("wv3",new Body(){public Object run(Object[] a){
            if(Boolean.TRUE.equals(a[0]))return Boolean.TRUE;
            synchronized(rowElements){return Boolean.valueOf(rowElements.contains(a[1]));}
        }}),Boolean.FALSE);
        return Boolean.TRUE.equals(found);
    }
    Object row(Object modifier,final boolean user){
        if(modifier==null)modifier=empty;
        synchronized(decorated){if(decorated.contains(modifier))return modifier;}
        if(containsRow(modifier))return modifier;
        // 头像位在"测量这一刻"决定，并把结果记下来给绘制阶段用：
        // 两边必须一致，否则会出现"没占位却把头像画到气泡上"或"占了位却是空的"。
        final boolean[] hasAvatar={false};
        final int[] avatarMetrics={0,0}; // [0]=头像边长(px) [1]=预留宽度(px)
        Object layout=fn("xv3",new Body(){public Object run(Object[] a){
            final Object scope=a[0],measurable=a[1];long constraints=HotHelpers.getLongField(a[2],"a");
            int maxW=(Integer)stat("cc2","i",constraints),maxH=(Integer)stat("cc2","h",constraints),minH=(Integer)stat("cc2","j",constraints),minW=(Integer)stat("cc2","k",constraints);
            int intrinsic=maxW;try{intrinsic=(Integer)HotHelpers.callMethod(measurable,"o",maxH);}catch(Throwable ignored){}
            float d=density();int screen=HotApp.currentApplication().getResources().getConfiguration().screenWidthDp;
            int avatarPx=0,reserve=0;
            try{
                android.content.Context app=HotApp.currentApplication();
                if(app!=null&&ChatAvatarStore.bitmap(app,user)!=null){
                    avatarPx=Math.round(BubbleStyle.avatarSizeDp()*d);
                    reserve=avatarPx+Math.round(BubbleStyle.avatarGapDp()*d);
                }
            }catch(Throwable ignored){}
            hasAvatar[0]=reserve>0;avatarMetrics[0]=avatarPx;avatarMetrics[1]=reserve;
            int limit=BubbleStyle.width(maxW,intrinsic,d,screen,user);
            long child=(Long)stat("cc2","a",0,limit,minH,maxH);
            final Object placeable=HotHelpers.callMethod(measurable,"r",child);
            int measured=HotHelpers.getIntField(placeable,"a"),height=HotHelpers.getIntField(placeable,"b");
            final int width=Math.min(maxW,Math.max(minW,measured)),childWidth=measured;
            // 头像空间由绘制层内侧的 padding 实际测量并移动内容，不能重复加偏移。
            final int childX=user?width-childWidth:0;
            height=Math.min(maxH,Math.max(height,avatarPx));
            Object placement=fn("sv3",new Body(){public Object run(Object[] args){HotHelpers.callMethod(args[0],"g",placeable,childX,0,0f);return unit;}});
            return HotHelpers.callMethod(scope,"x0",width,height,Collections.emptyMap(),placement);
        }});
        final Object rowElement=stat("a18","J",empty,layout);
        synchronized(rowElements){rowElements.add(rowElement);}
        Object chain=HotHelpers.callMethod(modifier,"r",rowElement);
        // 气泡背景与头像都挂在 row 的绘制层：头像永远画在最上层，不被气泡背景盖住
        Object draw=fn("sv3",new Body(){public Object run(Object[] a){
            Object scope=a[0];
            try{
                Object params=HotHelpers.getObjectField(HotHelpers.getObjectField(scope,"a"),"a");
                Canvas canvas=(Canvas)HotHelpers.getObjectField(HotHelpers.getObjectField(params,"c"),"a");long size=HotHelpers.getLongField(params,"d");
                float w=Float.intBitsToFloat((int)(size>>>32)),h=Float.intBitsToFloat((int)size);
                float radius=BubbleStyle.radius()*density();
                boolean has=hasAvatar[0];
                int avatarPx=avatarMetrics[0],reserve=avatarMetrics[1];
                if(!user){
                    float left=has?reserve:0f;
                    Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(BubbleStyle.assistantColor());
                    canvas.drawRoundRect(new RectF(left,0,w,h),radius,radius,paint);
                    float stroke=Math.min(density(),Math.min(w-left,h)/2f);
                    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setColor(0xFFD8D8D8);
                    canvas.drawRoundRect(new RectF(left+stroke/2,stroke/2,w-stroke/2,h-stroke/2),radius,radius,paint);
                }
                if(has){
                    Bitmap bitmap=ChatAvatarStore.bitmap(HotApp.currentApplication(),user);
                    if(bitmap!=null){
                        float x=user?w-avatarPx:0f,y=0f;
                        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                        canvas.drawBitmap(bitmap,null,new RectF(x,y,x+avatarPx,y+avatarPx),paint);
                    }
                }
            }catch(Throwable error){HotBridge.log("[DEKK][气泡] 气泡绘制失败: "+error.getClass().getSimpleName());}
            finally{HotHelpers.callMethod(scope,"a");}
            return unit;
        }});
        chain=stat("ru0","y",chain,draw);
        // ilke row: layout → decoration → 头像侧 padding → 正文 padding。
        boolean avatarPresent=ChatAvatarStore.bitmap(HotApp.currentApplication(),user)!=null;
        float inset=avatarPresent?BubbleStyle.avatarSizeDp()+BubbleStyle.avatarGapDp():0f;
        chain=stat("jt5","I",chain,user?0f:inset,0f,user?inset:0f,0f);
        if(!user)chain=stat("jt5","I",chain,16f,8.5f,16f,8.5f);
        synchronized(decorated){decorated.add(chain);}return chain;
    }
}