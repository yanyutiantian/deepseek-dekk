package com.dekk.yanyu;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;

/**
 * 人设 / 插件详情页（按草图）：
 * 顶部名称 → 「简介」「提示词」两块可折叠内容（点标题才展开）→
 * 左边创作者 / 上传时间 / 查看创作者的主页，右边 下载 / 关注 / 创作者头像。
 */
public final class ItemDetailPage {
    static final int PERSONA=1;
    static final int PLUGIN=2;

    private ItemDetailPage() { }

    static void show(final Activity activity,final int kind,final JSONObject row){
        show(activity,kind,row,0,"","");
    }

    /**
     * @param fallbackUserId   列表里没带作者时用（例如从创作者主页点进来）
     * @param fallbackAuthor   同上，作者昵称
     * @param fallbackAvatar   同上，作者头像文件名
     */
    static void show(final Activity activity,final int kind,final JSONObject row,
                     final int fallbackUserId,final String fallbackAuthor,final String fallbackAvatar){
        if(row==null)return;
        final boolean isPersona=kind==PERSONA;
        final int userId=row.optInt("user_id",0)>0?row.optInt("user_id",0):fallbackUserId;
        final String authorName=row.optString("author","").length()>0
                ?row.optString("author"):(fallbackAuthor.length()>0?fallbackAuthor:"未知用户");
        final String avatarFile=row.optString("author_avatar","").length()>0
                ?row.optString("author_avatar"):fallbackAvatar;
        final String name=row.optString("name");
        final CenterPage page=new CenterPage(activity,name,null);

        // 骨架先占位：形状与下面的真实版面一一对应，所以内容浮现时页面不会跳、也不会闪白
        final View skeleton=FeedUi.detailSkeleton(activity,page);
        page.body.addView(skeleton);

        // 真实内容先放进临时容器：等构建完后整体插到骨架的位置上
        final LinearLayout content=new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);

        // 名称已经在大标题里显示，正文不再重复一遍（之前是 34sp 标题 + 22sp 重名标题）

        // 简介：默认折叠，点标题才展开（提示词不在详情页展示，下载后在聊天里使用）
        LinearLayout blocks=panel(activity,page,true);
        content.addView(blocks);
        CollapsibleSection.Result brief=CollapsibleSection.add(activity,page,blocks,"简介",MiuixIcon.DOC);
        TextView briefText=page.label(row.optString("description","暂无简介"),15);
        briefText.setLineSpacing(0,1.25f);
        briefText.setPadding(0,page.dp(6),0,page.dp(10));
        brief.content.addView(briefText);

        // 左信息卡 / 右操作卡
        LinearLayout line=new LinearLayout(activity);
        line.setOrientation(LinearLayout.VERTICAL);
        LinearLayout info=panel(activity,page,false);
        LinearLayout actions=panel(activity,page,false);
        actions.setGravity(Gravity.CENTER_HORIZONTAL);
        actions.setPadding(page.dp(16),page.dp(16),page.dp(16),page.dp(16));
        LinearLayout.LayoutParams infoParams=new LinearLayout.LayoutParams(-1,-2);
        infoParams.setMargins(0,0,0,page.dp(12));
        line.addView(info,infoParams);
        line.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        actions.setOrientation(LinearLayout.HORIZONTAL);actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lineParams=new LinearLayout.LayoutParams(-1,-2);
        lineParams.topMargin=page.dp(12);
        content.addView(line,lineParams);

        MiuixUi.Row authorRow=MiuixUi.row(activity,info,page.night,MiuixIcon.PERSON,"创作者",authorName,false,null);
        SponsorName.apply(authorRow.summary,SponsorName.flag(row,"author_sponsor"),ModuleTheme.secondary(page.night));
        MiuixUi.row(activity,info,page.night,MiuixIcon.CLOCK,"上传时间",
                shortTime(row.optString("created_at","")),false,null);
        MiuixUi.Row homeRow=MiuixUi.row(activity,info,page.night,MiuixIcon.PERSON,"查看创作者的主页","",true,
                new View.OnClickListener(){public void onClick(View v){
                    CreatorPage.show(activity,userId,authorName);
                }});
        homeRow.title.setTextColor(ModuleTheme.ACCENT);

        final TextView download=pill(page,"下载",true);
        LinearLayout.LayoutParams downloadParams=new LinearLayout.LayoutParams(0,page.dp(48),1);
        downloadParams.rightMargin=page.dp(12);actions.addView(download,downloadParams);

        final FollowButton follow=new FollowButton(activity,page,userId,authorName);
        actions.addView(follow.view);

        final ImageView avatar=new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int size=page.dp(44);
        LinearLayout.LayoutParams avatarParams=new LinearLayout.LayoutParams(size,size);
        avatarParams.leftMargin=page.dp(12);
        actions.addView(avatar,avatarParams);
        // 没有头像 / 头像文件不存在 → 直接用内置默认头像
        AvatarUi.show(avatar,avatarUrl(userId,avatarFile));
        avatar.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            CreatorPage.show(activity,userId,authorName);
        }});

        TextView hint=page.label(isPersona
                ?"下载后可在聊天里直接使用这个人设；关注作者可以看到 TA 的后续作品。"
                :"下载后可以在聊天里直接使用这个插件；关注作者可以看到 TA 的后续作品。",13);
        hint.setTextColor(page.secondary);
        hint.setPadding(0,page.dp(12),0,0);
        content.addView(hint);

        // 评论区（登录后才能发，头衔由服务端判定）—— 挂在临时容器里，随内容一起浮现
        CommentSection.attach(activity,page,content,kind,row);

        // 内容就位：插到骨架所在位置，然后骨架淡出、内容逐块错开浮现
        page.body.addView(content,page.body.indexOfChild(skeleton));
        page.show();
        content.postDelayed(new Runnable(){public void run(){
            FeedUi.revealBlocks(content,skeleton,true);
        }},240L);

        // 已下载过就直接显示「已下载」
        if(localExists(activity,kind,row)){
            download.setText("已下载");
            download.setEnabled(false);
        }
        download.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            // 下载中：按钮呼吸 + 禁用（不再只是把文字改成"下载中…"就没反馈）
            Motion.busy(download,true);
            download.setText("下载中…");
            download(activity,page,kind,row,download);
        }});

        // 关注状态：列表里没有这个字段，单独按作者 id 拉一次
        follow.load();
    }

    /** 关注按钮：状态从创作者资料接口取，取关要二次确认。 */
    private static final class FollowButton {
        final TextView view;
        private boolean following;
        private final Activity activity;
        private final CenterPage page;
        private final int userId;
        private final String name;

        FollowButton(Activity activity,CenterPage page,int userId,String name){
            this.activity=activity;this.page=page;this.userId=userId;this.name=name;
            this.view=pill(page,"关注",false);
            // 和「下载」同高同圆角，否则两个按钮一高一矮很明显
            this.view.setLayoutParams(new LinearLayout.LayoutParams(page.dp(92),page.dp(48)));
            apply(false);
            view.setOnClickListener(new View.OnClickListener(){public void onClick(View v){click();}});
        }

        /** 未关注=浅蓝底蓝字，已关注=灰底次级色。 */
        private void apply(boolean value){
            following=value;
            view.setText(value?"已关注":"关注");
            // 文字颜色渐变（终态由 Motion.color 保证，去掉动画也是对的）
            Motion.color(view,value?ModuleTheme.secondary(page.night):ModuleTheme.ACCENT,240L);
            view.setBackground(page.shape(value
                    ?(page.night?0xFF2A2A2A:0xFFF0F0F0)
                    :(page.night?0xFF23314D:0xFFEBF1FF),23));
        }

        void load(){
            if(userId<=0)return;
            PersonaApi.request(activity,"user_profile&id="+userId,null,new PersonaApi.Callback(){
                public void done(JSONObject result,String error){
                    if(error!=null||result==null)return;
                    apply(result.optBoolean("following",false));
                }});
        }

        /** 关注直接生效；取关走二次确认（FollowAction.ask）。 */
        private void click(){
            // 忙碌态：禁用 + 呼吸，点了就有"在处理"的反馈
            Motion.busy(view,true);
            FollowAction.ask(activity,userId,name,following,new FollowAction.Listener(){
                public void done(boolean result,String error){
                    Motion.stopBreath(view);
                    view.setEnabled(true);
                    if(error!=null){page.message(error);return;}
                    apply(result);
                    Motion.pulse(view); // 状态变了，脉冲确认一下
                    page.message(result?"已关注该创作者":"已取消关注");
                    // 关注数/粉丝数会变：重新拉一次创作者资料
                    load();
                }});
        }
    }

    /** 和 CenterPage.card() 同样样式，但不自动加进 body（要放进横向布局）。 */
    private static LinearLayout panel(Activity activity,CenterPage page,boolean full){
        LinearLayout card=new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(page.dp(16),page.dp(4),page.dp(16),page.dp(4));
        card.setBackground(MiuixUi.shape(activity,ModuleTheme.surface(page.night),ModuleTheme.CARD_RADIUS));
        if(full){
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
            params.topMargin=page.dp(4);params.bottomMargin=page.dp(4);
            card.setLayoutParams(params);
        }
        return card;
    }

    private static TextView text(CenterPage page,String value,int size){
        TextView view=page.label(value,size);
        view.setPadding(0,page.dp(8),0,page.dp(8));
        return view;
    }

    private static TextView pill(CenterPage page,String label,boolean filled){
        TextView view=page.label(label,15);
        view.setTextColor(filled?0xFFFFFFFF:ModuleTheme.text(page.night));
        view.setGravity(Gravity.CENTER);
        view.setPadding(page.dp(24),page.dp(12),page.dp(24),page.dp(12));
        view.setBackground(page.shape(filled?ModuleTheme.ACCENT:(page.night?0xFF2A2A2A:0xFFF0F0F0),23));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);
        params.bottomMargin=page.dp(12);
        view.setLayoutParams(params);
        // 详情页的「下载」「关注」都是 pill：统一按下回缩反馈
        Motion.pressable(view);
        return view;
    }

    /** 本机是否已经有这份副本（决定按钮显示「下载」还是「已下载」）。 */
    private static boolean localExists(Activity activity,int kind,JSONObject row){
        try{
            long id=row.optLong("id");
            org.json.JSONArray all=kind==PERSONA?PersonaLocalStore.list(activity):PluginStore.list(activity);
            for(int i=0;i<all.length();i++){
                JSONObject item=all.optJSONObject(i);
                if(item!=null&&item.optLong("id")==id)return true;
            }
        }catch(Throwable ignored){ }
        return false;
    }

    private static void download(final Activity activity,final CenterPage page,final int kind,
                                 final JSONObject row,final TextView button){
        PersonaApi.request(activity,(kind==PERSONA?"download&id=":"plugin_download&id=")+row.optLong("id"),null,
                new PersonaApi.Callback(){public void done(JSONObject result,String error){
            if(error!=null){
                Motion.stopBreath(button);
                button.setEnabled(true);button.setText("下载");
                page.message(error);return;
            }
            JSONObject item=result==null?null:result.optJSONObject(kind==PERSONA?"persona":"plugin");
            if(item==null){
                Motion.stopBreath(button);
                button.setEnabled(true);button.setText("下载");
                page.message("返回格式错误");return;
            }
            try{
                if(kind==PERSONA)PersonaLocalStore.save(activity,item);
                else PluginStore.save(activity,item);
                Motion.stopBreath(button);
                button.setText("已下载");
                Motion.pulse(button); // 下载完成：脉冲确认一下
                page.message("已保存，可在聊天里使用");
            }catch(Exception e){
                Motion.stopBreath(button);
                button.setEnabled(true);button.setText("下载");
                page.message("本地保存失败");
            }
        }});
    }

    /** 头像地址：没设头像或文件不存在时返回空串，交给内置默认头像。 */
    static String avatarUrl(int userId,String avatarFile){
        if(userId<=0)return "";
        // 没上传过头像也让服务端下发默认头像，创作者头像位置不留空
        if(avatarFile==null||avatarFile.length()==0)
            return "https://dekk.example.com/API/?action=avatar&id="+userId+"&default=1";
        return "https://dekk.example.com/API/?action=avatar&id="+userId+"&v="+avatarFile.hashCode();
    }

    static String shortTime(String value){
        if(value==null)return "未知";
        String text=value.trim();
        return text.length()>=16?text.substring(0,16):(text.length()==0?"未知":text);
    }
}