package com.dekk.yanyu;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

/** AI 控制封号：Miuix 开关行。 */
final class AiBanCard {
    static View create(final Activity activity,boolean dark,int textColor,int subColor){
        LinearLayout card=MiuixUi.card(activity,dark);
        MiuixUi.switchRow(activity,card,dark,MiuixIcon.GEAR,"AI 控制封号",AiBanControl.enabled(activity),new M3SwitchView.OnCheckedChangeListener(){
            public void onCheckedChanged(M3SwitchView view,boolean checked){AiBanControl.setEnabled(activity,checked);}
        });
        MiuixUi.caption(activity,card,dark,"默认关闭。附加隐藏说明，允许 AI 触发 1~60 分钟本地禁言。已生效的 AI 禁言不能提前解除；关闭开关仅停止后续触发，到期自动恢复。");
        return card;
    }
}
