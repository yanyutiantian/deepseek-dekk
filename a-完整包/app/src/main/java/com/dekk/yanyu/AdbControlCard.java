package com.dekk.yanyu;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings entry for the third level ADB page. */
public final class AdbControlCard {
    private AdbControlCard() { }

    static View create(final Activity activity, boolean dark, int textColor, int subColor) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(ModuleTheme.surface(dark));
        background.setCornerRadius(dp(activity, 22));
        card.setBackground(background);
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.topMargin = dp(activity, 16);
        card.setLayoutParams(space);

        TextView title = new TextView(activity);
        title.setText("ADB操控");
        title.setTextSize(16);
        title.setTextColor(textColor);
        card.addView(title);

        TextView description = new TextView(activity);
        description.setText(describe(activity));
        description.setTextSize(13);
        description.setTextColor(subColor);
        description.setPadding(0, dp(activity, 6), 0, 0);
        card.addView(description);

        TextView open = new TextView(activity);
        open.setText("打开 ADB操控面板  ›");
        open.setTextSize(15);
        open.setTextColor(textColor);
        open.setPadding(0, dp(activity, 12), 0, 0);
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AdbControlPage.show(activity);
            }
        });
        card.addView(open);
        return card;
    }

    private static String describe(Activity activity) {
        boolean freeze = AdbControlStore.allowFreeze(activity);
        boolean usage = AdbControlStore.allowUsage(activity);
        String state = freeze || usage
                ? "AI权限：" + (freeze ? "可冻结应用 " : "") + (usage ? "可查看使用时长" : "")
                : "AI权限：未开启";
        return "扩展连接：" + AdbProtocol.hostPort() + "（需安装 dekk ADB 扩展并启动服务）\n" + state;
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + .5f);
    }
}