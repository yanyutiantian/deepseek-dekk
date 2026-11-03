package com.dekk.yanyu;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 伪封号：Miuix 开关行 + 剩余时间；AI 封号生效期间不可修改。 */
final class FakeBanCard {
    static View create(final Activity a,boolean dark,int textColor,int subColor){
        final LinearLayout card=MiuixUi.card(a,dark);
        final M3SwitchView toggle=MiuixUi.switchRow(a,card,dark,MiuixIcon.BELL,"伪封号",FakeBanStore.active(a),null);
        final TextView status=MiuixUi.caption(a,card,dark,"");
        final Runnable update=new Runnable(){public void run(){
            boolean active=FakeBanStore.active(a);
            toggle.setEnabled(!AiBanControl.locked(a));
            toggle.setChecked(active);
            status.setText(active?"剩余 "+FakeBanStore.remainingText(a)+" · 点击重新设定时间"
                    :"本地模拟，默认关闭。开启时选择年月日和时分，到期自动解除；可随时关闭。");
        }};
        final Runnable edit=new Runnable(){public void run(){if(AiBanControl.locked(a))return;FakeBanDeadlinePicker.show(a,update);}};
        toggle.setOnCheckedChangeListener(new M3SwitchView.OnCheckedChangeListener(){
            public void onCheckedChanged(M3SwitchView v,boolean checked){
                if(checked){update.run();edit.run();}else{FakeBanStore.stop(a);update.run();}
            }
        });
        status.setOnClickListener(new View.OnClickListener(){public void onClick(View v){edit.run();}});
        final Runnable ticker=new Runnable(){public void run(){update.run();if(card.getWindowToken()!=null)card.postDelayed(this,1000);}};
        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){card.removeCallbacks(ticker);card.post(ticker);}
            public void onViewDetachedFromWindow(View v){card.removeCallbacks(ticker);}
        });
        update.run();
        return card;
    }
}
