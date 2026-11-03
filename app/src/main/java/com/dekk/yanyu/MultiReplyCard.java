package com.dekk.yanyu;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

import android.content.DialogInterface;
import android.widget.NumberPicker;
import android.widget.TextView;

/** AI 一问多答：Miuix 开关行 + 次数行。 */
public final class MultiReplyCard {
    private MultiReplyCard() { }

    public static View create(final Activity activity, boolean night, int textColor, int subColor) {
        LinearLayout card = MiuixUi.card(activity, night);
        MiuixUi.switchRow(activity, card, night, MiuixIcon.CHAT, "AI 一问多答", MultiReplySettings.enabled(activity),
                new M3SwitchView.OnCheckedChangeListener() {
            public void onCheckedChanged(M3SwitchView view, boolean checked) {
                MultiReplySettings.setEnabled(activity, checked);
            }
        });
        final MiuixUi.Row count = MiuixUi.row(activity, card, night, countLabel(activity), "首次回答后自动续答", true, null);
        count.container.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                final NumberPicker picker = new NumberPicker(activity);
                picker.setMinValue(MultiReplySettings.MIN_COUNT);
                picker.setMaxValue(MultiReplySettings.MAX_COUNT);
                picker.setWrapSelectorWheel(false);
                picker.setValue(MultiReplySettings.count(activity));
                picker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
                new ModuleDialog(activity).setTitle("总回答次数")
                        .setView(picker).setNegativeButton("取消", null)
                        .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                MultiReplySettings.setCount(activity, picker.getValue());
                                count.title.setText(countLabel(activity));
                            }
                        }).show();
            }
        });
        MiuixUi.caption(activity, card, night,
                "默认关闭。会增加服务端用量；自动消息仍会发送并可能保存到云端，其他客户端或导出记录可能显示。");
        return card;
    }

    private static String countLabel(Activity activity) {
        return "总回答次数：" + MultiReplySettings.count(activity) + " 次";
    }
}
