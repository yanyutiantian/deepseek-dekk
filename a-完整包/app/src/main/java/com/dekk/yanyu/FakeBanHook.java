package com.dekk.yanyu;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** 2.5.1: gy.i -> at9 -> dm0.y -> rn4.e, native muted banner. */
final class FakeBanHook {
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final List<Entry> entries=new ArrayList<Entry>();
    private static final ThreadLocal<Boolean> writing=new ThreadLocal<Boolean>();
    private static Class<?> statusClass;
    private static boolean installed;
    private static final class Entry {
        final WeakReference<Object> flow;
        Object real;
        boolean injected;
        Entry(Object f,Object value){flow=new WeakReference<Object>(f);real=value;}
    }
    private static final Runnable tick=new Runnable(){public void run(){refreshNow();}};
    private static Entry find(Object flow){for(Entry e:entries)if(e.flow.get()==flow)return e;return null;}
    private static Object simulated(Context c){return HotHelpers.newInstance(statusClass,Double.valueOf(FakeBanStore.until(c)/1000.0));}
    static void refresh(){main.removeCallbacks(tick);main.post(tick);}
    private static synchronized void track(Object flow){
        if(flow==null||find(flow)!=null)return;
        entries.add(new Entry(flow,HotHelpers.callMethod(flow,"getValue")));refresh();
    }
    private static synchronized void refreshNow(){
        main.removeCallbacks(tick);
        Context c=HotApp.currentApplication();boolean active=FakeBanStore.active(c);
        for(int i=entries.size()-1;i>=0;i--){
            Entry e=entries.get(i);Object flow=e.flow.get();if(flow==null){entries.remove(i);continue;}
            try{
                if(active||e.injected){writing.set(true);HotHelpers.callMethod(flow,"l",active?simulated(c):e.real);e.injected=active;}
            }catch(Throwable t){HotBridge.log("[DEKK][伪封号] 状态更新失败: "+t.getClass().getSimpleName());}
            finally{writing.remove();}
        }
        if(active)main.postDelayed(tick,Math.max(1,Math.min(1000,FakeBanStore.remaining(c))));
    }
    static synchronized void install(ClassLoader loader){
        if(installed)return;
        List<HotUnhook> hooks=new ArrayList<HotUnhook>();
        try{
            statusClass=HotHelpers.findClass("at9",loader);
            statusClass.getDeclaredConstructor(Double.class);
            hooks.add(HotHelpers.findAndHookMethod(HotHelpers.findClass("rs8",loader),"m",Object.class,Object.class,new HotHook(10000){
                protected void beforeHookedMethod(HotParam p){
                    // 【已移除】服务端下发禁言时自动重置数美指纹。
                    // 原因（实测）：数美设备 ID（sp "auc" 的 di）由服务端下发并在本地缓存，
                    // 删 .smtfb / sp "bd" 后重新初始化，服务端会凭硬件指纹再发回同一个 di
                    // （实测：puuid 与 seq/auc 归零，di 一个字节未变）。
                    // 因此重置无效；而 SmAntiFraud.destroy() 会触发重新采集与上报，
                    // 反而被数美记为「设备异常/反复重置」。这里只保留伪封号展示逻辑。
                    if(p.args==null||p.args.length<2)return;
                    if(Boolean.TRUE.equals(writing.get())||!statusClass.isInstance(p.args[1]))return;
                    synchronized(FakeBanHook.class){
                        Entry e=find(p.thisObject);if(e==null)return;
                        // Respect failed compare-and-set operations; only retain accepted server updates.
                        if(p.args[0]!=null){Object current=HotHelpers.callMethod(p.thisObject,"getValue");if(!p.args[0].equals(current))return;}
                        e.real=p.args[1];Context c=HotApp.currentApplication();
                        if(FakeBanStore.active(c)){p.args[1]=simulated(c);e.injected=true;}else e.injected=false;
                    }
                }
            }));
            hooks.addAll(HotBridge.hookAllConstructors(HotHelpers.findClass("gy",loader),new HotHook(){protected void afterHookedMethod(HotParam p){if(!p.hasThrowable())track(HotHelpers.getObjectField(p.thisObject,"i"));}}));
            // Also capture the repository used by a live controller if constructed before installation.
            hooks.addAll(HotBridge.hookAllConstructors(HotHelpers.findClass("ao1",loader),new HotHook(){protected void afterHookedMethod(HotParam p){if(!p.hasThrowable()){Object repo=HotHelpers.getObjectField(p.thisObject,"d");track(HotHelpers.getObjectField(repo,"i"));}}}));
            hooks.add(HotHelpers.findAndHookMethod(HotHelpers.findClass("nb5",loader),"J",HotHelpers.findClass("jb1",loader),HotHelpers.findClass("yp1",loader),String.class,HotHelpers.findClass("p31",loader),new HotHook(10000){protected void beforeHookedMethod(HotParam p){if(FakeBanStore.active(HotApp.currentApplication())){MultiReplyQueue.cancel();p.setResult(null);refresh();}}}));
            installed=true;refresh();
            HotBridge.log("[DEKK][伪封号] 原生禁言状态流已接入");
        }catch(Throwable t){for(HotUnhook h:hooks)h.unhook();HotBridge.log("[DEKK][伪封号] 安装失败: "+t);}
    }
}