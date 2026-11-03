package com.dekk.yanyu;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/**
 * 本进程播放器（不另起 Service）：拿到直链就真播，拿不到直链（版权/会员限制）
 * 就退化成"静默同步"——只维护虚拟进度，保证一起听两边进度仍然一致。
 */
final class MusicPlayer {
    static final int STATE_IDLE=0;
    static final int STATE_LOADING=1;
    static final int STATE_PLAYING=2;
    static final int STATE_PAUSED=3;
    static final int STATE_FAILED=4;

    interface Listener {
        void onMusicState(int state,String message);
    }

    private static final MusicPlayer INSTANCE=new MusicPlayer();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());

    private final List<Listener> listeners=new ArrayList<Listener>();
    private final List<NeteaseApi.Track> queue=new ArrayList<NeteaseApi.Track>();
    private MediaPlayer player;
    private Thread loader;
    private int generation;
    private NeteaseApi.Track current;
    private int state=STATE_IDLE;
    private String message="";
    private String lyric="";
    private long virtualPosition;
    private long virtualAt;
    private boolean silent;
    private int quality=NeteaseApi.BR_HIGH;
    private Context playbackContext;
    private final Runnable endWatch=new Runnable(){public void run(){
        if(current==null)return;
        if(silent&&state==STATE_PLAYING&&current.duration>0&&position()>=current.duration){
            completed(playbackContext);
        }
        if(current!=null){MAIN.removeCallbacks(this);MAIN.postDelayed(this,500);}
    }};

    private MusicPlayer() { }

    static MusicPlayer get(){return INSTANCE;}
    static boolean ready(){return INSTANCE.current!=null;}

    void addListener(Listener listener){
        if(listener==null||listeners.contains(listener))return;
        listeners.add(listener);
    }

    void removeListener(Listener listener){listeners.remove(listener);}

    List<NeteaseApi.Track> queue(){return queue;}

    NeteaseApi.Track current(){return current;}

    int state(){return state;}
    String message(){return message;}
    String lyric(){return lyric;}

    /** 解析后的歌词（内部按内容缓存，可随时按播放位置取行）。 */
    MusicLyric lyrics(){return MusicLyric.of(lyric);}

    /** 当前该显示的那句歌词；没有歌词或还没开始返回空串。 */
    String lyricLine(){return lyrics().lineAt(position());}

    boolean silent(){return silent;}
    int quality(){return quality;}

    void setQuality(int br){quality=br;}

    String title(){return current==null?"":current.label();}

    String cover(){return current==null?"":current.cover;}

    String currentId(){return current==null?"":current.id;}

    /** 播放位置：真播时问 MediaPlayer，静默时算虚拟时钟。 */
    long position(){
        if(player!=null&&!silent){
            try{return player.getCurrentPosition();}catch(Throwable ignored){ }
        }
        if(state==STATE_PLAYING)return virtualPosition+(System.currentTimeMillis()-virtualAt);
        return virtualPosition;
    }

    /** 加入队列并播放；positionMs 用于一起听对齐。 */
    void play(Context context,NeteaseApi.Track track,long positionMs){
        if(context==null||track==null||track.id.length()==0)return;
        if(track.id.startsWith("qq:")!=MusicPlatform.qq())return;
        if(!contains(track.id))queue.add(track);
        current=track;
        lyric="";
        silent=false;
        message="";
        generation++;
        final int ticket=generation;
        release();
        virtualPosition=Math.max(0L,positionMs);
        virtualAt=System.currentTimeMillis();
        setState(STATE_LOADING,"正在获取播放地址…");
        final Context app=context.getApplicationContext();
        playbackContext=app;
        MAIN.removeCallbacks(endWatch);
        MAIN.postDelayed(endWatch,500);
        final String id=track.id;
        final long startAt=Math.max(0L,positionMs);
        final int wanted=quality;
        loader=new Thread(new Runnable(){
            public void run(){
                NeteaseApi.Audio audio=null;
                String cookie=id.startsWith("qq:")?QqMusicAccount.cookie(app):NeteaseAccount.cookie(app);
                int[] rates=id.startsWith("qq:")?new int[]{128000}:distinctRates(wanted);
                for(int rate:rates){
                    try{audio=id.startsWith("qq:")?QqMusicApi.audio(id,cookie,QqMusicDevice.guid(app)):NeteaseApi.audio(id,rate,cookie);}catch(Throwable t){audio=null;}
                    if(ticket!=generation)return;
                    if(audio!=null&&audio.url.length()>0)break;
                }
                final NeteaseApi.Audio found=audio;
                if(found==null||found.url.length()==0){
                    HotBridge.log("[DEKK][一起听] "+id+" 没有可播放直链（版权/会员限制），改为静默同步");
                    silent=true;
                    setState(STATE_PLAYING,"该歌曲没有可播放直链（版权或会员限制），已改为静默同步进度");
                    loadLyric(app,id,ticket);
                    return;
                }
                HotBridge.log("[DEKK][一起听] 直链就绪 br="+found.br+" size="+found.size);
                // MediaPlayer 必须在有 Looper 的线程创建，否则回调不触发（表现就是永远没声音）。
                MAIN.post(new Runnable(){
                    public void run(){
                        if(ticket!=generation)return;
                        try{
                            MediaPlayer created=new MediaPlayer();
                            created.setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                            created.setDataSource(found.url);
                            created.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){
                                public void onPrepared(MediaPlayer mp){
                                    if(ticket!=generation){mp.release();return;}
                                    player=mp;
                                    if(startAt>0)mp.seekTo((int)startAt);
                                    mp.start();
                                    setState(STATE_PLAYING,"正在播放");
                                    HotBridge.log("[DEKK][一起听] 已开始播放（"+mp.getDuration()+"ms）");
                                }
                            });
                            created.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){
                                public void onCompletion(MediaPlayer mp){
                                    if(ticket==generation&&mp==player)completed(app);
                                }
                            });
                            created.setOnErrorListener(new MediaPlayer.OnErrorListener(){
                                public boolean onError(MediaPlayer mp,int what,int extra){
                                    HotBridge.log("[DEKK][一起听] 播放失败 what="+what+" extra="+extra);
                                    mp.release();
                                    if(ticket==generation){
                                        player=null;silent=true;
                                        setState(STATE_PLAYING,"播放失败（"+what+"/"+extra+"），已改为静默同步进度");
                                    }
                                    return true;
                                }
                            });
                            created.prepareAsync();
                        }catch(Throwable t){
                            HotBridge.log("[DEKK][一起听] MediaPlayer 异常: "+t.getClass().getSimpleName()+" "+t.getMessage());
                            silent=true;
                            setState(STATE_PLAYING,"播放异常（"+t.getClass().getSimpleName()+"），已改为静默同步进度");
                        }
                    }
                });
                if(ticket==generation)loadLyric(app,id,ticket);
            }
        },"dekk-music-load");
        loader.setDaemon(true);
        loader.start();
    }

    /** 质量从设定值开始，逐级回退，尽量拿到能播的直链。 */
    private static int[] distinctRates(int wanted){
        int[] pool={wanted,NeteaseApi.BR_HIGH,NeteaseApi.BR_MID,NeteaseApi.BR_LOW};
        int[] result=new int[pool.length];
        int size=0;
        for(int rate:pool){
            boolean seen=false;
            for(int i=0;i<size;i++)if(result[i]==rate)seen=true;
            if(!seen)result[size++]=rate;
        }
        int[] trimmed=new int[size];
        System.arraycopy(result,0,trimmed,0,size);
        return trimmed;
    }

    void toggle(){
        if(state==STATE_PLAYING)pause();
        else resume();
    }

    void pause(){
        if(player!=null&&!silent){
            try{player.pause();}catch(Throwable ignored){ }
        }
        virtualPosition=position();
        setState(STATE_PAUSED,"已暂停");
    }

    void resume(){
        if(state==STATE_IDLE||current==null)return;
        if(player!=null&&!silent){
            try{player.start();}catch(Throwable ignored){ }
        }
        virtualAt=System.currentTimeMillis();
        setState(STATE_PLAYING,silent?"静默同步中":"正在播放");
    }

    void seekTo(long positionMs){
        long target=Math.max(0L,positionMs);
        if(player!=null&&!silent){
            try{player.seekTo((int)target);}catch(Throwable ignored){ }
        }
        virtualPosition=target;
        virtualAt=System.currentTimeMillis();
        notifyState();
    }

    void stop(){
        generation++;
        release();
        current=null;
        lyric="";
        silent=false;
        virtualPosition=0;
        setState(STATE_IDLE,"");
    }

    private void completed(Context context){
        if(current==null||context==null)return;
        if(MusicSettings.repeatOne(context)){
            seekTo(0L);
            resume();
            return;
        }
        int index=indexOf(currentId());
        if(index>=0&&index+1<queue.size())play(context,queue.get(index+1),0L);
        else {pause();setState(STATE_PAUSED,"列表已播放完毕");}
    }

    void next(Context context){
        int index=indexOf(currentId());
        if(index<0||queue.isEmpty())return;
        NeteaseApi.Track next=queue.get((index+1)%queue.size());
        play(context,next,0L);
    }

    void previous(Context context){
        int index=indexOf(currentId());
        if(index<0||queue.isEmpty())return;
        NeteaseApi.Track prev=queue.get((index-1+queue.size())%queue.size());
        play(context,prev,0L);
    }

    void enqueue(NeteaseApi.Track track){
        if(track!=null&&!contains(track.id))queue.add(track);
    }

    private boolean contains(String id){
        for(NeteaseApi.Track track:queue)if(track.id.equals(id))return true;
        return false;
    }

    private int indexOf(String id){
        for(int i=0;i<queue.size();i++)if(queue.get(i).id.equals(id))return i;
        return -1;
    }

    private void release(){
        silent=false;
        if(player!=null){
            try{player.reset();player.release();}catch(Throwable ignored){ }
            player=null;
        }
    }

    private void loadLyric(final Context context,final String id,final int ticket){
        try{
            String text=id.startsWith("qq:")?QqMusicApi.lyric(id,QqMusicAccount.cookie(context)):NeteaseApi.lyric(id,NeteaseAccount.cookie(context));
            if(ticket==generation)lyric=text==null?"":text;
        }catch(Throwable ignored){ }
    }

    private void setState(int value,String text){
        state=value;
        message=text==null?"":text;
        notifyState();
    }

    private void notifyState(){
        for(Listener listener:new ArrayList<Listener>(listeners)){
            try{listener.onMusicState(state,message);}catch(Throwable ignored){ }
        }
    }
}