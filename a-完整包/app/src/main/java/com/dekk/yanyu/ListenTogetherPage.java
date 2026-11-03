package com.dekk.yanyu;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** 三级页面「一起听」：账号、找歌、播放控制、一起听。 */
public final class ListenTogetherPage {
    private final Activity activity;
    private final CenterPage page;
    private TextView accountName;
    private ImageView accountAvatar;
    private EditText keyword;
    private LinearLayout results;
    private TextView playerStatus;
    private TextView togetherStatus;
    private TextView lyricNow;
    private TextView lyricNext;
    private final android.os.Handler ticker=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick=new Runnable(){
        public void run(){
            // 每秒刷新：歌词跟着播放进度走；对方/AI 接受邀请后状态也能立刻变成「进行中」。
            if(!page.active())return;
            refresh();
            ticker.postDelayed(this,1000);
        }
    };
    private final MusicPlayer.Listener listener=new MusicPlayer.Listener(){
        public void onMusicState(int state,String message){refresh();}
    };

    public static void show(final Activity activity){
        final CenterPage p=new CenterPage(activity,"一起听",null).navigationKey("music-platform");
        LinearLayout platforms=p.card();
        p.row(platforms,"网易云一起听  ›",new Runnable(){public void run(){open(activity,false);}});
        p.row(platforms,"QQ音乐一起听  ›",new Runnable(){public void run(){open(activity,true);}});
        p.show();
    }
    private static void open(Activity a,boolean qq){
        if(!MusicPlatform.select(qq)){android.widget.Toast.makeText(a,"请先停止另一平台的一起听和播放",1).show();return;}
        new ListenTogetherPage(a);
    }

    private ListenTogetherPage(final Activity activity){
        this.activity=activity;
        this.page=new CenterPage(activity,MusicPlatform.qq()?"QQ音乐一起听":"网易云一起听",null).navigationKey("music-player");
        LinearLayout tabCard=page.card();
        tabCard.setPadding(page.dp(6),page.dp(6),page.dp(6),page.dp(6));
        // 每个分组先建进 body，再整体搬进自己的容器；
        // 切换时只切容器的显隐，不再按索引切片（索引切片一旦算错就会「点了没反应」）。
        final LinearLayout accountBox=box();
        int start=page.body.getChildCount();
        buildAccount();
        moveInto(start,accountBox);
        final LinearLayout musicBox=box();
        start=page.body.getChildCount();
        buildSearch();
        buildPlayer();
        moveInto(start,musicBox);
        final LinearLayout togetherBox=box();
        start=page.body.getChildCount();
        buildTogether();
        moveInto(start,togetherBox);
        page.body.addView(accountBox,new LinearLayout.LayoutParams(-1,-2));
        page.body.addView(musicBox,new LinearLayout.LayoutParams(-1,-2));
        musicBox.setVisibility(View.GONE);
        page.body.addView(togetherBox,new LinearLayout.LayoutParams(-1,-2));
        togetherBox.setVisibility(View.GONE);
        final LinearLayout[] boxes={accountBox,musicBox,togetherBox};
        final int[] current={0};
        MiuixUi.Tabs strip=MiuixUi.tabs(activity,page.night,new String[]{"账号","音乐","一起听"},0,
                new MiuixUi.TabListener(){
            public void onSelected(int index){
                int old=current[0];
                if(old==index)return;
                current[0]=index;
                for(int j=0;j<boxes.length;j++){
                    boxes[j].setVisibility(j==index?View.VISIBLE:View.GONE);
                }
                // 新内容沿点击方向轻推入，和胶囊流动同一个节奏
                FeedUi.pushIn(boxes[index],index>old?1:-1);
                scrollTop();
            }});
        tabCard.addView(strip.view);
        page.show();
        MusicPlayer.get().addListener(listener);
        refresh();
        ticker.postDelayed(tick,1000);
    }

    /** 切换后回到顶部，保证用户马上看到新内容。 */
    private void scrollTop(){
        try{
            android.view.ViewParent parent=page.body.getParent();
            while(parent!=null && !(parent instanceof android.widget.ScrollView))parent=parent.getParent();
            if(parent instanceof android.widget.ScrollView)((android.widget.ScrollView)parent).scrollTo(0,0);
        }catch(Throwable ignored){ }
    }

    /** 分组容器：每个页签一个，切换只改它的显隐。 */
    private LinearLayout box(){
        LinearLayout box=new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    /** 把 body 里刚建好的这一段内容整体搬进容器。 */
    private void moveInto(int start,LinearLayout box){
        while(page.body.getChildCount()>start){
            View child=page.body.getChildAt(start);
            page.body.removeViewAt(start);
            box.addView(child);
        }
    }

    private java.util.List<View> viewsFrom(int start){java.util.List<View> views=new java.util.ArrayList<View>();for(int i=start;i<page.body.getChildCount();i++)views.add(page.body.getChildAt(i));return views;}

    private void buildAccount(){
        if(MusicPlatform.qq()){buildQqAccount();return;}
        page.section("网易云账号");
        LinearLayout card=page.card();
        LinearLayout row=new LinearLayout(activity);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0,page.dp(10),0,page.dp(6));
        accountAvatar=new ImageView(activity);
        row.addView(accountAvatar,new LinearLayout.LayoutParams(page.dp(46),page.dp(46)));
        accountName=page.label("",14);
        accountName.setPadding(page.dp(12),0,0,0);
        row.addView(accountName,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(row);
        page.row(card,"短信登录  ›",new Runnable(){public void run(){
            MusicLoginDialog.showCaptcha(activity,new MusicLoginDialog.Done(){
                public void done(NeteaseLogin.Result result){refresh();}
            });
        }});
        page.row(card,"CK 登录  ›",new Runnable(){public void run(){
            MusicLoginDialog.showCookie(activity,new MusicLoginDialog.Done(){
                public void done(NeteaseLogin.Result result){refresh();}
            });
        }});
        page.row(card,"刷新账号信息  ›",new Runnable(){public void run(){
            async(new Task(){
                public String run(){
                    NeteaseLogin.Result result=NeteaseLogin.refresh(activity);
                    return result.ok?"账号信息已更新":result.message;
                }
            });
        }});
        page.row(card,"接口诊断  ›",new Runnable(){public void run(){diagnose();}});
        page.row(card,"重置登录设备标识  ›",new Runnable(){public void run(){
            NeteaseDevice.reset();
            toast("设备标识已重置：重新登录试试（网络异常/风控时用）");
        }});
        page.row(card,"退出登录  ›",new Runnable(){public void run(){
            NeteaseAccount.clear(activity);
            MusicPlayer.get().stop();
            toast("已退出网易云账号");
            refresh();
        }}).setTextColor(0xFFD84A4A);
        page.caption(card,"如果登录时触发滑块验证码，完成后点「我已完成」即可直接重试原请求，不等待本地完成检测；结果以网易云返回为准。"
                +"登录只为获取播放所需的 CK（MUSIC_U）。密码与验证码只在本次登录使用、不会保存；CK 用 AndroidKeyStore 加密后存在本机。登录可能触发网易云风控，风险由使用者自行承担。");
    }

    private void buildQqAccount(){
        page.section("QQ音乐账号");LinearLayout c=page.card();
        LinearLayout row=new LinearLayout(activity);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0,page.dp(10),0,page.dp(6));accountAvatar=new ImageView(activity);
        row.addView(accountAvatar,new LinearLayout.LayoutParams(page.dp(46),page.dp(46)));
        accountName=page.label("",14);accountName.setPadding(page.dp(12),0,0,0);
        row.addView(accountName,new LinearLayout.LayoutParams(0,-2,1));c.addView(row);
        page.row(c,"刷新账号信息  ›",new Runnable(){public void run(){async(new Task(){public String run(){
            try{QqMusicProfile.refresh(activity);activity.runOnUiThread(new Runnable(){public void run(){refresh();}});return "账号信息已更新";}
            catch(Exception e){return "刷新失败："+e.getMessage();}
        }});}});
        page.row(c,"设备信息  ›",new Runnable(){public void run(){new ModuleDialog(activity).setTitle("QQ音乐设备信息").setMessage(QqMusicDevice.description(activity)).setPositiveButton("关闭",null).show();}});
        page.row(c,"重置播放器设备标识  ›",new Runnable(){public void run(){QqMusicDevice.reset(activity);toast("播放器 GUID 已重置，下次获取播放地址时生效");}});
        page.row(c,"QQ 二维码登录",new Runnable(){public void run(){QqMusicLogin.web(activity,false);}});
        page.row(c,"微信二维码登录",new Runnable(){public void run(){QqMusicLogin.web(activity,true);}});
        page.row(c,"CK 登录",new Runnable(){public void run(){QqMusicLogin.cookie(activity);}});
        page.row(c,"退出登录",new Runnable(){public void run(){QqMusicAccount.clear(activity);ListenTogether.stop();MusicPlayer.get().stop();refresh();}});
        page.caption(c,"二维码由 QQ 音乐官方授权页提供。遇到人机验证请手动完成，再点已完成登录 / 验证。QQ 和微信账号的音乐资产与会员不互通。");
    }

    private void buildSearch(){
        page.section("找歌");
        LinearLayout card=page.card();
        keyword=page.field(card,"歌曲名或歌手",1,false);
        page.row(card,"搜索  ›",new Runnable(){public void run(){search();}}).setTextColor(0xFF3482FF);
        results=new LinearLayout(activity);
        results.setOrientation(LinearLayout.VERTICAL);
        page.body.addView(results);
    }

    private void buildPlayer(){
        page.section("播放");
        LinearLayout card=page.card();
        playerStatus=page.label("没有正在播放的歌曲",13);
        playerStatus.setTextColor(page.secondary);
        playerStatus.setPadding(0,page.dp(8),0,page.dp(8));
        card.addView(playerStatus);
        page.row(card,"播放 / 暂停  ›",new Runnable(){public void run(){
            MusicPlayer.get().toggle();
            refresh();
        }});
        page.row(card,"上一首  ›",new Runnable(){public void run(){
            MusicPlayer.get().previous(activity);
            refresh();
        }});
        page.row(card,"下一首  ›",new Runnable(){public void run(){
            MusicPlayer.get().next(activity);
            refresh();
        }});
        page.row(card,"停止播放  ›",new Runnable(){public void run(){
            MusicPlayer.get().stop();
            refresh();
        }});
        page.row(card,"音质：" + qualityLabel() + "  ›",new Runnable(){public void run(){
            if(MusicPlatform.qq()){toast("QQ音乐使用官网返回的可用音质");return;}
            int next=MusicSettings.quality(activity)==NeteaseApi.BR_HIGH?NeteaseApi.BR_MID
                    :(MusicSettings.quality(activity)==NeteaseApi.BR_MID?NeteaseApi.BR_LOW:NeteaseApi.BR_HIGH);
            MusicSettings.setQuality(activity,next);
            MusicPlayer.get().setQuality(next);
            new ListenTogetherPage(activity);
        }});
        switchRow(card,"显示右上角迷你播放条",MusicSettings.miniBar(activity),new Switch(){
            public void set(boolean value){
                MusicSettings.setMiniBar(activity,value);
                if(value)MusicMiniBar.attach(activity);
                else MusicMiniBar.detach();
            }
        });
        page.caption(card,"迷你条固定在页面右上角（标题右侧），开着就一直显示，和一起听有没有同意无关。");
        page.caption(card,"版权或会员限制时会自动改为「静默同步」：只对齐进度，不出声。");

        page.section("歌词");
        LinearLayout lyricCard=page.card();
        lyricNow=page.label("暂无歌词",15);
        lyricNow.setPadding(0,page.dp(8),0,page.dp(2));
        lyricNext=page.label("",12);
        lyricNext.setTextColor(page.secondary);
        lyricNext.setPadding(0,0,0,page.dp(10));
        lyricCard.addView(lyricNow);
        lyricCard.addView(lyricNext);
        page.caption(lyricCard,"歌词按播放进度同步；一起听时两边共用同一份进度，看到的句子是一致的。");
    }

    private void buildTogether(){
        page.section("一起听");
        LinearLayout card=page.card();
        togetherStatus=page.label("",13);
        togetherStatus.setTextColor(page.secondary);
        togetherStatus.setPadding(0,page.dp(8),0,page.dp(8));
        card.addView(togetherStatus);
        page.row(card,"发起邀请  ›",new Runnable(){public void run(){
            MusicHook.sendToken(activity,MusicProtocol.invite());
            ListenTogether.inviteNote();
            refresh();
            if(!MusicSettings.aiPrompt(activity))toast("已发出邀请；AI 不会回应，先打开下面的「给 AI 附加一起听隐藏说明」");
        }});
        page.row(card,"接受对方的邀请  ›",new Runnable(){public void run(){
            MusicHook.sendToken(activity,MusicProtocol.accept());
            ListenTogether.decide(new MusicProtocol.Token(MusicProtocol.ACCEPT,"",""));
            refresh();
        }});
        page.row(card,"拒绝邀请  ›",new Runnable(){public void run(){
            MusicHook.sendToken(activity,MusicProtocol.refuse());
            ListenTogether.decide(new MusicProtocol.Token(MusicProtocol.REFUSE,"",""));
            refresh();
        }});
        page.row(card,"停止一起听  ›",new Runnable(){public void run(){
            ListenTogether.stop();
            MusicPlayer.get().stop();
            refresh();
        }});
        switchRow(card,"每次发消息自动声明当前音乐",MusicSettings.shareNow(activity),new Switch(){
            public void set(boolean value){MusicSettings.setShareNow(activity,value);}
        });
        switchRow(card,"给 AI 附加一起听隐藏说明",MusicSettings.aiPrompt(activity),new Switch(){
            public void set(boolean value){MusicSettings.setAiPrompt(activity,value);}
        });
        page.caption(card,"一起听靠聊天消息传递：邀请/接受/拒绝和分享歌曲都是会话里的一行标记。"
                +"在播歌时客户端会在每条消息里自动附上「当前音乐」的说明，"
                +"开启说明后 AI 会在被邀请时回应、也能主动换歌；这些标记都会从气泡里隐藏。");
    }

    private void search(){
        final String text=keyword.getText().toString().trim();
        if(text.length()==0){toast("请输入歌曲名或歌手");return;}
        if(!MusicPlatform.loggedIn(activity)){toast("请先登录当前平台账号");return;}
        results.removeAllViews();
        TextView loading=page.label("搜索中…",13);
        loading.setTextColor(page.secondary);
        results.addView(loading);
        async(new Task(){
            public String run(){
                try{
                    final List<NeteaseApi.Track> tracks;
                    if(MusicPlatform.qq())tracks=QqMusicApi.search(text,QqMusicAccount.cookie(activity));
                    else{NeteaseApi.Reply reply=NeteaseApi.search(text,20,NeteaseAccount.cookie(activity));
                    if(!reply.ok)return "搜索失败："+reply.error;tracks=NeteaseApi.parseSongs(reply);}
                    activity.runOnUiThread(new Runnable(){
                        public void run(){renderResults(tracks);}
                    });
                    return "";
                }catch(Throwable t){
                    return "搜索异常："+t.getClass().getSimpleName();
                }
            }
        });
    }

    private void renderResults(final List<NeteaseApi.Track> tracks){
        results.removeAllViews();
        if(tracks.isEmpty()){
            TextView empty=page.label("没有找到结果",13);
            empty.setTextColor(page.secondary);
            results.addView(empty);
            return;
        }
        LinearLayout card=new LinearLayout(activity);card.setOrientation(1);results.addView(card);
        for(final NeteaseApi.Track track:tracks){
            String label=track.label()+(track.trial?"（可能有试听限制）":"");
            page.row(card,label,new Runnable(){public void run(){
                MusicPlayer.get().play(activity,track,0L);
                toast("开始播放："+track.label());
                refresh();
            }});
        }
        TextView hint=page.label("点歌名即可播放；播放期间客户端会自动把「当前音乐」告诉 AI。",12);
        hint.setTextColor(page.secondary);
        hint.setPadding(page.dp(4),page.dp(8),0,page.dp(10));
        results.addView(hint);
    }

    private void refresh(){
        if(!page.active())return;
        String name=MusicPlatform.qq()?QqMusicAccount.name(activity):NeteaseAccount.name(activity);
        String cookie=MusicPlatform.qq()?QqMusicAccount.cookie(activity):NeteaseAccount.cookie(activity);
        accountName.setText(cookie.length()==0
                ?"未登录 · 点下面任一方式登录"
                :(name.length()==0?"已登录（"+NeteaseCookie.masked(cookie)+"）":name));
        MusicAvatar.load(accountAvatar,MusicPlatform.qq()?QqMusicAccount.avatar(activity):NeteaseAccount.avatar(activity),true,page.dp(46));
        MusicPlayer player=MusicPlayer.get();
        if(player.current()==null){
            playerStatus.setText("没有正在播放的歌曲");
        }else{
            StringBuilder text=new StringBuilder();
            text.append(player.title()).append("\n")
                    .append(MusicProtocol.time(player.position())).append(" / ")
                    .append(MusicProtocol.time(player.current().duration));
            if(player.silent())text.append(" · 静默同步");
            else if(player.state()==MusicPlayer.STATE_PAUSED)text.append(" · 已暂停");
            if(player.message().length()>0)text.append("\n").append(player.message());
            playerStatus.setText(text.toString());
        }
        StringBuilder together=new StringBuilder("状态：").append(ListenTogether.statusText());
        if(ListenTogether.note().length()>0)together.append("（").append(ListenTogether.note()).append("）");
        if(!MusicPlatform.loggedIn(activity))together.append("\n未登录时只能看到状态，不能播放或分享。");
        togetherStatus.setText(together.toString());

        if(lyricNow!=null){
            MusicLyric lyric=player.lyrics();
            long position=player.position();
            if(lyric.empty()){
                lyricNow.setText(player.current()==null?"暂无歌词":"正在加载歌词…");
                lyricNext.setText("");
            }else{
                String now=lyric.lineAt(position);
                lyricNow.setText(now.length()==0?"♪ 前奏":now);
                String next=lyric.nextLine(position);
                lyricNext.setText(next.length()==0?"":"下一句："+next);
            }
        }
    }

    /** 跑一遍诊断并把结果完整显示出来（可长按复制）。 */
    private void diagnose(){
        final String word=keyword==null?"":keyword.getText().toString().trim();
        toast("正在诊断…");
        Thread worker=new Thread(new Runnable(){
            public void run(){
                final String report=NeteaseDiag.run(activity.getApplicationContext(),word);
                activity.runOnUiThread(new Runnable(){
                    public void run(){showReport(report);}
                });
            }
        },"dekk-music-diag");
        worker.setDaemon(true);
        worker.start();
    }

    private void showReport(String report){
        TextView view=page.label(report,12);
        view.setTextIsSelectable(true);
        view.setPadding(page.dp(16),page.dp(12),page.dp(16),page.dp(12));
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);
        scroll.addView(view);
        new ModuleDialog(activity)
                .setTitle("接口诊断")
                .setView(scroll)
                .setPositiveButton("关闭",null)
                .show();
    }

    private String qualityLabel(){
        if(MusicPlatform.qq())return "官网可用音质";
        int quality=MusicSettings.quality(activity);
        return quality==NeteaseApi.BR_HIGH?"320k":(quality==NeteaseApi.BR_MID?"192k":"128k");
    }

    private interface Switch { void set(boolean value); }

    private void switchRow(LinearLayout card,String title,boolean checked,final Switch toggle){
        LinearLayout row=new LinearLayout(activity);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0,page.dp(10),0,page.dp(10));
        TextView label=page.label(title,16);
        row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        final M3SwitchView view=new M3SwitchView(activity);
        view.setContentDescription(title);
        view.setTrackColors(0xFF3482FF,page.night?0x33FFFFFF:0x1F000000);
        view.setChecked(checked);
        view.setOnCheckedChangeListener(new M3SwitchView.OnCheckedChangeListener(){
            public void onCheckedChanged(M3SwitchView switchView,boolean value){toggle.set(value);}
        });
        row.addView(view,new LinearLayout.LayoutParams(page.dp(52),page.dp(32)));
        row.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){view.performClick();}
        });
        card.addView(row);
    }

    private interface Task { String run(); }

    private void async(final Task task){
        new Thread(new Runnable(){
            public void run(){
                final String message;
                try{message=task.run();}catch(Throwable t){return;}
                if(message!=null&&message.length()>0){
                    activity.runOnUiThread(new Runnable(){
                        public void run(){toast(message);}
                    });
                }
            }
        },"dekk-music-task").start();
    }

    private void toast(String text){page.message(text);}
}