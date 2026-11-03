package com.dekk.yanyu;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.TextView;
import com.dekk.yanyu.M3SwitchView.OnCheckedChangeListener;
final class AntiRecallCard {
    static View create(final Activity activity, boolean z, int i, int i2) {
        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setOrientation(1);
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(z ? -14079703 : -1);
        gradientDrawable.setCornerRadius((float) dp(activity, 22));
        linearLayout.setBackground(gradientDrawable);
        LayoutParams anonymousClass100000000 = new LayoutParams(-1, -2);
        anonymousClass100000000.topMargin = dp(activity, 16);
        linearLayout.setLayoutParams(anonymousClass100000000);
        LinearLayout linearLayout2 = new LinearLayout(activity);
        linearLayout2.setGravity(16);
        linearLayout2.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16));
        TextView textView = new TextView(activity);
        String str = "防撤回";
        textView.setText(str);
        textView.setTextSize((float) 16);
        textView.setTextColor(i);
        linearLayout2.addView(textView, new LayoutParams(0, -2, (float) 1));
        final M3SwitchView m3SwitchView = new M3SwitchView(activity);
        m3SwitchView.setContentDescription(str);
        m3SwitchView.setTrackColors(-11703298, z ? 872415231 : 520093696);
        m3SwitchView.setChecked(AntiRecallHook.enabled(activity));
        m3SwitchView.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            public void onCheckedChanged(M3SwitchView m3SwitchView, boolean z) {
                AntiRecallHook.setEnabled(activity, z);
            }
        });
        linearLayout2.addView(m3SwitchView, new LayoutParams(dp(activity, 52), dp(activity, 32)));
        linearLayout2.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                m3SwitchView.performClick();
            }
        });
        linearLayout.addView(linearLayout2);
        TextView textView2 = new TextView(activity);
        textView2.setText("在撤回前保留撤回前的内容，如果重载模块，将无法恢复已撤回的内容");
        textView2.setTextSize((float) 13);
        textView2.setTextColor(i2);
        textView2.setPadding(dp(activity, 20), 0, dp(activity, 20), dp(activity, 20));
        linearLayout.addView(textView2);
        return linearLayout;
    }
private static int dp(Activity activity, int i) {
        return (int) ((((float) i) * activity.getResources().getDisplayMetrics().density) + 0.5f);
    }
}
