package com.dekk.yanyu;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

/** 补充上下文：Miuix 开关行 + 要点行。 */
final class ContextSummaryCard {
    static View create(final Activity activity,boolean dark,int textColor,int subColor){
        LinearLayout card=MiuixUi.card(activity,dark);
        MiuixUi.switchRow(activity,card,dark,MiuixIcon.DOC,"补充上下文",ContextSummaryStore.enabled(activity),new M3SwitchView.OnCheckedChangeListener(){
            public void onCheckedChanged(M3SwitchView view,boolean checked){ContextSummaryStore.setEnabled(activity,checked);}
        });
        MiuixUi.caption(activity,card,dark,"开启后自动记录当前会话上一条提问，下次发送时作为上下文一并带去；撤回的回答只留在本地，不参与发送。");
        final MiuixUi.Row notes=MiuixUi.row(activity,card,dark,notesLabel(activity),"",true,null);
        notes.container.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                ContextSummaryEditor.notes(activity,new Runnable(){
                    public void run(){notes.title.setText(notesLabel(activity));}
                });
            }
        });
        return card;
    }
    private static String notesLabel(Activity activity){return "已确认要点："+ContextSummaryEditor.summary(activity);}
}
