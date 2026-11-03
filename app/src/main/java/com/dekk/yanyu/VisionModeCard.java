package com.dekk.yanyu;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

/** 强开识图模式：Miuix 开关行。 */
final class VisionModeCard {
    static View create(final Activity activity,boolean dark,int textColor,int subColor){
        LinearLayout card=MiuixUi.card(activity,dark);
        MiuixUi.switchRow(activity,card,dark,MiuixIcon.IMAGE,"强开识图模式",VisionModeHook.isEnabled(activity),new M3SwitchView.OnCheckedChangeListener(){
            public void onCheckedChanged(M3SwitchView view,boolean checked){VisionModeHook.setEnabled(activity,checked);}
        });
        MiuixUi.caption(activity,card,dark,"强行打开已关闭的识图模式。");
        return card;
    }
}
