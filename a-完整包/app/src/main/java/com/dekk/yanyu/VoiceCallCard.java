package com.dekk.yanyu;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页里的「语音通话」分组卡片。 */
final class VoiceCallCard {

    private VoiceCallCard() { }

    static View create(final Activity activity, boolean dark, int textColor, int subColor) {
        LinearLayout card = MiuixUi.card(activity, dark);

        TextView caption = MiuixUi.caption(activity, card, dark,
                "点一下麦克风开始说话，再点一下结束并发送；对方回复生成完整后自动朗读。");
        caption.setTextSize(12);

        MiuixUi.switchRow(activity, card, dark, MiuixIcon.PHONE, "语音通话", VoiceCallStore.enabled(activity),
                new M3SwitchView.OnCheckedChangeListener() {
                    public void onCheckedChanged(M3SwitchView view, boolean checked) {
                        VoiceCallStore.setEnabled(activity, checked);
                    }
                });

        MiuixUi.switchRow(activity, card, dark, "回复自动朗读", VoiceCallStore.autoSpeak(activity),
                new M3SwitchView.OnCheckedChangeListener() {
                    public void onCheckedChanged(M3SwitchView view, boolean checked) {
                        VoiceCallStore.setAutoSpeak(activity, checked);
                    }
                });

        MiuixUi.row(activity, card, dark, "语音键位置",
                VoiceCallStore.hasKeyPoint(activity)
                        ? "已标定 " + (int) VoiceCallStore.keyX(activity) + "," + (int) VoiceCallStore.keyY(activity)
                        : "未标定：用一次按住说话会自动记录",
                false, null);

        MiuixUi.row(activity, card, dark, "打一通测试电话", "在当前会话发起语音通话", true,
                new View.OnClickListener() {
                    public void onClick(View v) {
                        Activity current = DekkSettingsPage.currentActivity();
                        if (current != null) VoiceCallManager.requestFromUi(current);
                    }
                });

        MiuixUi.row(activity, card, dark, "通话提示词", "已自动携带，不用手动加进人设", false, null);

        MiuixUi.row(activity, card, dark, "清除语音键标定", "位置不准时先清掉再按一次语音键", false,
                new View.OnClickListener() {
                    public void onClick(View v) {
                        VoiceCallStore.clearKeyPoint(activity);
                        Toast.makeText(activity, "已清除，下次按住说话会重新记录", Toast.LENGTH_SHORT).show();
                    }
                });
        return card;
    }

    private static void copy(Activity activity) {
        String prompt = "你可以主动给用户打语音电话：想说的时候，在回复最后写一块 "
                + VoiceCallProtocol.START + "开场白" + VoiceCallProtocol.END
                + "，客户端会弹通话邀请，这段标记不会显示在聊天里。"
                + "只有在真的有事要说或者想聊天时才用，不要每次都写。";
        try {
            ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            manager.setPrimaryClip(ClipData.newPlainText("语音通话提示词", prompt));
            Toast.makeText(activity, "提示词已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(activity, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }
}
