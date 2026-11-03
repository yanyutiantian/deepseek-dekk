package com.dekk.yanyu;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

/** 账号与人设：Miuix 卡片 + 带图标列表行。 */
public final class AccountCenterCard {
    private AccountCenterCard() { }

    public static View create(final Activity a, boolean dark, int color) {
        LinearLayout card = MiuixUi.card(a, dark);
        NoticePage.attachEntry(a, card, ModuleTheme.text(dark));
        MiuixUi.row(a, card, dark, MiuixIcon.STAR, "人设市场", "浏览、下载与上传人设", true, new View.OnClickListener() {
            public void onClick(View v) { PersonaPages.show(a, "market"); }
        });
        return card;
    }
}