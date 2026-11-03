package com.dekk.yanyu;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** ilke 转账卡片的 Java 7 View：领取、退回、通知重试及持久状态刷新。 */
final class TransferCardView extends LinearLayout {
    interface Actions {
        void decide(VirtualTransferProtocol.Id id, boolean received) throws Exception;
        void retry(VirtualTransferStore.Record record) throws Exception;
    }
    private final VirtualTransferStore store;
    private VirtualTransferProtocol.Id id;
    private String amount;
    private boolean streaming, user, listening;
    private Actions actions;
    private AlertDialog dialog;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener(){
        public void onSharedPreferenceChanged(SharedPreferences p,String key){
            if(id!=null&&("transfer:"+id.key).equals(key))post(new Runnable(){public void run(){refresh();}});
        }
    };
    TransferCardView(Context context,VirtualTransferStore store){
        super(context);this.store=store;setOrientation(VERTICAL);
    }
    void bind(VirtualTransferProtocol.Id id,String amount,boolean user,boolean streaming,Actions actions){
        if(this.id!=null&&(id==null||!this.id.key.equals(id.key)))closeDialog();
        this.id=id;this.amount=amount;this.user=user;this.streaming=streaming;this.actions=actions;refresh();
    }
    private void refresh(){
        if(amount==null)return;
        removeAllViews();setGravity(user?android.view.Gravity.RIGHT:android.view.Gravity.LEFT);
        try{
            VirtualTransferStore.Record record=id==null?null:store.get(id);
            boolean returned=record!=null&&!record.received;
            String status=record==null?"待领取":returned?"已退回":user?"已收款":"已领取";
            LinearLayout card=new LinearLayout(getContext());card.setOrientation(VERTICAL);
            card.setPadding(dp(16),dp(12),dp(16),dp(12));
            GradientDrawable bg=new GradientDrawable();bg.setColor(record==null?0xFFFFE6B8:0xFFFFF2D8);
            bg.setCornerRadius(dp(8));bg.setStroke(dp(1),0xFFE6C88F);card.setBackground(bg);
            card.setFocusable(true);card.setContentDescription("虚拟转账 ¥"+amount+" "+status);
            card.addView(label("虚拟转账",12,0xFF866526));
            TextView money=label("¥ "+amount,26,0xFF252525);money.setTypeface(Typeface.create("sans-serif-medium",0));
            money.setPadding(0,dp(8),0,dp(6));card.addView(money);
            card.addView(label(status,14,0xFF866526));
            if(!user&&record!=null&&record.delivery!=VirtualTransferStore.SENT)
                card.addView(label(record.delivery==1?"通知确认中":record.delivery==3?"通知未送达，点击重试":"通知待发送",11,0xFF777777));
            card.setOnClickListener(new OnClickListener(){public void onClick(View v){details();}});
            addView(card,new LayoutParams(Math.min(dp(250),(int)(getResources().getDisplayMetrics().widthPixels*.65f)),-2));
        }catch(IllegalStateException e){addView(label("转账记录读取失败",14,0xFF777777));}
    }
    private void details(){
        closeDialog();
        if(id==null||streaming){toast("请等待回复完成");return;}
        try{
            final VirtualTransferStore.Record record=store.get(id);
            AlertDialog.Builder builder=new AlertDialog.Builder(getContext()).setTitle("虚拟转账 ¥"+amount).setNegativeButton("关闭",null);
            if(record==null&&!user){
                builder.setPositiveButton("领取",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){decide(true);}});
                builder.setNeutralButton("退回",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){decide(false);}});
            }else if(record!=null){
                builder.setMessage(record.received?"已领取":"已退回");
                if(record.delivery!=2)builder.setPositiveButton("重试通知",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){
                    try{if(actions==null)throw new IllegalStateException("当前会话不可用");actions.retry(record);refresh();}catch(Exception e){toast(e.getMessage());}
                }});
            }
            dialog=builder.show();
        }catch(Exception e){toast("转账记录读取失败");}
    }
    private void decide(boolean received){try{
        if(actions==null)throw new IllegalStateException("当前会话不可用");actions.decide(id,received);refresh();
    }catch(Exception e){toast(e.getMessage());}}
    void release(){closeDialog();unlisten();actions=null;}
    private void closeDialog(){if(dialog!=null)dialog.dismiss();dialog=null;}
    private void unlisten(){if(listening)store.preferences.unregisterOnSharedPreferenceChangeListener(listener);listening=false;}
    protected void onAttachedToWindow(){super.onAttachedToWindow();if(!listening)store.preferences.registerOnSharedPreferenceChangeListener(listener);listening=true;refresh();}
    protected void onDetachedFromWindow(){closeDialog();unlisten();super.onDetachedFromWindow();}
    private TextView label(String text,int size,int color){TextView view=new TextView(getContext());view.setText(text);view.setTextSize(size);view.setTextColor(color);view.setIncludeFontPadding(false);return view;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(getContext(),text==null?"通知发送失败":text,Toast.LENGTH_LONG).show();}
}
