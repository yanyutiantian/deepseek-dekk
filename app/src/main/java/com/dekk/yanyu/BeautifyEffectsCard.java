package com.dekk.yanyu;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Uses the same switch control as the module settings page. */
final class BeautifyEffectsCard {
    private BeautifyEffectsCard() {}

    static void add(CenterPage page) {
        page.section("消息动画");
        pendingRow(page, page.card(), "发送与接收动画", "尚未接入消息气泡，暂不可用");
        page.section("输入框样式");
        LinearLayout card = page.card();
        pendingRow(page, card, "毛玻璃", "尚未接入输入框背景，暂不可用");
        pendingRow(page, card, "液态玻璃", "尚未接入输入框背景，暂不可用");
    }

    private static void pendingRow(CenterPage page, LinearLayout card, String title, String description) {
        LinearLayout row = new LinearLayout(page.activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, page.dp(16), 0, page.dp(16));
        LinearLayout labels = new LinearLayout(page.activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(0, 0, page.dp(16), 0);
        labels.addView(page.label(title, 16));
        TextView detail = page.label(description, 12);
        detail.setTextColor(page.secondary);
        detail.setPadding(0, page.dp(6), 0, 0);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        M3SwitchView toggle = new M3SwitchView(page.activity);
        toggle.setContentDescription(title + "，暂不可用");
        toggle.setTrackColors(0xFF3482FF, page.night ? 0x33FFFFFF : 0x1F000000);
        final int kind=title.equals("发送与接收动画")?0:title.equals("毛玻璃")?1:2;
        final android.content.Context context=page.activity;
        toggle.setChecked(kind==0?BeautifyStore.animations(context):BeautifyStore.composerStyle(context)==kind);
        toggle.setOnCheckedChangeListener(new M3SwitchView.OnCheckedChangeListener(){
            public void onCheckedChanged(M3SwitchView view,boolean checked){
                if(kind==0)BeautifyStore.setAnimations(context,checked);
                else BeautifyStore.setComposerStyle(context,checked?kind:0);
                BeautifyPage.show((android.app.Activity)context);
            }
        });
        row.addView(toggle, new LinearLayout.LayoutParams(page.dp(52), page.dp(32)));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
}