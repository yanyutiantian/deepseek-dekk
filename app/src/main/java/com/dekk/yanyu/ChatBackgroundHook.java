package com.dekk.yanyu;

import android.content.Context;
import android.graphics.*;

/** DeepSeek 2.5.1: zc.w -> pd5 Scaffold -> sz8 Surface -> se0 -> te0.
 * Tag only the background element created directly by ChatPage, never global colors.
 */
public final class ChatBackgroundHook {
    // se0 overrides equals/hashCode by color/shape. Xposed additional-instance
    // fields use equals-based weak keys: identical white backgrounds share tags!
    // Keep tags by referential identity only, without changing host equality.
    private static final java.util.List<java.lang.ref.WeakReference<Object>> tagged = new java.util.ArrayList<java.lang.ref.WeakReference<Object>>();
    private static synchronized boolean isTagged(Object object){
        boolean found=false;
        for(int i=tagged.size()-1;i>=0;i--){Object value=tagged.get(i).get();if(value==null)tagged.remove(i);else if(value==object)found=true;}
        return found;
    }
    private static synchronized void tag(Object object,boolean enabled){
        for(int i=tagged.size()-1;i>=0;i--){Object value=tagged.get(i).get();if(value==null||value==object)tagged.remove(i);}
        if(enabled&&object!=null){tagged.add(new java.lang.ref.WeakReference<Object>(object));tagSeen++;}
    }

    /** 聊天页判断（带缓存）：聊天页才有输入框。 */
    private static boolean chatPage(){
        long now=android.os.SystemClock.uptimeMillis();
        if(now-chatCheckedAt>1000L){
            chatCheckedAt=now;
            try{
                android.app.Activity activity=DekkSettingsPage.currentActivity();
                chatActive=activity!=null&&MusicSend.hasComposer(activity);
            }catch(Throwable t){chatActive=false;}
        }
        return chatActive;
    }

    /** 把壁纸画到给定节点的画布上；成功返回 true。 */
    private static boolean paintWallpaper(HotParam p){
        Bitmap bitmap=image;
        if(bitmap==null||bitmap.isRecycled())return false;
        try{
            Object scope=p.args[0];
            Object params=HotHelpers.getObjectField(HotHelpers.getObjectField(scope,"a"),"a");
            Object wrapper=HotHelpers.getObjectField(params,"c");
            Canvas canvas=(Canvas)HotHelpers.getObjectField(wrapper,"a");
            long size=HotHelpers.getLongField(params,"d");
            float w=Float.intBitsToFloat((int)(size>>>32)),h=Float.intBitsToFloat((int)size);
            if(!(w>0&&h>0)||Float.isInfinite(w)||Float.isInfinite(h))return false;
            float scale=Math.max(w/bitmap.getWidth(),h/bitmap.getHeight());
            float dw=bitmap.getWidth()*scale,dh=bitmap.getHeight()*scale;
            int save=canvas.save();
            try{
                canvas.clipRect(0,0,w,h);
                int percent=alphaPercent;
                if(percent<=0)return true;   // 全透明：直接不画
                PAINT.setAlpha(Math.round(255f*percent/100f));
                canvas.drawBitmap(bitmap,null,new RectF((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2),PAINT);
            }finally{canvas.restoreToCount(save);}
            return true;
        }catch(Throwable e){
            if(!reported){reported=true;HotBridge.log("[DEKK][背景] 绘制适配失败，保留原背景："+e);}
            return false;
        }
    }

    /** 兜底：节点够大（≈整屏宽 + 大半屏高）且当前在聊天页时，直接在它这层画壁纸。 */
    /** 兜底：任何「没被打上标记」的大聊天节点都直接画壁纸。
     *  为什么要逐节点兜底：从最近任务回来时宿主会重建 Surface，
     *  那时打标记依赖的「正处于聊天帧内」不成立 → 独立壁纸层拿不到标记 → 画面空白；
     *  以前只有 3.5 秒探测失败才启用兜底，所以这种情况会一直不显示，
     *  直到用户点输入框触发一次正常重组才又出来。 */
    private static boolean paintFallback(HotParam p){
        if(image==null||image.isRecycled())return false;
        if(!chatPage())return false;
        try{
            android.app.Activity activity=DekkSettingsPage.currentActivity();
            if(activity==null)return false;
            Object scope=p.args[0];
            Object params=HotHelpers.getObjectField(HotHelpers.getObjectField(scope,"a"),"a");
            long size=HotHelpers.getLongField(params,"d");
            float w=Float.intBitsToFloat((int)(size>>>32)),h=Float.intBitsToFloat((int)size);
            android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
            if(w<metrics.widthPixels*0.9f||h<metrics.heightPixels*0.45f)return false;
        }catch(Throwable t){
            return false;
        }
        if(!fallbackLogged){
            fallbackLogged=true;
            HotBridge.log("[DEKK][背景] 节点未命中独立壁纸层，改用聊天区直绘（逐节点兜底）");
        }
        return paintWallpaper(p);
    }
    private static volatile Bitmap image;
    private static volatile String requested="";
    private static boolean loading;
    private static long retryAt;
    private static int generation;
    private static boolean installed, reported;
    /** 兜底生效时只提示一次。 */
    private static boolean fallbackLogged;
    private static int tagSeen;
    /** 聊天页判断缓存，避免每帧都遍历视图树。 */
    private static long chatCheckedAt;
    private static boolean chatActive;
    private static Class<?> invalidator;
    private static final java.util.Map<Object,Boolean> nodes=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object,Boolean>());
    private static void invalidateNodes(){new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable(){public void run(){Object[] all; synchronized(nodes){all=nodes.keySet().toArray();}for(Object n:all)try{HotHelpers.callStaticMethod(invalidator,"M",n);}catch(Throwable ignored){}}});}
    private static final Paint PAINT=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    /** 壁纸透明度（0-100），绘制前同步到 Paint。 */
    private static volatile int alphaPercent=100;
    /** 用户拖动透明度滑块后立即重绘聊天区。 */
    public static void applyAlpha(Context context){
        alphaPercent=BeautifyStore.alpha(context);
        invalidateNodes();
        repaintChat();
    }

    /**
     * 让整个聊天页面重绘一次。
     *
     * <p>只靠 {@link #invalidateNodes()} 不够：走「逐节点兜底直绘」时节点压根没被打上标记，
     * 于是拖动透明度滑块后画面不变（用户反馈"不能调节壁纸透明度"就是这个原因）。
     */
    private static void repaintChat(){
        try{
            final android.app.Activity activity=DekkSettingsPage.currentActivity();
            if(activity==null)return;
            final android.view.ViewGroup root=(android.view.ViewGroup)activity.getWindow().getDecorView();
            root.post(new Runnable(){public void run(){repaint(root);}});
        }catch(Throwable ignored){ }
    }

    private static void repaint(android.view.View view){
        try{view.invalidate();}catch(Throwable ignored){ }
        if(view instanceof android.view.ViewGroup){
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){
                repaint(group.getChildAt(i));
            }
        }
    }
    private static int depth(ThreadLocal<Integer> scope){Integer n=scope.get();return n==null?0:n;}
    /** 全局计数：Compose 的重组可能发生在 recomposer 线程上，光看 ThreadLocal 会漏。 */
    private static final java.util.concurrent.atomic.AtomicInteger chatGlobal=new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger scaffoldGlobal=new java.util.concurrent.atomic.AtomicInteger();
    private static HotHook depthHook(final ThreadLocal<Integer> scope,final java.util.concurrent.atomic.AtomicInteger global){return new HotHook(){
        protected void beforeHookedMethod(HotParam p){scope.set(depth(scope)+1);global.incrementAndGet();}
        protected void afterHookedMethod(HotParam p){int n=depth(scope)-1;if(n<=0)scope.remove();else scope.set(n);global.decrementAndGet();}
    };}
    private ChatBackgroundHook(){}
    /** 文件名不变时也强制失效旧图片及正在进行的解码。 */
    public static synchronized void reload(Context context){
        generation++;requested="";image=null;loading=false;retryAt=0;
        refresh(context);
    }
    public static synchronized void refresh(final Context context){
        alphaPercent=BeautifyStore.alpha(context);
        final String stored=BeautifyStore.get(context);
        // 旧版本放在 Pictures/dekk-wallpapers/ 下，这里迁移到宿主私有 files/ 目录
        final String path=WallpaperFile.ensure(context,stored);
        if(!path.equals(stored))BeautifyStore.setPath(context,path);
        if(path.equals(requested)&&(path.length()==0||image!=null||loading||android.os.SystemClock.uptimeMillis()<retryAt))return;
        final int ticket=++generation;
        requested=path;image=null;loading=path.length()>0;retryAt=0;invalidateNodes();
        if(path.length()==0)return;
        new Thread(new Runnable(){public void run(){
            Bitmap decoded=null;
            try{
                BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,o);
                if(o.outWidth<=0||o.outHeight<=0)return;
                o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>2048)o.inSampleSize*=2;
                o.inJustDecodeBounds=false;o.inPreferredConfig=Bitmap.Config.RGB_565;
                decoded=BitmapFactory.decodeFile(path,o);
                synchronized(ChatBackgroundHook.class){if(ticket==generation){image=decoded;decoded=null;invalidateNodes();}}
            }catch(Throwable e){HotBridge.log("[DEKK][背景] 图片读取失败："+e.getClass().getSimpleName());}
            finally{
                synchronized(ChatBackgroundHook.class){if(ticket==generation){loading=false;if(image==null)retryAt=android.os.SystemClock.uptimeMillis()+5000;}}
                if(decoded!=null)decoded.recycle();
            }
        }},"DEKK-background-load").start();
    }
    public static synchronized void install(ClassLoader loader){
        if(installed)return;
        final java.util.List<HotUnhook> hooks=new java.util.ArrayList<HotUnhook>();
        try{
            invalidator=HotHelpers.findClass("yya",loader); Class<?> element=HotHelpers.findClass("se0",loader);
            Class<?> node=HotHelpers.findClass("te0",loader);
            hooks.add(HotHelpers.findAndHookMethod(node,"k0",HotHelpers.findClass("da5",loader),new HotHook(){
                protected void beforeHookedMethod(HotParam p)throws Throwable{
                    // Input materials and message transforms have their own draw hooks.
                    // r is a Shape, not a white color; never use it to select backgrounds.
                    if(ChatEffectsHook.ownsNode(p.thisObject))return;
                    Context c=HotApp.currentApplication();if(c==null)return;refresh(c);
                    boolean taggedNode=isTagged(p.thisObject);
                    if(!taggedNode){
                        // 宿主结构变了（独立壁纸层没命中）时的兜底：直接在聊天区大节点上画壁纸
                        if(!paintFallback(p))return;
                        nodes.put(p.thisObject,Boolean.TRUE);
                        p.setResult(null);
                        HotHelpers.callMethod(p.args[0],"a");
                        return;
                    }
                    nodes.put(p.thisObject,Boolean.TRUE);
                    if(!paintWallpaper(p))return;
                    // k0 ends in da5.a(): drawContent. Skip only its background paint.
                    // Mark handled before invoking content to avoid a second content pass on exceptions.
                    p.setResult(null);
                    HotHelpers.callMethod(p.args[0],"a");
                }
            }));
            hooks.add(HotHelpers.findAndHookMethod(element,"b",new HotHook(){
                protected void afterHookedMethod(HotParam p){if(p.getResult()!=null)tag(p.getResult(),isTagged(p.thisObject));}
            }));
            hooks.add(HotHelpers.findAndHookMethod(element,"c",HotHelpers.findClass("b76",loader),new HotHook(){
                protected void afterHookedMethod(HotParam p){tag(p.args[0],isTagged(p.thisObject));}
            }));
            // Ensure Compose updates nodes crossing the chat/non-chat boundary.
            hooks.add(HotHelpers.findAndHookMethod(element,"equals",Object.class,new HotHook(){
                protected void beforeHookedMethod(HotParam p){
                    if(isTagged(p.thisObject)!=isTagged(p.args[0]))p.setResult(false);
                }
            }));
            // Explicit composition scopes replace stack-trace matching. Only the chat
            // Scaffold's Surface receives a dedicated inner wallpaper modifier.
            final ThreadLocal<Integer> chatDepth=new ThreadLocal<Integer>();
            final ThreadLocal<Integer> scaffoldDepth=new ThreadLocal<Integer>();
            hooks.addAll(HotBridge.hookAllMethods(HotHelpers.findClass("zc",loader),"w",depthHook(chatDepth,chatGlobal)));
            hooks.addAll(HotBridge.hookAllMethods(HotHelpers.findClass("pd5",loader),"a",depthHook(scaffoldDepth,scaffoldGlobal)));
            final ThreadLocal<Boolean> drawingSurface=new ThreadLocal<Boolean>();
            Class<?> surfaceLambda=HotHelpers.findClass("sz8",loader);
            hooks.addAll(HotBridge.hookAllConstructors(surfaceLambda,new HotHook(){
                protected void afterHookedMethod(HotParam p){
                    boolean inFrame=depth(chatDepth)>0&&depth(scaffoldDepth)>0;
                    // 重组可能不在同一个线程上：只要有线程正处在聊天 Scaffold 的构建帧里就算命中
                    boolean composing=chatGlobal.get()>0&&scaffoldGlobal.get()>0;
                    tag(p.thisObject,inFrame||composing);
                }
            }));
            hooks.add(HotHelpers.findAndHookMethod(surfaceLambda,"q",Object.class,Object.class,new HotHook(){
                protected void beforeHookedMethod(HotParam p){p.setObjectExtra("wallpaper.previous",drawingSurface.get());drawingSurface.set(isTagged(p.thisObject));}
                protected void afterHookedMethod(HotParam p){Boolean old=(Boolean)p.getObjectExtra("wallpaper.previous");if(old==null)drawingSurface.remove();else drawingSurface.set(old);}
            }));
            final Class<?> backgroundElement=element;
            final Object rectangle=HotHelpers.getStaticObjectField(HotHelpers.findClass("zc",loader),"o");
            hooks.add(HotHelpers.findAndHookMethod(HotHelpers.findClass("vz8",loader),"c",
                HotHelpers.findClass("c76",loader),HotHelpers.findClass("md8",loader),long.class,
                HotHelpers.findClass("zl0",loader),float.class,new HotHook(){
                    protected void afterHookedMethod(HotParam p){
                        if(p.hasThrowable()||p.getResult()==null||!Boolean.TRUE.equals(drawingSurface.get()))return;
                        drawingSurface.set(false); // Only the Surface's first factory call, never descendants.
                        try{
                            Object wallpaper=HotHelpers.newInstance(backgroundElement,0L,null,rectangle,2);
                            tag(wallpaper,true);
                            p.setResult(HotHelpers.callMethod(p.getResult(),"r",wallpaper));
                        }catch(Throwable t){HotBridge.log("[DEKK][背景] 独立壁纸层创建失败: "+t);}
                    }
                }));
            installed=true;HotBridge.log("[DEKK][背景] 独立壁纸层 Hook 已安装");
        }catch(Throwable e){for(HotUnhook h:hooks)h.unhook();HotBridge.log("[DEKK][背景] 版本不匹配，保留原背景："+e);}
    }
}