package com.dekk.yanyu;

import android.app.Activity;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 虚拟转账页：只看本地记录，不连接任何支付平台、不产生真实扣款。
 * AI 用 [转账&amp;金额] 标记发起，客户端隐藏标记并单独显示转账卡片，用户可以收款。
 */
public final class TransferPage {
    private TransferPage() { }

    public static void show(final Activity activity){
        final CenterPage page=new CenterPage(activity,"虚拟转账",null);
        page.section("说明");LinearLayout note=page.card();
        TextView hint=page.label("这是聊天内的虚拟转账（记账用），不涉及真实支付。"
                +"AI 只要在回复里写 [转账&金额] 就会被隐藏，并单独显示一张转账卡片，点「收款」即可完成收款；"
                +"收款结果会在下一次请求的隐藏上下文里告诉 AI。",12);
        hint.setTextColor(page.secondary);note.addView(hint);

        page.section("开关");LinearLayout setting=page.card();
        final M3SwitchView toggle=new M3SwitchView(activity);
        toggle.setContentDescription("虚拟转账提示词");
        toggle.setTrackColors(0xFF3482FF,page.night?0x33FFFFFF:0x1F000000);
        toggle.setChecked(TransferStore.promptEnabled(activity));
        toggle.setOnCheckedChangeListener(new M3SwitchView.OnCheckedChangeListener(){
            public void onCheckedChanged(M3SwitchView view,boolean on){
                TransferStore.setPromptEnabled(activity,on);
                page.message(on?"已开启：AI 可以发起虚拟转账":"已关闭：不再向 AI 说明转账协议");
            }});
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,page.dp(14),0,page.dp(14));
        TextView label=page.label("允许 AI 发起虚拟转账",16);
        row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(toggle,new LinearLayout.LayoutParams(page.dp(52),page.dp(32)));
        row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){toggle.performClick();}});
        setting.addView(row);

        page.section("给 AI 转一笔（虚拟）");LinearLayout send=page.card();
        final EditText amount=page.field(send,"金额（元）",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL,false);
        amount.setText("1");
        page.row(send,"发送转账  ›",new Runnable(){public void run(){
            String text=amount.getText().toString().trim();
            String value=TransferProtocol.amount("[转账&"+text+"]");
            if(value.length()==0){page.message("请输入正确的金额，例如 1.50");return;}
            if(MusicSend.send(activity.getApplicationContext(),"[转账&"+value+"]"))page.message("已发送转账 ¥"+value);
        }});

        page.section("记录");LinearLayout list=page.card();
        java.util.List<TransferStore.Record> all=TransferStore.all(activity);
        if(all.isEmpty()){
            TextView empty=page.label("暂无转账记录",13);empty.setTextColor(page.secondary);list.addView(empty);
        }
        for(final TransferStore.Record record:all){
            LinearLayout item=new LinearLayout(activity);item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(0,page.dp(10),0,page.dp(10));
            TextView text=page.label((record.incoming?"AI 转账":"我转给 AI")+"  ¥"+record.amount
                    +"  ·  "+(record.received?"已收款":"待收款"),15);
            item.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            if(!record.received){
                TextView action=page.label("收款",14);action.setTextColor(0xFF3482FF);
                action.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
                    TransferStore.receive(activity,record.id);show(activity);
                }});
                item.addView(action);
            }
            list.addView(item);
        }
        page.action("刷新记录",new Runnable(){public void run(){show(activity);}});
        page.show();
    }
}