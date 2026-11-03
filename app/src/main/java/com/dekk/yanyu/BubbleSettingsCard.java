package com.dekk.yanyu;
import android.view.View;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
final class BubbleSettingsCard {
    static void add(final CenterPage page){
        page.section("消息气泡");LinearLayout card=page.card();LinearLayout row=new LinearLayout(page.activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,page.dp(14),0,page.dp(14));
        TextView label=page.label("微信对话气泡",16);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        final M3SwitchView toggle=new M3SwitchView(page.activity);toggle.setContentDescription("微信对话气泡");toggle.setTrackColors(0xFF3482FF,page.night?0x33FFFFFF:0x1F000000);toggle.setChecked(ChatBubbleHook.enabled(page.activity));
        toggle.setOnCheckedChangeListener(new M3SwitchView.OnCheckedChangeListener(){public void onCheckedChanged(M3SwitchView view,boolean on){ChatBubbleHook.setEnabled(page.activity,on);page.message("已保存，重新进入聊天会话生效");}});
        row.addView(toggle,new LinearLayout.LayoutParams(page.dp(52),page.dp(32)));row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){toggle.performClick();}});card.addView(row);
        TextView description=page.label("用户与AI独立气泡，自适应消息宽度；思考区保留独立折叠。切换后重新进入会话生效。",12);description.setTextColor(page.secondary);card.addView(description);
        page.row(card,"用户气泡颜色  ›",new Runnable(){public void run(){BubbleStyleEditor.color(page,"bubble_user_color",BubbleStyle.userColor());}});
        page.row(card,"AI气泡颜色  ›",new Runnable(){public void run(){BubbleStyleEditor.color(page,"bubble_assistant_color",BubbleStyle.assistantColor());}});
        page.row(card,"气泡圆角  ›",new Runnable(){public void run(){BubbleStyleEditor.radius(page);}});

        // 回复分段由美化页的 SegmentsSettingsCard 独立提供。

        // 气泡最大宽度：滑块，实时保存（重进会话生效，与上面的开关一致）
        page.section("气泡宽度");
        LinearLayout widthCard=page.card();
        page.caption(widthCard,"气泡最大宽度占屏幕的百分比（40%–100%）。只限制上限，短消息仍按内容自适应。");
        LinearLayout widthRow=new LinearLayout(page.activity);
        widthRow.setGravity(Gravity.CENTER_VERTICAL);
        widthRow.setPadding(0,page.dp(6),0,page.dp(2));
        final android.widget.SeekBar widthBar=new android.widget.SeekBar(page.activity);
        widthBar.setMax(60);
        widthBar.setProgress(Math.max(0,Math.min(60,BubbleStyle.maxWidthPct()-40)));
        widthBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ModuleTheme.ACCENT));
        widthBar.setThumbTintList(android.content.res.ColorStateList.valueOf(ModuleTheme.ACCENT));
        widthRow.addView(widthBar,new LinearLayout.LayoutParams(0,page.dp(48),1f));
        final TextView widthValue=page.label(BubbleStyle.maxWidthPct()+"%",15);
        widthValue.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams widthValueParams=new LinearLayout.LayoutParams(page.dp(58),-2);
        widthValueParams.leftMargin=page.dp(10);
        widthRow.addView(widthValue,widthValueParams);
        widthBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar seekBar,int progress,boolean fromUser){
                widthValue.setText((progress+40)+"%");
                if(fromUser){BubbleStyle.setMaxWidthPct(progress+40);}
            }
            public void onStartTrackingTouch(android.widget.SeekBar seekBar){}
            public void onStopTrackingTouch(android.widget.SeekBar seekBar){
                BubbleStyle.setMaxWidthPct(seekBar.getProgress()+40);
                page.message("已保存，重新进入聊天会话生效");
            }
        });
        widthCard.addView(widthRow);

        // 聊天头像（移植自 ilke）：用户与 AI 各一张，选了就显示在气泡外侧
        page.section("聊天头像");LinearLayout avatarCard=page.card();
        LinearLayout avatarRow=new LinearLayout(page.activity);avatarRow.setGravity(Gravity.CENTER_VERTICAL);avatarRow.setPadding(0,page.dp(14),0,page.dp(14));
        TextView avatarLabel=page.label("聊天头像",16);avatarRow.addView(avatarLabel,new LinearLayout.LayoutParams(0,-2,1));
        final M3SwitchView avatarToggle=new M3SwitchView(page.activity);avatarToggle.setContentDescription("聊天头像");
        avatarToggle.setTrackColors(0xFF3482FF,page.night?0x33FFFFFF:0x1F000000);
        avatarToggle.setChecked(ChatAvatarStore.enabled(page.activity));
        avatarToggle.setOnCheckedChangeListener(new M3SwitchView.OnCheckedChangeListener(){public void onCheckedChanged(M3SwitchView view,boolean on){
            ChatAvatarStore.setEnabled(page.activity,on);ChatAvatarStore.preload();page.message("已保存，重新进入聊天会话生效");}});
        avatarRow.addView(avatarToggle,new LinearLayout.LayoutParams(page.dp(52),page.dp(32)));
        avatarRow.setOnClickListener(new View.OnClickListener(){public void onClick(View v){avatarToggle.performClick();}});avatarCard.addView(avatarRow);
        page.row(avatarCard,"设置我的头像  ›",new Runnable(){public void run(){ChatAvatarStore.pick(page.activity,true);}});
        page.row(avatarCard,"设置 AI 头像  ›",new Runnable(){public void run(){ChatAvatarStore.pick(page.activity,false);}});
        page.row(avatarCard,"头像大小："+BubbleStyle.avatarSizeDp()+"dp（点一下切换）  ›",new Runnable(){public void run(){
            int next=BubbleStyle.avatarSizeDp()>=48?28:(BubbleStyle.avatarSizeDp()>=40?48:40);
            BubbleStyle.setAvatarSizeDp(next);BeautifyPage.show(page.activity);
        }});
        page.row(avatarCard,"清除头像  ›",new Runnable(){public void run(){
            ChatAvatarStore.clear(page.activity,true);ChatAvatarStore.clear(page.activity,false);
            page.message("已清除头像（重新进入会话生效）");
        }});
        TextView avatarNote=page.label("从相册选图即可，会自动裁成圆形；用户头像在气泡右侧，AI 头像在左侧。选完进入会话生效；"
                +"需要先打开上面的「微信对话气泡」。",12);
        avatarNote.setTextColor(page.secondary);avatarCard.addView(avatarNote);
    }
}