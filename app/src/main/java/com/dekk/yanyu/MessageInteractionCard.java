package com.dekk.yanyu;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.LinearLayout;

final class MessageInteractionCard {
    static void add(final CenterPage page){
        page.section("消息互动");
        LinearLayout card=page.card();
        MiuixUi.switchRow(page.activity,card,page.night,MiuixIcon.CHAT,"虚拟转账",TransferStore.promptEnabled(page.activity),new M3SwitchView.OnCheckedChangeListener(){
            public void onCheckedChanged(M3SwitchView view,boolean checked){TransferStore.setPromptEnabled(page.activity,checked);}
        });
        MiuixUi.switchRow(page.activity,card,page.night,MiuixIcon.CHAT,"主动消息",ProactiveMessages.enabled(page.activity),new M3SwitchView.OnCheckedChangeListener(){
            public void onCheckedChanged(M3SwitchView view,boolean checked){ProactiveMessages.setEnabled(page.activity,checked);}
        });
        final android.widget.TextView[] intervalLabel=new android.widget.TextView[1];
        intervalLabel[0]=page.row(card,"主动消息间隔："+ProactiveMessages.minutes(page.activity)+" 分钟 ›",new Runnable(){public void run(){
            final int[] values={1,3,5,10,30,60};
            new AlertDialog.Builder(page.activity).setTitle("空闲间隔").setItems(new String[]{"1 分钟","3 分钟","5 分钟","10 分钟","30 分钟","60 分钟"},new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
                ProactiveMessages.setMinutes(page.activity,values[which]);
                intervalLabel[0].setText("主动消息间隔："+values[which]+" 分钟");
            }}).setNegativeButton("取消",null).show();
        }});
        page.caption(card,"转账卡片可领取或退回，结果会通知 AI。主动消息仅在当前会话前台空闲时触发，每次用户发言最多一次；触发请求会保留在会话中。");
    }
    private MessageInteractionCard(){}
}