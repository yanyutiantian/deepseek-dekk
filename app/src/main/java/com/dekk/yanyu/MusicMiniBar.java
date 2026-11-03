package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 顶栏迷你播放条：固定在页面右上角（标题右侧、图标左侧那一块），
 * 只要「显示迷你播放条」开着就强制显示，和一起听有没有同意无关。
 * 没歌在播时显示一行「一起听」入口，点一下进三级页面。
 */
public final class MusicMiniBar {
    /** 右侧留出的图标宽度（喇叭 + 加号），避免压住宿主自己的按钮。 */
    private static final int RIGHT_MARGIN_DP=100;
    private static LinearLayout bar;
    private static ImageView cover;
    private static TextView title;
    private static TextView lyricView;
    private static ImageView toggle;
    private static ImageView next;
    private static ImageView open;
    private static MusicMiniStyle surface;
    private static Activity host;
    private static final ViewTreeObserver.OnGlobalLayoutListener REPOSITION=
            new ViewTreeObserver.OnGlobalLayoutListener(){
        public void onGlobalLayout(){place();}
    };

    private MusicMiniBar() { }

    static boolean attached(){return bar!=null&&bar.getParent()!=null;}

    static void detach(){
        if(bar!=null){
            ViewGroup parent=(ViewGroup)bar.getParent();
            if(parent!=null){
                try{
                    if(parent.getViewTreeObserver()!=null)
                        parent.getViewTreeObserver().removeOnGlobalLayoutListener(REPOSITION);
                }catch(Throwable ignored){ }
                try{parent.removeView(bar);}catch(Throwable ignored){ }
            }
        }
        bar=null;
        cover=null;
        title=null;
        lyricView=null;
        toggle=null;
        next=null;
        open=null;
        host=null;
    }

    /** 挂到右上角。任何一步失败都不抛异常，也不影响会话轮询。 */
    static void attach(final Activity activity){
        if(activity==null||attached())return;
        View root=activity.findViewById(android.R.id.content);
        if(!(root instanceof ViewGroup))return;
        LinearLayout created;
        try{
            created=build(activity);
        }catch(Throwable t){
            HotBridge.log("[DEKK][一起听] 迷你条构建失败: "+t);
            return;
        }
        try{
            FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity=Gravity.TOP|Gravity.END;
            params.topMargin=statusBar(activity)+dp(activity,6);
            params.rightMargin=dp(activity,RIGHT_MARGIN_DP);
            ((ViewGroup)root).addView(created,params);
            if(created.getParent()!=root)return;
            ((ViewGroup)root).getViewTreeObserver().addOnGlobalLayoutListener(REPOSITION);
        }catch(Throwable t){
            HotBridge.log("[DEKK][一起听] 迷你条挂载失败: "+t);
            return;
        }
        bar=created;
        host=activity;
        place();
        refresh();
        HotBridge.log("[DEKK][一起听] 迷你条已挂载（右上角）");
    }

    /** 尽量把条子压进顶栏那一行：找标题栏的高度，再垂直居中。 */
    private static void place(){
        if(bar==null||host==null)return;
        View root=host.findViewById(android.R.id.content);
        if(root==null||!(bar.getLayoutParams() instanceof FrameLayout.LayoutParams))return;
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)bar.getLayoutParams();
        int statusBar=statusBar(host);
        int top=statusBar+dp(host,6);
        try{
            View header=findHeader(root,statusBar,host);
            if(header!=null&&header.getHeight()>0&&bar.getHeight()>0){
                top=header.getTop()+Math.max(0,(header.getHeight()-bar.getHeight())/2);
            }else if(header!=null){
                top=header.getTop()+dp(host,8);
            }
        }catch(Throwable ignored){ }
        int margin=Math.max(statusBar,top),right=dp(host,RIGHT_MARGIN_DP);
        if(params.topMargin!=margin||params.rightMargin!=right){
            params.topMargin=margin;params.rightMargin=right;bar.setLayoutParams(params);
        }
    }

    /**
     * 找宿主顶栏：宽度占满、高度 40–96dp、位置贴着顶部的最上层容器。
     */
    private static View findHeader(View root,int statusBar,Context context){
        if(!(root instanceof ViewGroup))return null;
        int min=dp(context,40);
        int max=dp(context,96);
        int limit=statusBar+dp(context,40);
        View best=null;
        ViewGroup group=(ViewGroup)root;
        for(int i=0;i<group.getChildCount();i++){
            View child=group.getChildAt(i);
            if(!(child instanceof ViewGroup))continue;
            if(child.getWidth()!=root.getWidth())continue;
            int height=child.getHeight();
            if(height<min||height>max)continue;
            if(child.getTop()>limit)continue;
            if(best==null||child.getTop()<best.getTop())best=child;
        }
        return best;
    }

    private static LinearLayout build(Context context){
        LinearLayout row=new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context,8),dp(context,4),dp(context,4),dp(context,4));
        row.setMinimumHeight(dp(context,40));
        boolean night=(context.getResources().getConfiguration().uiMode&48)==32;
        surface=new MusicMiniStyle(night,context.getResources().getDisplayMetrics().density);
        row.setBackground(surface);
        row.setElevation(dp(context,2));

        cover=new ImageView(context);
        row.addView(cover,new LinearLayout.LayoutParams(dp(context,26),dp(context,26)));

        LinearLayout texts=new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(context,6),0,dp(context,4),0);
        title=new TextView(context);
        title.setTextSize(12);
        title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        title.setTextColor(ModuleTheme.text(night));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setMaxWidth(dp(context,86));
        lyricView=new TextView(context);
        lyricView.setTextSize(9);
        lyricView.setTextColor(ModuleTheme.secondary(night));
        lyricView.setSingleLine(true);
        lyricView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lyricView.setMaxWidth(dp(context,86));
        lyricView.setVisibility(View.GONE);
        texts.addView(title);
        texts.addView(lyricView);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));

        toggle=button(context,0,"播放或暂停",new Runnable(){public void run(){
            MusicPlayer.get().toggle();
            refresh();
        }});
        row.addView(toggle);
        next=button(context,2,"下一首",new Runnable(){public void run(){
            if(host!=null)MusicPlayer.get().next(host);
        }});
        row.addView(next);
        open=button(context,3,"播放模式",new Runnable(){public void run(){
            if(host==null)return;
            final String[] modes={"顺序播放","单曲循环"};
            new ModuleDialog(host).setTitle("播放模式")
                .setSingleChoiceItems(modes,MusicSettings.repeatOne(host)?1:0,new android.content.DialogInterface.OnClickListener(){
                    public void onClick(android.content.DialogInterface dialog,int which){
                        MusicSettings.setRepeatOne(host,which==1);refresh();dialog.dismiss();
                        android.widget.Toast.makeText(host,modes[which],android.widget.Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("取消",null).show();
        }});
        row.addView(open);
        row.setOnClickListener(new View.OnClickListener(){
            public void onClick(View view){
                if(host!=null)ListenTogetherPage.show(host);
            }
        });
        return row;
    }

    private static ImageView button(Context context,int icon,String label,final Runnable click){
        ImageView view=new ImageView(context);
        view.setImageDrawable(MusicMiniStyle.icon(icon,ModuleTheme.ACCENT));
        view.setContentDescription(label);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(context,30),dp(context,32)));
        view.setPadding(dp(context,6),dp(context,7),dp(context,6),dp(context,7));
        view.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ModuleTheme.ripple(false)),null,
                MiuixUi.shape(context,0xFF000000,10f)));
        view.setOnClickListener(new View.OnClickListener(){public void onClick(View v){click.run();}});
        return view;
    }

    static void refresh(){
        if(bar==null||title==null||lyricView==null||toggle==null||next==null||open==null||cover==null)return;
        MusicPlayer player=MusicPlayer.get();
        boolean has=player.current()!=null;
        boolean repeat=MusicSettings.repeatOne(bar.getContext());
        open.setImageDrawable(MusicMiniStyle.icon(repeat?4:5,ModuleTheme.ACCENT));
        open.setContentDescription(repeat?"单曲循环，点击切换":"顺序播放，点击切换");
        // 开关开着就一直在：没歌时收缩成「一起听」入口，不做隐藏。
        bar.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams sizing=(FrameLayout.LayoutParams)bar.getLayoutParams();
        int available=bar.getResources().getDisplayMetrics().widthPixels-dp(bar.getContext(),RIGHT_MARGIN_DP+76);
        int desired=dp(bar.getContext(),has?206:110);
        int target=Math.min(desired,Math.max(dp(bar.getContext(),120),available));
        if(sizing.width!=target){sizing.width=target;bar.setLayoutParams(sizing);}
        if(!has){
            surface.progress(0);
            cover.setVisibility(View.GONE);
            title.setText("一起听");
            lyricView.setVisibility(View.GONE);
            toggle.setVisibility(View.GONE);
            next.setVisibility(View.GONE);
            open.setVisibility(View.VISIBLE);
            return;
        }
        cover.setVisibility(View.VISIBLE);
        toggle.setVisibility(View.VISIBLE);
        next.setVisibility(target>=dp(bar.getContext(),206)?View.VISIBLE:View.GONE);
        open.setVisibility(View.VISIBLE);
        title.setText(player.title());
        toggle.setImageDrawable(MusicMiniStyle.icon(player.state()==MusicPlayer.STATE_PLAYING?1:0,ModuleTheme.ACCENT));
        surface.progress(player.current().duration>0?(float)player.position()/player.current().duration:0);
        String line=player.lyricLine();
        if(line.length()==0){
            lyricView.setVisibility(View.GONE);
            lyricView.setText("");
        }else{
            lyricView.setVisibility(View.VISIBLE);
            lyricView.setText(line);
        }
        MusicAvatar.load(cover,player.cover(),false,dp(bar.getContext(),26));
    }

    private static int statusBar(Context context){
        try{
            int id=context.getResources().getIdentifier("status_bar_height","dimen","android");
            if(id>0)return context.getResources().getDimensionPixelSize(id);
        }catch(Throwable ignored){ }
        return dp(context,24);
    }

    private static int dp(Context context,int value){
        return (int)(value*context.getResources().getDisplayMetrics().density+0.5f);
    }
}