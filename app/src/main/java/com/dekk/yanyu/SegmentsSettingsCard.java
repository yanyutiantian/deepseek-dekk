package com.dekk.yanyu;

import android.widget.LinearLayout;

/** 美化页独立分段开关。分段必须依赖「微信对话气泡」，所以开启分段时会自动一起打开气泡。 */
final class SegmentsSettingsCard {
    static void add(final CenterPage page) {
        page.section("回复分段");
        LinearLayout card = page.card();
        MiuixUi.switchRow(page.activity, card, page.night, MiuixIcon.CHAT,
                "分段气泡", ConversationSegments.enabled(page.activity),
                new M3SwitchView.OnCheckedChangeListener() {
                    public void onCheckedChanged(M3SwitchView view, boolean checked) {
                        ConversationSegments.setEnabled(page.activity, checked);
                        if (checked && !ChatBubbleHook.enabled(page.activity)) {
                            // 分段渲染挂在气泡节点上，不开气泡就不会生效，这里强制一起打开
                            ChatBubbleHook.setEnabled(page.activity, true);
                            page.message("已开启分段，并自动开启「微信对话气泡」（分段依赖它生效）");
                        } else {
                            page.message("已保存，重新进入聊天会话生效");
                        }
                    }
                });
        page.caption(card, "将 AI 回复显示为多段气泡，并隐藏分段标记。"
                + "注意：分段依赖「微信对话气泡」，开启分段时会自动一起开启气泡，否则不会生效。"
                + "代码块和行内代码中的标记不参与分段。");
    }
    private SegmentsSettingsCard() { }
}