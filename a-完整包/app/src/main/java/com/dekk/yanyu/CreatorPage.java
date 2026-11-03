package com.dekk.yanyu;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 创作者主页：头像、昵称、四个计数、关注（取关二次确认），以及 TA 公开的人设 / 插件。
 * 关注状态变化后重新拉一次资料，数字随即可见。
 *
 * <p>加载期间先铺骨架（头像 + 昵称 + 计数 + 两块列表），数据到了骨架淡出、内容错开浮现，
 * 所以进页面不会先看到一片空白。
 */
public final class CreatorPage {
    private static final String[] KEYS={"follows","fans","personas","likes"};
    private static final String[] LABELS={"关注","粉丝","人设","点赞"};

    private CreatorPage() { }

    static void show(final Activity activity,final int userId,final String fallbackName){
        final CenterPage page=new CenterPage(activity,fallbackName.length()==0?"创作者主页":fallbackName,null);
        if(userId<=0){page.message("找不到这个创作者");page.show();return;}

        final Parts parts=build(activity,page,userId,fallbackName);
        final TextView nickname=parts.nickname;
        final TextView follow=parts.follow;
        final View loading=parts.loading;
        final ImageView avatar=parts.avatar;
        final LinearLayout body=parts.body;
        final TextView[] values=parts.values;

        final boolean[] following={false};
        final String[] creatorName={fallbackName};
        final Activity host=activity;
        final Profile profile=new Profile(host,page,userId,nickname,values,avatar,follow,following,creatorName,loading,body);

        follow.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            follow.setEnabled(false);
            FollowAction.ask(host,userId,creatorName[0],following[0],new FollowAction.Listener(){
                public void done(boolean result,String error){
                    follow.setEnabled(true);
                    if(error!=null){page.message(error);return;}
                    following[0]=result;
                    follow.setText(result?"已关注":"关注");
                    page.message(result?"已关注该创作者":"已取消关注");
                    profile.load(); // 关注/粉丝数字会变，重新拉一次
                }});
        }});
        page.show();
        profile.load();
    }

    /** 头部 + 列表容器里需要后面更新的控件。 */
    private static final class Parts {
        TextView nickname,follow;
        View loading;
        ImageView avatar;
        LinearLayout body;
        TextView[] values;
    }

    /** 头部 + 列表容器。 */
    private static Parts build(Activity activity,CenterPage page,int userId,String fallbackName){
        LinearLayout head=page.card();
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView avatar=new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int size=page.dp(84);
        head.addView(avatar,new LinearLayout.LayoutParams(size,size));
        TextView nickname=page.label(fallbackName.length()==0?"加载中…":fallbackName,20);
        nickname.setTypeface(MiuixUi.medium());
        nickname.setGravity(Gravity.CENTER);
        nickname.setPadding(0,page.dp(10),0,page.dp(6));
        head.addView(nickname);
        // 还没有昵称可显示：让"加载中…"柔和呼吸
        if(fallbackName.length()==0)Motion.breath(nickname);

        LinearLayout stats=new LinearLayout(activity);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] values=new TextView[KEYS.length];
        for(int i=0;i<KEYS.length;i++){
            LinearLayout column=new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER);
            TextView value=page.label("0",17);
            value.setTypeface(MiuixUi.medium());
            value.setGravity(Gravity.CENTER);
            TextView label=page.label(LABELS[i],12);
            label.setTextColor(page.secondary);label.setGravity(Gravity.CENTER);
            label.setPadding(0,page.dp(4),0,0);
            column.addView(value);column.addView(label);
            values[i]=value;
            stats.addView(column,new LinearLayout.LayoutParams(0,-2,1));
        }
        head.addView(stats,new LinearLayout.LayoutParams(-1,-2));
        TextView follow=page.action("关注",new Runnable(){public void run(){}});

        // 内容容器：骨架和真实列表都放这里，替换时页面不会跳
        LinearLayout body=new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        page.body.addView(body,new LinearLayout.LayoutParams(-1,-2));
        View loading=FeedUi.profileSkeleton(activity,page);
        body.addView(loading);

        Parts parts=new Parts();
        parts.nickname=nickname;parts.follow=follow;parts.loading=loading;
        parts.avatar=avatar;parts.body=body;parts.values=values;
        return parts;
    }

    /** 拉取资料的封装：关注后可以再 load() 一次刷新。 */
    private static final class Profile {
        private final Activity activity;
        private final CenterPage page;
        private final int userId;
        private final TextView nickname, follow;
        private final View loading;
        private final TextView[] values;
        private final ImageView avatar;
        private final boolean[] following;
        private final String[] creatorName;
        private final LinearLayout body;
        private boolean loaded;

        Profile(Activity activity,CenterPage page,int userId,TextView nickname,TextView[] values,
                ImageView avatar,TextView follow,boolean[] following,String[] creatorName,
                View loading,LinearLayout body){
            this.activity=activity;this.page=page;this.userId=userId;this.nickname=nickname;
            this.values=values;this.avatar=avatar;this.follow=follow;this.following=following;
            this.creatorName=creatorName;this.loading=loading;this.body=body;
        }

        void load(){
            PersonaApi.request(activity,"user_profile&id="+userId,null,new PersonaApi.Callback(){
                public void done(JSONObject result,String error){
                    if(!page.active())return;
                    if(error!=null){
                        FeedUi.fadeOutAndRemove(loading);
                        page.message(error);
                        return;
                    }
                    JSONObject profile=result==null?null:result.optJSONObject("profile");
                    if(profile==null){
                        FeedUi.fadeOutAndRemove(loading);
                        page.message("资料格式错误");
                        return;
                    }
                    FeedUi.fadeOutAndRemove(loading);
                    creatorName[0]=profile.optString("nickname","未设置昵称");
                    Motion.stopBreath(nickname);
                    nickname.setText(creatorName[0]);
                    SponsorName.apply(nickname,SponsorName.flag(profile,"sponsor"),ModuleTheme.text(page.night));
                    for(int i=0;i<KEYS.length;i++)values[i].setText(String.valueOf(profile.optInt(KEYS[i],0)));
                    // 没设头像或头像文件已不存在：服务端返回空地址，这里直接用内置默认头像
                    AvatarUi.show(avatar,profile.optString("avatar_url",""));
                    following[0]=result.optBoolean("following",false);
                    follow.setText(following[0]?"已关注":"关注");
                    if(!loaded){
                        loaded=true;
                        list(activity,page,body,result.optJSONArray("personas"),ItemDetailPage.PERSONA,"TA 的人设",
                                userId,creatorName[0],profile.optString("avatar",""));
                        list(activity,page,body,result.optJSONArray("plugins"),ItemDetailPage.PLUGIN,"TA 的插件",
                                userId,creatorName[0],profile.optString("avatar",""));
                    }
                }});
        }
    }

    /** TA 公开的人设与插件，点进去就是详情页（带上创作者信息，避免显示“未知”）。 */
    private static void list(final Activity activity,final CenterPage page,final LinearLayout parent,
                             final JSONArray rows,final int kind,final String section,final int userId,
                             final String authorName,final String avatarFile){
        parent.addView(MiuixUi.section(activity,page.night,section));
        LinearLayout card=MiuixUi.card(activity,page.night);
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);
        cardParams.bottomMargin=page.dp(16);
        parent.addView(card,cardParams);
        // 折叠：默认收起，用户点哪个展开哪个（内容多的时候不用一路滑到底）
        CollapsibleSection.Result block=CollapsibleSection.add(activity,page,card,
                rows==null||rows.length()==0?"（暂无）":"共 "+rows.length()+" 个，点击展开",false,
                kind==ItemDetailPage.PERSONA?MiuixIcon.STAR:MiuixIcon.PLUG);
        // 数据到了错开浮现，和列表页的节奏一致
        FeedUi.enterCard(card,Math.max(0,parent.getChildCount()-2),true);
        if(rows==null||rows.length()==0){
            TextView empty=page.label("TA 还没有发布过内容",13);
            empty.setTextColor(page.secondary);
            block.content.addView(empty);
            return;
        }
        for(int i=0;i<rows.length();i++){
            final JSONObject row=rows.optJSONObject(i);
            if(row==null)continue;
            LinearLayout item=new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0,page.dp(10),0,page.dp(10));
            final boolean visible=!"private".equals(row.optString("visibility","public"));
            TextView name=page.label(row.optString("name"),16);
            item.addView(name);
            TextView desc=page.label(row.optString("description",""),13);
            desc.setTextColor(page.secondary);
            desc.setMaxLines(2);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            desc.setPadding(0,page.dp(4),0,0);
            item.addView(desc);
            if(!visible){
                // 未公开的作品也能看到（创作者主页列出来），但点不进去、不能下载
                TextView lock=page.label("未公开 · 只有作者本人可以下载",12);
                lock.setTextColor(0xFFE53935);
                lock.setPadding(0,page.dp(4),0,0);
                item.addView(lock);
                continue;
            }
            item.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
                ItemDetailPage.show(activity,kind,row,userId,authorName,avatarFile);
            }});
            block.content.addView(item);
        }
    }
}