package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.Set;

/**
 * 一起听的聊天侧接线：
 * 1) 抓住真实发送入口（controller/输入框状态/发送模式）以便发送 token；
 * 2) 用户发送时立刻处理 token；
 * 3) 每 1.5 秒轮询一次会话，处理对方（AI）回复里的 token，并维护迷你播放条。
 */
public final class MusicHook {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Set<String> SEEN=new HashSet<String>();
    private static final boolean[] sessionLogged={false};
    private static final boolean[] barLogged={false};
    private static boolean installed;
    private static Activity attachedActivity;

    private MusicHook() { }

    public static void install(ClassLoader loader){
        if(installed)return;
        try{
            Class<?> owner=HotHelpers.findClass("nb5",loader);
            Class<?> controllerClass=HotHelpers.findClass("jb1",loader);
            Class<?> composerClass=HotHelpers.findClass("yp1",loader);
            Class<?> modeClass=HotHelpers.findClass("p31",loader);
            java.lang.reflect.Method send=owner.getDeclaredMethod("J",controllerClass,composerClass,
                    String.class,modeClass);
            send.setAccessible(true);
            MusicSend.sendMethod(send);
            HotBridge.hookMethod(send,new HotHook(7000){
                protected void beforeHookedMethod(HotParam param){
                    try{
                        if(param.args==null||param.args.length!=4)return;
                        MusicSend.bind(param.args[0],param.args[1],param.args[3]);
                        String text=param.args[2] instanceof String?(String)param.args[2]:"";
                        for(MusicProtocol.Token token:MusicProtocol.scan(text)){
                            if(token.kind!=MusicProtocol.SONG)handleOwn(token);
                        }
                        if(TransferProtocol.has(text)){
                            android.content.Context c=HotApp.currentApplication();
                            if(c!=null)TransferStore.create(c,TransferProtocol.amount(text),false);
                        }
                    }catch(Throwable t){
                        HotBridge.log("[DEKK][一起听] 发送处理异常: "+t.getClass().getSimpleName());
                    }
                }
            });
            // 回答落盘后立刻检查一次，不必等轮询。
            HotHelpers.findAndHookMethod(HotHelpers.findClass("ao1",loader),"T",
                    HotHelpers.findClass("ao1",loader),HotHelpers.findClass("d21",loader),
                    new HotHook(){
                protected void afterHookedMethod(HotParam param){
                    if(param.args!=null&&param.args.length>0)MusicSend.sessionOwner(param.args[0]);
                    MAIN.postDelayed(new Runnable(){public void run(){tick();}},400);
                }
            });
            // 会话对象（宿主 ao1）持有会话 id / 消息表 / 输入框状态；构造时先抓住一个，
            // 这样即使用户还没发过消息也能读取会话。
            HotBridge.hookAllConstructors(HotHelpers.findClass("ao1",loader),
                    new HotHook(){
                protected void afterHookedMethod(HotParam param){
                    MusicSend.sessionOwner(param.thisObject);
                    if(!sessionLogged[0]){
                        sessionLogged[0]=true;
                        HotBridge.log("[DEKK][一起听] 已捕捉当前会话对象");
                    }
                }
            });
            installed=true;
            MAIN.postDelayed(POLL,1500);
            HotBridge.log("[DEKK][一起听] 已安装（迷你条 + 会话轮询）");
        }catch(Throwable t){
            HotBridge.log("[DEKK][一起听] 安装失败: "+t);
        }
    }

    private static final Runnable POLL=new Runnable(){
        public void run(){
            try{tick();}catch(Throwable ignored){ }
            MAIN.postDelayed(this,1500);
        }
    };

    private static void tick(){
        // 迷你条与 token 轮询必须互不影响：挂条失败也不能让轮询停摆。
        try{
            updateBar();
        }catch(Throwable t){
            if(!barLogged[0]){
                barLogged[0]=true;
                HotBridge.log("[DEKK][一起听] 迷你条挂载失败: "+t);
            }
        }
        try{
            TransferHook.tick();
        }catch(Throwable ignored){ }
        try{
            pollTokens();
        }catch(Throwable ignored){ }
    }

    private static void updateBar(){
        Activity activity=DekkSettingsPage.currentActivity();
        if(activity==null)return;
        Context context=activity.getApplicationContext();
        if(!MusicSettings.miniBar(context)){
            if(MusicMiniBar.attached())MusicMiniBar.detach();
            return;
        }
        if(activity!=attachedActivity||!MusicMiniBar.attached()){
            if(MusicMiniBar.attached())MusicMiniBar.detach();
            // 只要开关是开的就强制挂到右上角：和有没有歌、一起听有没有同意都无关。
            MusicMiniBar.attach(activity);
            if(MusicMiniBar.attached())attachedActivity=activity;
        }
        MusicMiniBar.refresh();
    }

    private static void pollTokens(){
        String[] session=MusicSend.readSession();
        if(session==null||session.length<2)return;
        String text=session[1];
        if(text.length()==0)return;
        String key=session[0]+"#"+text.hashCode();
        if(SEEN.contains(key))return;
        SEEN.add(key);
        if(SEEN.size()>64)SEEN.clear();
        // 用容错扫描：AI 常把 token 写在句子里、或加粗/带空格，都要能认出来。
        java.util.List<MusicProtocol.Token> tokens=MusicProtocol.scan(text);
        if(!tokens.isEmpty()){
            for(MusicProtocol.Token token:tokens)handle(token);
            return;
        }
        // 兜底：还在等回复，而 AI 直接用文字答应了（"好啊，一起听吧"）。
        if(ListenTogether.pending()){
            int answer=MusicProtocol.proseAnswer(text);
            if(answer!=0)handle(new MusicProtocol.Token(answer,"",""));
        }
    }

    private static void handle(MusicProtocol.Token token){
        int before=ListenTogether.state();
        int decision=ListenTogether.decide(token);
        if(decision==ListenTogether.DECISION_IGNORE)return;
        HotBridge.log("[DEKK][一起听] 收到"+MusicProtocol.name(token)
                +"："+stateName(before)+" → "+stateName(ListenTogether.state()));
        if(decision==ListenTogether.DECISION_SONG&&ListenTogether.shouldRestart(token.songId))playSong(token);
    }

    /** 用户自己发出的 token：邀请就用"已发出邀请"的措辞，其它交给 handle。 */
    private static void handleOwn(MusicProtocol.Token token){
        if(token==null)return;
        if(token.kind==MusicProtocol.INVITE){
            if(ListenTogether.state()==ListenTogether.OFF){
                HotBridge.log("[DEKK][一起听] "+ListenTogether.inviteNote());
            }
            return;
        }
        handle(token);
    }

    private static String stateName(int state){
        switch(state){
            case ListenTogether.INVITED:return "等对方接受";
            case ListenTogether.ACTIVE:return "一起听进行中";
            default:return "未开启";
        }
    }

    /** 把 token 里的歌曲抓下来并播放（详情失败就用 token 里的歌名兜底）。 */
    static void playSong(final MusicProtocol.Token token){
        Thread worker=new Thread(new Runnable(){
            public void run(){
                Context context=HotApp.currentApplication();
                if(context==null)return;
                NeteaseApi.Track track=null;
                try{
                    if(token.songId.startsWith("qq:"))track=QqMusicApi.detail(token.songId,QqMusicAccount.cookie(context));
                    else{NeteaseApi.Reply reply=NeteaseApi.detail(token.songId,NeteaseAccount.cookie(context));
                    track=NeteaseApi.parseDetail(reply);}
                }catch(Throwable ignored){ }
                if(track==null||track.id.length()==0){
                    track=new NeteaseApi.Track();
                    track.id=token.songId;
                    track.name=token.title;
                }
                MusicPlayer.get().play(context,track,0L);
            }
        },"dekk-music-fetch");
        worker.setDaemon(true);
        worker.start();
    }

    /** 供三级页面复用的发送入口。 */
    static void sendToken(Activity activity,String token){
        MusicSend.send(activity.getApplicationContext(),token);
    }
}