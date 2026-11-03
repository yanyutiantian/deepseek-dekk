package com.dekk.yanyu;
import android.content.SharedPreferences;
final class BubbleStyle {
    static SharedPreferences prefs(){return HotApp.currentApplication().getSharedPreferences("dekk_beautify",0);}
    static int userColor(){return prefs().getInt("bubble_user_color",0xFF9BEA68);}
    static int assistantColor(){return prefs().getInt("bubble_assistant_color",0xFFFFFFFF);}
    static int radius(){return Math.max(0,Math.min(32,prefs().getInt("bubble_radius",4)));}
    /** 聊天头像尺寸（dp），默认 36。 */
    static int avatarSizeDp(){return Math.max(24,Math.min(56,prefs().getInt("avatar_size",36)));}
    static void setAvatarSizeDp(int value){prefs().edit().putInt("avatar_size",Math.max(24,Math.min(56,value))).apply();}
    /** 头像与气泡之间的间距（dp）。 */
    static int avatarGapDp(){return Math.max(2,Math.min(20,prefs().getInt("avatar_gap",8)));}
    static void setAvatarGapDp(int value){prefs().edit().putInt("avatar_gap",Math.max(2,Math.min(20,value))).apply();}
    /** 气泡最大宽度：占屏幕宽度的百分比（40-100），默认 65。 */
    static int maxWidthPct(){return Math.max(40,Math.min(100,prefs().getInt("bubble_width_pct",65)));}
    /** 设定气泡最大宽度百分比。 */
    static void setMaxWidthPct(int value){prefs().edit().putInt("bubble_width_pct",Math.max(40,Math.min(100,value))).apply();}
    static int width(int max,int intrinsic,float density,int screen,boolean user){
        float pct=maxWidthPct()/100f;
        // 用户可在「聊天美化」里调百分比：只影响气泡的最大宽度上限，
        // 短消息仍然按内容宽度自适应（intrinsic 更小时取 intrinsic）。
        float dp=screen>0?Math.max(120f,Math.min(screen*pct,screen-116f)):340f;
        int limit=Math.max(1,Math.round(dp*density))+(user?Math.round(50*density):0);
        return Math.max(1,Math.min(max,Math.min(intrinsic,limit)));
    }
}