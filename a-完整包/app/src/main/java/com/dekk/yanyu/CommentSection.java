package com.dekk.yanyu;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 详情页里的「评论区」入口：**可折叠**（默认收起，点标题展开）。
 *
 * <p>展开后显示：条数 + 置顶的评论预览（最多 2 条）+「查看全部 N 条评论 ›」+「写一条评论…」，
 * 点任意一处都会打开抖音式评论面板 {@link CommentSheet}（列表上下滑动、输入框固定在底部、
 * 支持回复 / 长按置顶删除）。
 */
final class CommentSection {

    /** 和服务端 DEKK_COMMENT_MAX_CHARS 保持一致。 */
    static final int MAX_CHARS = 30;

    private CommentSection() { }

    /** 在详情页里挂一个可折叠的评论区入口。 */
    static void attach(final Activity activity, final CenterPage page, final int itemKind, final JSONObject row) {
        attach(activity, page, page.body, itemKind, row);
    }

    /**
     * 同上，但把卡片挂到指定父容器（详情页需要先把内容放进临时容器、再整体浮现）。
     *
     * <p>加载过程中显示与真实评论同形的骨架行；数据到达后骨架淡出、置顶预览逐条浮现。
     */
    static void attach(final Activity activity, final CenterPage page, final LinearLayout parent,
                       final int itemKind, final JSONObject row) {
        if (row == null) return;
        final long id = row.optLong("id");
        if (id <= 0) return;
        final String kind = itemKind == ItemDetailPage.PERSONA ? "persona" : "plugin";

        LinearLayout card = MiuixUi.card(activity, page.night);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = page.dp(16);
        parent.addView(card, cardParams);
        CollapsibleSection.Result block = CollapsibleSection.add(activity, page, card, "评论区", false, MiuixIcon.CHAT);
        final LinearLayout content = block.content;

        // 骨架：两条评论行的形状（圆头像 + 昵称条 + 内容条）
        final View skeleton = FeedUi.commentSkeleton(activity, page, 2);
        content.addView(skeleton);

        final LinearLayout pinBox = new LinearLayout(activity);
        pinBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(pinBox);

        final TextView openAll = page.label("查看全部评论 ›", 14);
        openAll.setTextColor(ModuleTheme.ACCENT);
        openAll.setPadding(0, page.dp(10), 0, page.dp(10));
        openAll.setVisibility(View.GONE);
        openAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { CommentSheet.open(activity, page, kind, id); }
        });
        content.addView(openAll);

        TextView write = page.label("写一条评论…", 14);
        write.setTextColor(page.secondary);
        write.setBackground(MiuixUi.shape(activity,ModuleTheme.field(page.night),14));
        write.setPadding(page.dp(14), page.dp(12), page.dp(14), page.dp(12));
        write.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (AccountStore.token(activity).length() == 0) {
                    page.message("登录后才能评论");
                    AccountPages.open(activity);
                    return;
                }
                CommentSheet.open(activity, page, kind, id);
            }
        });
        content.addView(write, new LinearLayout.LayoutParams(-1, -2));

        PersonaApi.request(activity, "comment_list&kind=" + kind + "&id=" + id, null,
                new PersonaApi.Callback() {
                    public void done(JSONObject result, String error) {
                        FeedUi.fadeOutAndRemove(skeleton);
                        if (error != null) {
                            TextView err = page.label(error, 13);
                            err.setTextColor(page.secondary);
                            err.setPadding(0, page.dp(6), 0, page.dp(10));
                            pinBox.addView(err);
                            return;
                        }
                        int count = result == null ? 0 : result.optInt("count", 0);
                        openAll.setText("查看全部 " + count + " 条评论 ›");
                        openAll.setVisibility(View.VISIBLE);
                        pinBox.removeAllViews();
                        if (count == 0) {
                            TextView none = page.label("还没有评论，来做第一个吧", 13);
                            none.setTextColor(page.secondary);
                            none.setPadding(0, page.dp(4), 0, page.dp(8));
                            pinBox.addView(none);
                            return;
                        }
                        // 置顶预览（最多 2 条）
                        JSONArray items = result.optJSONArray("items");
                        int shown = 0;
                        if (items != null) {
                            for (int i = 0; i < items.length() && shown < 2; i++) {
                                JSONObject item = items.optJSONObject(i);
                                if (item == null || !item.optBoolean("pinned", false)) continue;
                                LinearLayout line = new LinearLayout(activity);
                                line.setOrientation(LinearLayout.VERTICAL);
                                line.setPadding(0, page.dp(4), 0, page.dp(6));
                                LinearLayout head = new LinearLayout(activity);
                                head.setOrientation(LinearLayout.HORIZONTAL);
                                TextView name = page.label(item.optString("nickname", "用户"), 13);
                                name.setTextColor(page.secondary);
                                SponsorName.apply(name, SponsorName.flag(item, "sponsor"), page.secondary);
                                head.addView(name);
                                TextView pin = page.label("置顶", 11);
                                pin.setTextColor(ModuleTheme.ACCENT);
                                pin.setPadding(page.dp(8), 0, 0, 0);
                                head.addView(pin);
                                line.addView(head);
                                TextView text = page.label(item.optString("text", ""), 14);
                                text.setPadding(0, page.dp(3), 0, 0);
                                line.addView(text);
                                pinBox.addView(line);
                                // 每条错开浮现，和列表里的节奏一致
                                FeedUi.enterCard(line, shown, true);
                                shown++;
                            }
                        }
                        if (shown == 0) {
                            TextView hint = page.label("点「查看全部」看全部评论", 13);
                            hint.setTextColor(page.secondary);
                            hint.setPadding(0, page.dp(4), 0, page.dp(6));
                            pinBox.addView(hint);
                        }
                    }
                });
    }
}