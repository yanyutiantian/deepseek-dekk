package com.dekk.yanyu;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** 所有模块弹窗的窗口与内容规范；保留各页面原有按钮和回调。 */
final class ModuleDialog extends AlertDialog.Builder {
    ModuleDialog(Context context){super(context);}
    @Override public AlertDialog create(){
        final AlertDialog dialog=super.create();
        // create() 后各页面可能设置自己的 OnShowListener；布局监听不会覆盖这些业务回调。
        final View decor=dialog.getWindow().getDecorView();
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){v.post(new Runnable(){public void run(){if(dialog.isShowing())style(dialog);}});}
            public void onViewDetachedFromWindow(View v){}
        });
        return dialog;
    }
    static void style(AlertDialog dialog){
        Window window=dialog.getWindow();if(window==null)return;
        Context c=dialog.getContext();float density=c.getResources().getDisplayMetrics().density;
        boolean dark=(c.getResources().getConfiguration().uiMode&48)==32;
        GradientDrawable bg=new GradientDrawable();bg.setColor(ModuleTheme.surface(dark));bg.setCornerRadius(28*density);
        window.setBackgroundDrawable(bg);
        window.setLayout(Math.min((int)(c.getResources().getDisplayMetrics().widthPixels-40*density),(int)(520*density)),-2);
        for(int which:new int[]{-1,-2,-3}){Button button=dialog.getButton(which);if(button!=null){button.setAllCaps(false);button.setTextColor(which==-1?ModuleTheme.ACCENT:ModuleTheme.secondary(dark));button.setMinHeight((int)(48*density));}}
        // 各页面自己塞进来的输入框 / 数字选择器统一成灰色圆角输入框
        View content=dialog.findViewById(android.R.id.content);
        styleInputs(content,c,dark,density);
    }
    private static void styleInputs(View view,Context c,boolean dark,float density){
        if(view==null)return;
        if(view instanceof EditText){
            EditText input=(EditText)view;
            input.setTextSize(16);
            input.setTextColor(ModuleTheme.text(dark));
            input.setHintTextColor(ModuleTheme.secondary(dark));
            input.setBackground(MiuixUi.shape(c,ModuleTheme.field(dark),12));
            input.setPadding((int)(14*density),input.getPaddingTop(),(int)(14*density),input.getPaddingBottom());
        }
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)styleInputs(group.getChildAt(i),c,dark,density);
        }
    }
    @Override public AlertDialog show(){AlertDialog dialog=super.show();style(dialog);return dialog;}
}