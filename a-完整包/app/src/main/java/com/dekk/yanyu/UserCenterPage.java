package com.dekk.yanyu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * 用户中心：头像（选图→压缩→上传）、昵称（点一下即可改）、
 * 关注 / 粉丝 / 人设 / 点赞 四个数字，以及「安全设置」入口。
 */
public final class UserCenterPage {
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final String[] STAT_KEYS={"follows","fans","personas","likes"};
    private static final String[] STAT_LABELS={"关注","粉丝","人设","点赞"};
    /** 选图结果在这里暂存，等 onActivityResult 回来后压缩上传。 */
    private static Activity pending;
    private static Runnable pendingReload;

    private UserCenterPage() { }

    /** 设置页入口：没登录先去登录。 */
    public static void open(Activity activity){
        if(AccountStore.token(activity).length()==0){AccountPages.open(activity);return;}
        show(activity);
    }

    static void show(final Activity activity){
        // KOOK 绑定门禁：未绑定则弹窗
        if(!BindGate.require(activity,null))return;
        build(activity,"个人中心").show();
    }

    /**
     * 内嵌到「我的」标签页：把我的内容直接搬进给定容器，不再单独占一层页面。
     * 个人中心里的回调通过 {@link CenterPage#embedded} 仍然算存活。
     */
    static void fillEmbedded(final Activity activity,final LinearLayout container){
        CenterPage page=build(activity,"我的");
        page.embedded=true;
        LinearLayout body=page.body;
        while(body.getChildCount()>0){
            View child=body.getChildAt(0);
            body.removeViewAt(0);
            container.addView(child);
        }
    }

    private static CenterPage build(final Activity activity,String title){
        final CenterPage page=new CenterPage(activity,title,null);
        if(AccountStore.token(activity).length()==0){
            LinearLayout login=page.card();
            page.rowIcon(login,MiuixIcon.PERSON,"登录 / 注册 ›",new Runnable(){
                public void run(){AccountPages.open(activity);}
            });
        }
        // 资料还没拉到（没有本地缓存）时先铺骨架：头像 + 昵称 + 计数 + 两块内容
        JSONObject cache=UserProfile.get();
        final View skeleton=(cache==null||cache.length()==0)?FeedUi.profileSkeleton(activity,page):null;
        if(skeleton!=null)page.body.addView(skeleton);

        final LinearLayout card=page.card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(page.dp(20),page.dp(28),page.dp(20),page.dp(24));

        // 头像
        final ImageView avatar=new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int size=page.dp(80);
        card.addView(avatar,new LinearLayout.LayoutParams(size,size));
        avatar.setOnClickListener(new View.OnClickListener(){public void onClick(View v){pickAvatar(activity,page);}});
        TextView change=page.label("点击更换头像",12);
        change.setTextColor(page.secondary);change.setGravity(Gravity.CENTER);
        change.setPadding(0,page.dp(8),0,0);card.addView(change);

        // 昵称（点一下直接改）
        final TextView nickname=page.label("加载中…",20);
        nickname.setTypeface(MiuixUi.medium());
        nickname.setGravity(Gravity.CENTER);nickname.setPadding(0,page.dp(10),0,0);
        card.addView(nickname);
        // 资料还没到：昵称先呼吸，避免一个静止的"加载中…"
        Motion.breath(nickname);
        nickname.setOnClickListener(new View.OnClickListener(){public void onClick(View v){editName(activity,page,nickname);}});
        TextView hint=page.label("点击昵称可直接修改",12);
        hint.setTextColor(page.secondary);hint.setGravity(Gravity.CENTER);
        hint.setPadding(0,page.dp(4),0,page.dp(6));card.addView(hint);

        // 四个计数：关注 / 粉丝 / 人设 / 点赞
        final LinearLayout stats=new LinearLayout(activity);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setPadding(0,page.dp(22),0,page.dp(8));
        final TextView[] values=new TextView[STAT_KEYS.length];
        for(int i=0;i<STAT_KEYS.length;i++){
            LinearLayout column=new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER);
            TextView value=page.label("0",22);
            value.setTypeface(MiuixUi.medium());
            value.setGravity(Gravity.CENTER);
            TextView label=page.label(STAT_LABELS[i],13);
            label.setTextColor(page.secondary);label.setGravity(Gravity.CENTER);
            label.setPadding(0,page.dp(4),0,0);
            column.addView(value);column.addView(label);
            values[i]=value;
            stats.addView(column,new LinearLayout.LayoutParams(0,-2,1));
        }
        card.addView(stats,new LinearLayout.LayoutParams(-1,-2));

        // 消息中心：别人评论/回复我的时候，这里会有红点
        page.section("消息");
        LinearLayout notices=page.card();
        NoticePage.attachEntry(activity,notices,page.text);

        page.section("账号");
        LinearLayout safety=page.card();
        page.rowIcon(safety,MiuixIcon.SHIELD,"安全设置 ›",new Runnable(){public void run(){SafetyPage.show(activity);}});
        page.rowIcon(safety,MiuixIcon.GAUGE,"风控对抗 ›",new Runnable(){public void run(){AntiRiskPage.show(activity);}});
        page.rowIcon(safety,MiuixIcon.PERSON,"KOOK 绑定："+BindGate.boundText(activity)+" ›",new Runnable(){public void run(){
            if(BindGate.require(activity,new Runnable(){public void run(){
                activity.runOnUiThread(new Runnable(){public void run(){android.widget.Toast.makeText(activity,"已绑定："+BindGate.boundText(activity),android.widget.Toast.LENGTH_LONG).show();}});
            }})){ }
        }});

        final Runnable bind=new Runnable(){public void run(){
            Motion.stopBreath(nickname);
            JSONObject profile=UserProfile.get();
            nickname.setText(UserProfile.nickname(activity));
            // 赞助用户：灿金 + 扫光
            SponsorName.apply(nickname,UserProfile.isSponsor(activity),page.text);
            AvatarUi.show(avatar,UserProfile.avatarUrl(profile));
            for(int i=0;i<STAT_KEYS.length;i++)values[i].setText(String.valueOf(UserProfile.value(profile,STAT_KEYS[i])));
        }};
        bind.run();
        UserProfile.load(activity,new UserProfile.Callback(){
            public void done(JSONObject profile,String error){
                if(!page.active())return;
                if(error!=null){page.message(error);return;}
                bind.run();
                // 首次（铺过骨架时）让真实内容错开浮现，骨架同步淡出
                if(skeleton!=null){
                    FeedUi.fadeOutAndRemove(skeleton);
                    FeedUi.revealBlocks(card,null,true);
                }
            }
        });
        return page;
    }

    /** 点头像：选图 → 压缩 → 上传。 */
    private static void pickAvatar(final Activity activity,final CenterPage page){
        pending=activity;
        pendingReload=new Runnable(){public void run(){show(activity);}};
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try{activity.startActivityForResult(intent,AvatarUi.REQUEST);}
        catch(Throwable t){page.message("没有找到图片选择器");}
    }

    static Runnable pendingReload(){return pendingReload;}

    /** 选图回来：后台压缩再上传，成功后刷新页面。 */
    static void handleResult(final Activity activity,int request,int result,Intent data){
        if(request!=AvatarUi.REQUEST)return;
        final Runnable reload=pendingReload;
        pendingReload=null;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return;
        final Uri uri=data.getData();
        Toast.makeText(activity,"正在压缩并上传头像…",Toast.LENGTH_SHORT).show();
        new Thread(new Runnable(){
            public void run(){
                String failure=null;
                byte[] bytes=null;
                try{bytes=AvatarUi.compress(activity.getApplicationContext(),uri);}
                catch(Throwable t){failure="图片读取失败，请换一张";}
                if(bytes==null){
                    final String message=failure==null?"图片读取失败，请换一张":failure;
                    MAIN.post(new Runnable(){public void run(){Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}});
                    return;
                }
                final String base64=Base64.encodeToString(bytes,Base64.NO_WRAP);
                UserProfile.uploadAvatar(activity,base64,new UserProfile.Callback(){
                    public void done(final JSONObject profile,final String error){
                        MAIN.post(new Runnable(){public void run(){
                            Toast.makeText(activity,error==null?"头像已更新":"头像上传失败："+error,Toast.LENGTH_LONG).show();
                            if(error==null&&reload!=null)reload.run();
                        }});
                    }
                });
            }
        },"dekk-profile-upload").start();
    }

    /** 点昵称：弹输入框直接改。 */
    private static void editName(final Activity activity,final CenterPage page,final TextView view){
        final EditText input=new EditText(activity);
        input.setSingleLine(true);
        input.setText(UserProfile.nickname(activity));
        input.setSelection(input.getText().length());
        new ModuleDialog(activity).setTitle("修改昵称").setView(input)
                .setNegativeButton("取消",null)
                .setPositiveButton("保存",new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface dialog,int which){
                        final String name=input.getText().toString().trim();
                        if(name.length()==0){page.message("昵称不能为空");return;}
                        if(name.length()>30){page.message("昵称最多 30 个字");return;}
                        UserProfile.saveName(activity,name,new UserProfile.Callback(){
                            public void done(JSONObject profile,String error){
                                if(!page.active())return;
                                if(error!=null){page.message(error);return;}
                                view.setText(name);
                                page.message("昵称已更新");
                            }
                        });
                    }}).show();
    }
}