package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 识别 AI/用户消息中的转账标记，隐藏标记并显示独立虚拟转账卡片。 */
final class TransferHook {
    private static final Set<String> seen=new HashSet<String>();
    private static Activity activity;
    private static LinearLayout panel;
    private TransferHook(){}

    static void install(ClassLoader loader){
        ConversationTransfers.install(loader);
    }

    static String promptBlock(Context context){
        if(context==null||!TransferStore.promptEnabled(context))return "";
        // 「回复分段」没开（或气泡没开）时，协议里不再要求 AI 输出 [SGMx] 标记，
        // 否则会出现"没开分段却被注入分段要求"的问题。
        boolean segmented=ConversationSegments.enabled(context)&&ChatBubbleHook.enabled(context);
        // 新版决策由当前会话的事件通知提供，不能附加旧版跨会话记录。
        return TransferProtocol.prompt(segmented);
    }

    static void tick(){ /* 卡片已由消息渲染链负责。 */ }

    private static void legacyTick(){
        try{
            String[] session=MusicSend.readSession();
            Activity a=DekkSettingsPage.currentActivity();
            if(session==null||a==null)return;
            // 总开关默认关闭：关闭时既不改气泡内容，也不显示转账卡片
            if(!TransferStore.promptEnabled(a))return;
            if(a!=activity){activity=a;panel=null;} // 换页面就把卡片面板重建一次
            Object owner=getOwner();if(owner==null)return;
            Object state=HotHelpers.callMethod(owner,"M");
            Map<?,?> messages=(Map<?,?>)HotHelpers.getObjectField(state,"f");
            for(Map.Entry<?,?> entry:messages.entrySet()){
                String text=messageText(entry.getValue());
                String amount=TransferProtocol.amount(text);
                if(amount.length()==0)continue;
                String key=session[0]+"#"+text.hashCode();
                // 正文里的隐藏标记要从气泡里去掉，只在卡片里体现
                hideMarker(entry.getValue(),text);
                if(!seen.add(key))continue;
                TransferStore.Record record=TransferStore.byKey(a,key);
                if(record==null)record=TransferStore.create(a,amount,true,key);
                showCard(a,record);
            }
        }catch(Throwable t){HotBridge.log("[DEKK][转账] 处理失败: "+t.getClass().getSimpleName());}
    }

    private static Object getOwner(){return MusicSend.owner();}
    private static String messageText(Object message){
        try{
            Object snapshot=HotHelpers.callMethod(message,"a");List<?> fragments=(List<?>)HotHelpers.getObjectField(snapshot,"v");
            StringBuilder b=new StringBuilder();for(Object f:fragments)if(f!=null&&"tt8".equals(f.getClass().getName())){Object x=HotHelpers.getObjectField(f,"c");if(x instanceof String)b.append(x);}
            return b.toString();
        }catch(Throwable t){return "";}
    }
    private static void hideMarker(Object message,String text){
        try{
            Object snapshot=HotHelpers.callMethod(message,"a");List<?> fragments=(List<?>)HotHelpers.getObjectField(snapshot,"v");
            for(Object f:fragments)if(f!=null&&"tt8".equals(f.getClass().getName())){
                Object x=HotHelpers.getObjectField(f,"c");if(x instanceof String&&TransferProtocol.has((String)x))HotHelpers.setObjectField(f,"c",TransferProtocol.strip((String)x));
            }
        }catch(Throwable ignored){ }
    }
    private static void showCard(final Activity host,final TransferStore.Record record){
        if(panel==null||panel.getParent()==null){
            panel=new LinearLayout(host);panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(host,14),dp(host,8),dp(host,14),dp(host,8));
            GradientDrawableCompat.background(panel,0xF2FFF7E6,dp(host,16));
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(host,252),-2,
                    Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);lp.bottomMargin=dp(host,92);
            try{((FrameLayout)host.findViewById(android.R.id.content)).addView(panel,lp);}catch(Throwable t){panel=null;return;}
        }
        LinearLayout card=new LinearLayout(host);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(0,dp(host,5),0,dp(host,5));
        TextView label=new TextView(host);
        label.setText((record.incoming?"AI 转账":"转账")+"  ¥"+record.amount);
        label.setTextSize(16);label.setTextColor(0xFF6D4C1D);
        card.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        final Button receive=new Button(host);
        receive.setTextSize(12);
        if(record.received){receive.setText("已收款");receive.setEnabled(false);}
        else{
            receive.setText("收款");
            receive.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
                TransferStore.receive(host,record.id);
                Toast.makeText(host,"已收款（虚拟转账）：¥"+record.amount,Toast.LENGTH_SHORT).show();
                receive.setEnabled(false);receive.setText("已收款");
            }});
        }
        card.addView(receive,new LinearLayout.LayoutParams(-2,-2));panel.addView(card);
        while(panel.getChildCount()>3)panel.removeViewAt(0);
    }
    private static int dp(Activity a,int n){return (int)(n*a.getResources().getDisplayMetrics().density+.5f);}
    private static final class GradientDrawableCompat{
        static void background(View v,int color,float radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(radius);v.setBackground(d);}
    }
}