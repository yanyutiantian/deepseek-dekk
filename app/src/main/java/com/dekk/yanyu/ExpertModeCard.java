package com.dekk.yanyu;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

/** 强开专家模式：Miuix 开关行。 */
final class ExpertModeCard {
    static View create(final Activity activity,boolean dark,int textColor,int subColor){
        LinearLayout card=MiuixUi.card(activity,dark);
        MiuixUi.switchRow(activity,card,dark,MiuixIcon.STAR,"强开专家模式",ExpertModeHook.isEnabled(activity),new M3SwitchView.OnCheckedChangeListener(){
            public void onCheckedChanged(M3SwitchView view,boolean checked){ExpertModeHook.setEnabled(activity,checked);}
        });
        MiuixUi.caption(activity,card,dark,"强行打开已关闭的专家模式。");
        return card;
    }
}
