package com.dekk.yanyu;

/**
 * 一起听状态机（纯逻辑，便于自检）：用聊天里的 token 驱动。
 * OFF → 收到 [网易云#一起听] → INVITED → 收到 [一起听#接受] → ACTIVE
 * 收到 [一起听#拒绝] 或手动停止 → OFF
 */
final class ListenTogether {
    static final int OFF=0;
    static final int INVITED=1;
    static final int ACTIVE=2;

    static final int DECISION_IGNORE=0;
    static final int DECISION_INVITED=1;
    static final int DECISION_ACCEPTED=2;
    static final int DECISION_REFUSED=3;
    static final int DECISION_SONG=4;

    private static int state=OFF;
    private static String lastSongId="";
    private static String lastSongTitle="";
    private static long lastSongAt;
    private static String note="";

    private ListenTogether() { }

    static synchronized int state(){return state;}
    static synchronized boolean active(){return state==ACTIVE;}
    static synchronized boolean pending(){return state==INVITED;}
    static synchronized String lastSongId(){return lastSongId;}
    static synchronized String lastSongTitle(){return lastSongTitle;}
    static synchronized long lastSongAt(){return lastSongAt;}
    static synchronized String note(){return note;}

    static synchronized void stop(){
        state=OFF;
        note="已停止";
    }

    /** 用户主动发起邀请。 */
    static synchronized String inviteNote(){
        state=INVITED;
        note="已发出邀请，等待对方接受";
        return note;
    }

    static synchronized void markNote(String text){note=text==null?"":text;}

    /** 收到别人的 token 时该干什么。 */
    static synchronized int decide(MusicProtocol.Token token){
        if(token==null)return DECISION_IGNORE;
        if((token.kind==MusicProtocol.INVITE||token.kind==MusicProtocol.SONG)&&token.songId.startsWith("qq:")!=MusicPlatform.qq())return DECISION_IGNORE;
        if(token.kind==MusicProtocol.INVITE){
            if(state==OFF){
                state=INVITED;
                note="收到一起听邀请";
                return DECISION_INVITED;
            }
            return DECISION_IGNORE;
        }
        if(token.kind==MusicProtocol.ACCEPT){
            if(state==INVITED||state==ACTIVE){
                state=ACTIVE;
                note="一起听进行中";
                return DECISION_ACCEPTED;
            }
            return DECISION_IGNORE;
        }
        if(token.kind==MusicProtocol.REFUSE){
            if(state!=OFF){
                state=OFF;
                note="对方拒绝了邀请";
                return DECISION_REFUSED;
            }
            return DECISION_IGNORE;
        }
        if(token.kind==MusicProtocol.SONG){
            if(state==INVITED)state=ACTIVE;
            if(state!=ACTIVE)return DECISION_IGNORE;
            boolean fresh=!token.songId.equals(lastSongId);
            lastSongId=token.songId;
            lastSongTitle=token.title;
            lastSongAt=System.currentTimeMillis();
            note=fresh?"一起听："+token.title:"对方重新开始了这首歌";
            return DECISION_SONG;
        }
        return DECISION_IGNORE;
    }

    /** 同一首歌短期内重复出现（例如重发）时不需要重新从头播。 */
    static synchronized boolean shouldRestart(String songId){
        if(songId==null||songId.length()==0)return false;
        return !songId.equals(MusicPlayer.get().currentId());
    }

    static String statusText(){
        switch(state()){
            case INVITED:return "等待对方接受";
            case ACTIVE:return "一起听进行中";
            default:return "未开启";
        }
    }
}