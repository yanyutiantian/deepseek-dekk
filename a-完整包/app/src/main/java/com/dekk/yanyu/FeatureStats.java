package com.dekk.yanyu;

import android.content.Context;

/** 首页数据卡用的功能开关统计。单项查询失败按未开启处理，不影响页面。 */
final class FeatureStats {
    private FeatureStats() { }

    private static int enabled;
    private static int total;

    private static void count(Runnable probe){
        total++;
        try{ probe.run(); }catch(Throwable ignored){ }
    }

    static int total(){
        probe(null);
        return total;
    }
    static int enabled(Context context){
        probe(context);
        return enabled;
    }

    /** 每次调用都重新统计，开关改变后回首页即可看到新数字。 */
    private static void probe(final Context c){
        enabled=0;total=0;
        if(c==null){
            total=12;
            return;
        }
        count(new Runnable(){public void run(){if(MultiReplySettings.enabled(c))enabled++;}});
        count(new Runnable(){public void run(){if(ExpertModeHook.isEnabled(c))enabled++;}});
        count(new Runnable(){public void run(){if(VisionModeHook.isEnabled(c))enabled++;}});
        count(new Runnable(){public void run(){if(ContextSummaryStore.enabled(c))enabled++;}});
        count(new Runnable(){public void run(){if(AntiRecallHook.enabled(c))enabled++;}});
        count(new Runnable(){public void run(){if(FakeBanStore.active(c))enabled++;}});
        count(new Runnable(){public void run(){if(AiBanControl.enabled(c))enabled++;}});
        count(new Runnable(){public void run(){if(AdbControlStore.enabled(c))enabled++;}});
        count(new Runnable(){public void run(){if(ChatBubbleHook.enabled(c))enabled++;}});
        count(new Runnable(){public void run(){if(ChatAvatarStore.enabled(c))enabled++;}});
        count(new Runnable(){public void run(){if(DeviceContextHook.enabled(c))enabled++;}});
        count(new Runnable(){public void run(){if(MusicAiPrompt.enabled(c))enabled++;}});
        if(total==0)total=12;
    }
}