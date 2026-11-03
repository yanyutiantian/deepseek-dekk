package com.dekk.yanyu;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 消息中心（照抖音的逻辑做）：别人评论了你的作品、或回复了你的评论，都会在这里出现一条。
 *
 * <ul>
 *   <li>入口：「消息中心」按钮右上角一个红色角标，显示未读数，超过 99 显示 <b>99+</b>；</li>
 *   <li>列表：头像 +「某某回复了你的人设《x》」+ 评论内容 + 时间；</li>
 *   <li>点进去直接打开那条人设/插件的详情页（顺带能看到评论），内容已被删/不可见时提示；</li>
 *   <li>打开列表即标记全部已读（红点消失），和服务端的 notice_list 对应。</li>
 * </ul>
 */
final class NoticePage {

    private NoticePage() { }

    /** 打不开就先去登录。 */
    static void open(Activity activity) {
        if (AccountStore.token(activity).length() == 0) {
            AccountPages.open(activity);
            return;
        }
        show(activity);
    }

    static void show(final Activity activity) {
        final CenterPage page = new CenterPage(activity, "消息中心", null);
        final LinearLayout card = page.card();

        final TextView status = page.label("正在加载…", 13);
        status.setTextColor(page.secondary);
        status.setPadding(0, page.dp(6), 0, page.dp(10));
        card.addView(status);
        // 加载中：提示文字柔和呼吸，明确告诉大家"还在跑"
        Motion.breath(status);
        PersonaApi.request(activity, "notice_list", null, new PersonaApi.Callback() {
            public void done(JSONObject result, String error) {
                if (!page.active()) return;
                Motion.stopBreath(status);
                if (error != null) {
                    status.setText(error);
                    return;
                }
                card.removeAllViews();
                int unread = result == null ? 0 : result.optInt("unread", 0);
                if (unread > 0) {
                    TextView head = page.label("有 " + unread + " 条新消息", 13);
                    head.setTextColor(0xFFE53935);
                    head.setPadding(0, page.dp(6), 0, page.dp(8));
                    card.addView(head);
                }
                JSONArray items = result == null ? null : result.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    TextView none = page.label("还没有新消息", 13);
                    none.setTextColor(page.secondary);
                    none.setPadding(0, page.dp(6), 0, page.dp(10));
                    card.addView(none);
                    return;
                }
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) card.addView(row(activity, page, item));
                }
            }
        });
        page.show();
    }

    /** 一条消息：头像 + 谁 + 干了什么 + 内容 + 时间。 */
    private static View row(final Activity activity, final CenterPage page, final JSONObject item) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, page.dp(10), 0, page.dp(10));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x184D6BFE), null, page.shape(-1, 12)));

        ImageView avatar = new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int size = page.dp(38);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(size, size);
        avatarParams.rightMargin = page.dp(10);
        row.addView(avatar, avatarParams);
        AvatarUi.show(avatar, item.optString("avatar_url", ""));

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1f));

        String who = item.optString("nickname", "有人");
        String what = "comment".equals(item.optString("kind")) ? "评论了你的" : "回复了你的评论 ·";
        String kind = "plugin".equals(item.optString("target_kind")) ? "插件" : "人设";
        // 名字单独一个 TextView：赞助用户只让名字变灿金，后面那句保持原色
        LinearLayout titleLine = new LinearLayout(activity);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = page.label(who, 15);
        name.setTypeface(MiuixUi.medium());
        SponsorName.apply(name, SponsorName.flag(item, "sponsor"), page.text);
        titleLine.addView(name);
        TextView title = page.label(" " + what + kind + "《" + item.optString("target_name", "") + "》", 15);
        title.setTypeface(MiuixUi.medium());
        titleLine.addView(title);
        column.addView(titleLine);

        TextView body = page.label(item.optString("content", ""), 14);
        body.setTextColor(page.secondary);
        body.setPadding(0, page.dp(4), 0, 0);
        column.addView(body);

        TextView time = page.label(ItemDetailPage.shortTime(item.optString("created_at", "")), 12);
        time.setTextColor(page.secondary);
        time.setPadding(0, page.dp(4), 0, 0);
        column.addView(time);

        // 点进那条内容（人设/插件详情页），里面就能看到评论
        final String targetKind = "plugin".equals(item.optString("target_kind")) ? "plugin" : "persona";
        final long targetId = item.optLong("target_id");
        final int itemKind = "plugin".equals(targetKind) ? ItemDetailPage.PLUGIN : ItemDetailPage.PERSONA;
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openTarget(activity, page, targetKind, targetId, itemKind); }
        });
        return row;
    }

    /** 打开消息对应的内容（内容不可见时退回到评论面板，至少能看到评论）。 */
    private static void openTarget(final Activity activity, final CenterPage page,
                                   final String kind, final long id, final int itemKind) {
        if (id <= 0) return;
        PersonaApi.request(activity, "item_get&kind=" + kind + "&id=" + id, null, new PersonaApi.Callback() {
            public void done(JSONObject result, String error) {
                JSONObject item = result == null ? null : result.optJSONObject("item");
                if (error != null || item == null) {
                    page.message("内容已不可见，直接看评论吧");
                    CommentSheet.open(activity, page, kind, id);
                    return;
                }
                ItemDetailPage.show(activity, itemKind, item);
            }
        });
    }

    /**
     * 「消息中心」入口（带红色未读角标），个人中心和模块设置首页共用。
     *
     * @param textColor 标题颜色（跟随调用方的主题）
     */
    static void attachEntry(final Activity activity, final LinearLayout card, final int textColor) {
        boolean dark = ModuleTheme.dark(activity);
        MiuixUi.Row row = MiuixUi.row(activity, card, dark, MiuixIcon.BELL, "消息中心", "评论、回复与作品通知", true,
                new View.OnClickListener() {
            public void onClick(View v) { open(activity); }
        });
        final TextView badge = new TextView(activity);
        badge.setTextSize(11);
        badge.setTextColor(0xFFFFFFFF);
        badge.setIncludeFontPadding(false);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(activity, 7), dp(activity, 2), dp(activity, 7), dp(activity, 2));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFE53935);
        bg.setCornerRadius(dp(activity, 14));
        badge.setBackground(bg);
        badge.setVisibility(View.GONE);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
        badgeParams.rightMargin = dp(activity, 8);
        LinearLayout line = (LinearLayout) row.container;
        int arrowIndex = line.getChildCount() - 1;
        line.addView(badge, arrowIndex < 0 ? line.getChildCount() : arrowIndex, badgeParams);
        refreshBadge(activity, badge);
    }

    /** 只查未读数（很轻），用来画红点。 */
    static void refreshBadge(final Activity activity, final TextView badge) {
        if (AccountStore.token(activity).length() == 0) {
            badge.setVisibility(View.GONE);
            return;
        }
        PersonaApi.request(activity, "notice_unread", null, new PersonaApi.Callback() {
            public void done(JSONObject result, String error) {
                if (error != null || result == null) return;
                int unread = result.optInt("unread", 0);
                if (unread <= 0) {
                    badge.setText("");
                    badge.setVisibility(View.GONE);
                    return;
                }
                badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
                badge.setVisibility(View.VISIBLE);
            }
        });
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}