package com.dekk.yanyu;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;

/** 模块导航组件：独立标题、副标题和箭头，避免将全部信息塞进单行文本。 */
final class ModuleNavigation {
    static View tile(final Activity activity,boolean dark,String title,String summary,final Runnable click){
        int pad=dp(activity,20);
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad,pad,pad,pad);
        android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();
        shape.setColor(ModuleTheme.surface(dark));shape.setCornerRadius(dp(activity,20));
        row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(dark?0x20FFFFFF:0x125268D9),shape,shape));
        LinearLayout text=new LinearLayout(activity);text.setOrientation(1);
        TextView heading=new TextView(activity);heading.setText(title);heading.setTextSize(17);heading.setTextColor(ModuleTheme.text(dark));heading.setTypeface(null,Typeface.BOLD);text.addView(heading);
        if(summary.length()>0){TextView description=new TextView(activity);description.setText(summary);description.setTextSize(13);description.setTextColor(ModuleTheme.secondary(dark));description.setLineSpacing(dp(activity,3),1);description.setPadding(0,dp(activity,8),dp(activity,12),0);text.addView(description);}
        row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=new TextView(activity);arrow.setText("›");arrow.setTextSize(24);arrow.setTextColor(ModuleTheme.secondary(dark));row.addView(arrow);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(activity,12);row.setLayoutParams(lp);
        row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){click.run();}});return row;
    }
    static int dp(Activity a,int value){return (int)(value*a.getResources().getDisplayMetrics().density+0.5f);}
    private ModuleNavigation(){}
}