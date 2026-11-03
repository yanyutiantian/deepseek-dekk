package com.dekk.yanyu;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Outline;
import android.os.Build;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.AccelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 抖音式评论面板：底部弹出、列表上下滑动、输入框固定底部。
 *
 * <ul>
 *   <li>回复：点某条评论的「回复」→ 输入框变成「回复 @某人」，发布时带 parent_id；</li>
 *   <li>回复展示参考抖音：顶层评论下面缩进列出回复（默认只展示 2 条，可「展开 N条回复」），
 *       回复里显示「昵称 ▶ 被回复的人」，作者回复过的评论会打「作者回复过」标签；</li>
 *   <li>置顶：置顶的评论排在最前并带「置顶」标签；</li>
 *   <li>长按评论弹菜单：删除 / 置顶 / 取消置顶 / 回复 —— 有没有权限由服务端返回的
 *       can_delete / can_pin 决定（本人只能删自己的；作者和管理员可删任意；作者和管理员可置顶，
 *       每个内容最多 3 条）。</li>
 * </ul>
 */
final class CommentSheet extends FrameLayout {

    /** 和 CenterPage 一样挂个固定 id，便于返回键找到它、以及判断是否已经打开。 */
    private static final int ID = 0x00DE0004;
    private static final int REPLY_PREVIEW = 2;

    private final Activity activity;
    private final CenterPage page;
    private final String kind;
    private final long targetId;

    private LinearLayout panel;
    /** 首次加载时铺的评论骨架；数据到达后淡出移除。 */
    private View skeletonHolder;
    /** 背后的页面（root 的第一个子 View）：弹窗升起时它"退后一步"。 */
    private View host;
    /** 顶部把手：手指上下拖动面板。 */
    private View dragHandle;
    private float dragStartY;
    private VelocityTracker tracker;
    private boolean closing;
    private int maskMax = 0x99;
    private LinearLayout list;
    private TextView header;
    private EditText input;
    private TextView send;
    private TextView cancelReply;

    private int total;
    private int pinnedCount;
    private int maxPin = 3;
    private boolean canPin;
    private long replyToId;
    private String replyToName = "";
    private boolean loaded;
    private final Set<Long> expanded = new HashSet<Long>();

    private CommentSheet(Activity activity, CenterPage page, String kind, long targetId) {
        super(activity);
        this.activity = activity;
        this.page = page;
        this.kind = kind;
        this.targetId = targetId;
    }

    /** 打开评论面板。 */
    static void open(Activity activity, CenterPage page, String kind, long targetId) {
        if (activity == null || page == null || targetId <= 0) return;
        CommentSheet sheet = new CommentSheet(activity, page, kind, targetId);
        sheet.setId(ID);
        sheet.attach();
    }

    private void attach() {
        // 起手透明：遮罩随弹窗一起渐入（"背后页面被压暗"就是这层）
        setBackgroundColor(0x00000000);
        setClickable(true);
        setFocusableInTouchMode(true);
        setOnClickListener(new OnClickListener() {
            public void onClick(View v) { close(); }
        });

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        panel.setBackground(page.shape(ModuleTheme.surface(page.night), 28));
        int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.84f);
        addView(panel, new FrameLayout.LayoutParams(-1, height, Gravity.BOTTOM));

        LinearLayout handle = new LinearLayout(activity);
        handle.setGravity(Gravity.CENTER);
        View grip = new View(activity);
        grip.setBackground(page.shape(page.night ? 0xFF4A4A4A : 0xFFDDDDDD, 3));
        LinearLayout.LayoutParams gripParams = new LinearLayout.LayoutParams(page.dp(40), page.dp(4));
        gripParams.topMargin = page.dp(10);
        gripParams.bottomMargin = page.dp(6);
        handle.addView(grip, gripParams);
        dragHandle = handle;
        panel.addView(handle, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(page.dp(24), page.dp(22), page.dp(20), page.dp(18));
        header = page.label("评论", 20);
        header.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        head.addView(header, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = page.label("✕", 18);
        close.setTextColor(page.secondary);
        close.setPadding(page.dp(10), page.dp(2), page.dp(4), page.dp(2));
        close.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { close(); }
        });
        head.addView(close);
        panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(activity);
        divider.setBackgroundColor(ModuleTheme.divider(page.night));
        panel.addView(divider, new LinearLayout.LayoutParams(-1, page.dp(1)));

        ScrollView scroll = new ScrollView(activity);scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(page.dp(16), page.dp(8), page.dp(16), page.dp(16));
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 固定底部：我的头像 + 输入框 + 发送（回复某人时前面多一个「回复 @昵称 ✕」）
        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(page.dp(20), page.dp(16), page.dp(20), page.dp(20));
        bottom.setBackgroundColor(page.night ? 0xFF1A1A1A : 0xFFF7F7F7);

        cancelReply = page.label("", 12);
        cancelReply.setTextColor(ModuleTheme.ACCENT);
        cancelReply.setPadding(page.dp(4), 0, 0, page.dp(6));
        cancelReply.setVisibility(View.GONE);
        cancelReply.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { clearReply(); }
        });
        bottom.addView(cancelReply);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView mine = new ImageView(activity);
        mine.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int size = page.dp(34);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(size, size);
        avatarParams.rightMargin = page.dp(10);
        row.addView(mine, avatarParams);
        String myAvatar = "";
        try { myAvatar = UserProfile.avatarUrl(UserProfile.get()); } catch (Throwable ignored) { }
        AvatarUi.show(mine, myAvatar);

        input = new EditText(activity);
        input.setHint("说点什么（最多 " + CommentSection.MAX_CHARS + " 字）");
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(page.text);
        input.setHintTextColor(page.secondary);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(CommentSection.MAX_CHARS)});
        input.setBackground(page.shape(ModuleTheme.field(page.night), 14));
        input.setPadding(page.dp(14), page.dp(10), page.dp(14), page.dp(10));
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));

        send = page.label("发送", 15);
        send.setTextColor(0xFFFFFFFF);
        send.setGravity(Gravity.CENTER);
        send.setPadding(page.dp(16), page.dp(9), page.dp(16), page.dp(9));
        send.setBackground(page.shape(ModuleTheme.ACCENT, 18));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(-2, -2);
        sendParams.leftMargin = page.dp(10);
        row.addView(send, sendParams);
        Motion.pressable(send, 0.94f);
        send.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { post(); }
        });

        bottom.addView(row, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        // 键盘弹出时把面板顶上去
        setOnApplyWindowInsetsListener(new OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                panel.setPadding(0, 0, 0, Math.min(Math.max(0, insets.getSystemWindowInsetBottom()), page.dp(48)));
                return insets;
            }
        });

        addToRoot();
        animateIn();
        attachDrag();
        load();
    }

    /**
     * 入场：弹窗用弹簧从底部升起，同时背后页面"退后一步"——
     * 缩到 0.92、切成圆角、被遮罩压暗。全程约 420ms。
     */
    private void animateIn() {
        android.view.ViewGroup root = (android.view.ViewGroup) activity.findViewById(android.R.id.content);
        if (root != null && root.getChildCount() > 1) {
            // 面板是最后加进去的，所以"背后页面"就是第一个子 View
            host = root.getChildAt(0);
        }
        if (host != null) {
            host.setPivotX(host.getWidth() / 2f);
            host.setPivotY(host.getHeight() * 0.35f);
            setHostRounded(true);
            host.animate().scaleX(0.92f).scaleY(0.92f)
                    .setDuration(420L)
                    .setInterpolator(SpringInterpolator.SOFT)
                    .start();
        }
        int travel = panel.getHeight() > 0 ? panel.getHeight() : page.dp(600);
        panel.setTranslationY(travel);
        panel.animate().translationY(0f)
                .setDuration(420L)
                .setInterpolator(SpringInterpolator.DEFAULT)
                .start();
        fadeMask(0, maskMax, 420L);
    }

    /** 背后页面切成圆角（API 21+ 用 outline 裁剪）。 */
    private void setHostRounded(boolean rounded) {
        if (host == null || Build.VERSION.SDK_INT < 21) return;
        if (rounded) {
            final float r = page.dp(18);
            host.setOutlineProvider(new ViewOutlineProvider() {
                public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            host.setClipToOutline(true);
        } else {
            host.setClipToOutline(false);
            host.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        }
    }

    /** 顶部把手拖动：上拖有阻力，下拖超过 1/3 或快速一甩就关闭，否则弹回原位。 */
    private void attachDrag() {
        if (dragHandle == null) return;
        dragHandle.setOnTouchListener(new OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragStartY = event.getRawY();
                        if (tracker == null) tracker = VelocityTracker.obtain();
                        tracker.clear();
                        tracker.addMovement(event);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        tracker.addMovement(event);
                        float dy = event.getRawY() - dragStartY;
                        // 往上拖有阻力：越拖越费劲
                        if (dy < 0) dy *= 0.35f;
                        panel.setTranslationY(dy);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        tracker.addMovement(event);
                        tracker.computeCurrentVelocity(1000);
                        float velocityY = tracker.getYVelocity();
                        float offset = panel.getTranslationY();
                        float height = Math.max(1, panel.getHeight());
                        if (offset > height / 3f || velocityY > 1200f) {
                            close();
                        } else {
                            springBack();
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    /** 没达到关闭条件：弹簧回到原位。 */
    private void springBack() {
        panel.animate().translationY(0f)
                .setDuration(380L)
                .setInterpolator(SpringInterpolator.BOUNCY)
                .start();
    }

    /** 遮罩透明度动画（0~255）。 */
    private void fadeMask(int fromAlpha, int toAlpha, long duration) {
        ValueAnimator animator = ValueAnimator.ofInt(fromAlpha, toAlpha);
        animator.setDuration(duration);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                int alpha = (Integer) animation.getAnimatedValue();
                setBackgroundColor(alpha << 24);
            }
        });
        animator.start();
    }

    /** 盖到当前 Activity 的最上层（半透明背景 + 底部面板）。 */
    private void addToRoot() {
        android.view.ViewGroup root = (android.view.ViewGroup) activity.findViewById(android.R.id.content);
        if (root == null) return;
        root.addView(this, new android.view.ViewGroup.LayoutParams(-1, -1));
        requestFocus();
        requestApplyInsets();
    }

    /** 返回键：面板开着就先关面板（供宿主的 onBackPressed 钩子调用）。 */
    public static boolean handleBack(Activity activity) {
        View view = activity.findViewById(ID);
        if (view instanceof CommentSheet) {
            ((CommentSheet) view).close();
            return true;
        }
        return false;
    }

    /**
     * 关闭：面板落下，背后页面同步恢复（缩放回 1、去掉圆角、遮罩淡出）。
     * 动画结束后才真正把自己从父容器摘掉。
     */
    private void close() {
        if (closing) return;
        closing = true;
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
        // 背后页面恢复
        if (host != null) {
            setHostRounded(false);
            host.animate().scaleX(1f).scaleY(1f)
                    .setDuration(420L)
                    .setInterpolator(SpringInterpolator.SOFT)
                    .start();
        }
        fadeMask(maskMax, 0, 300L);
        int travel = panel.getHeight() > 0 ? panel.getHeight() : page.dp(600);
        panel.animate().translationY(travel)
                .setDuration(360L)
                .setInterpolator(new AccelerateInterpolator(1.3f))
                .withEndAction(new Runnable() {
                    public void run() {
                        android.view.ViewGroup parent = (android.view.ViewGroup) getParent();
                        if (parent != null) parent.removeView(CommentSheet.this);
                    }
                }).start();
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- 数据 ----

    private void load() {
        // 首次加载：先铺几条与真实评论同形的骨架，数据到了再"长"在骨架的位置上
        if (skeletonHolder == null && list.getChildCount() == 0) {
            skeletonHolder = FeedUi.commentSkeleton(activity, page, 3);
            list.addView(skeletonHolder);
        }
        PersonaApi.request(activity, "comment_list&kind=" + kind + "&id=" + targetId, null,
                new PersonaApi.Callback() {
                    public void done(JSONObject result, String error) {
                        if (!isAttached()) return;
                        // 清掉上一轮的内容，但保留骨架（稍后淡出，避免闪白）
                        for (int i = list.getChildCount() - 1; i >= 0; i--) {
                            if (list.getChildAt(i) != skeletonHolder) list.removeViewAt(i);
                        }
                        if (error != null) {
                            FeedUi.fadeOutAndRemove(skeletonHolder);
                            skeletonHolder = null;
                            list.addView(empty(error));
                            return;
                        }
                        total = result == null ? 0 : result.optInt("count", 0);
                        pinnedCount = result == null ? 0 : result.optInt("pinned_count", 0);
                        maxPin = result == null ? 3 : result.optInt("max_pin", 3);
                        canPin = result != null && result.optBoolean("can_pin", false);
                        loaded = true;
                        header.setText("评论 " + total);
                        JSONArray items = result == null ? null : result.optJSONArray("items");
                        if (items == null || items.length() == 0) {
                            FeedUi.fadeOutAndRemove(skeletonHolder);
                            skeletonHolder = null;
                            list.addView(empty("还没有评论，来做第一个吧"));
                            return;
                        }
                        // 真评论插在骨架的位置上，再让骨架淡出 —— 位置不跳
                        final int insertAt = skeletonHolder != null ? list.indexOfChild(skeletonHolder) : -1;
                        FeedUi.fadeOutAndRemove(skeletonHolder);
                        skeletonHolder = null;
                        int shown = 0;
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.optJSONObject(i);
                            if (item == null) continue;
                            View view = commentView(item, false);
                            if (insertAt >= 0) list.addView(view, insertAt + shown);
                            else list.addView(view);
                            FeedUi.enterCard(view, shown, true);
                            shown++;
                        }
                    }
                });
    }

    private boolean isAttached() {
        return getParent() != null;
    }

    private TextView empty(String text) {
        TextView view = page.label(text, 13);
        view.setTextColor(page.secondary);
        view.setPadding(0, page.dp(16), 0, page.dp(16));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    /** 一条评论（顶层或回复）。 */
    private View commentView(final JSONObject item, final boolean reply) {
        final long id = item.optLong("id");
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, page.dp(reply ? 6 : 12), 0, page.dp(reply ? 6 : 12));

        // 点头像 / 昵称：自己→个人中心，别人→TA 的主页
        final long authorId = item.optLong("user_id");
        final String authorName = item.optString("nickname", "用户");
        View.OnClickListener visit = authorId > 0 ? new OnClickListener() {
            public void onClick(View v) { openUser(authorId, authorName); }
        } : null;

        ImageView avatar = new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setContentDescription(authorName);
        int size = page.dp(reply ? 26 : 38);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(size, size);
        avatarParams.rightMargin = page.dp(10);
        row.addView(avatar, avatarParams);
        AvatarUi.show(avatar, item.optString("avatar_url", ""));
        if (visit != null) avatar.setOnClickListener(visit);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String nickname = item.optString("nickname", "用户");
        String to = item.optString("reply_to", "");
        // 昵称单独一个 TextView：赞助用户只让「名字」变灿金，▶ 回复对象保持灰色
        TextView name = page.label(nickname, reply ? 13 : 14);
        name.setTextColor(ModuleTheme.secondary(page.night));
        SponsorName.apply(name, SponsorName.flag(item, "sponsor"), ModuleTheme.secondary(page.night));
        head.addView(name);
        if (visit != null) name.setOnClickListener(visit);
        if (to.length() > 0) {
            TextView target = page.label(" ▶ " + to, reply ? 13 : 14);
            target.setTextColor(ModuleTheme.secondary(page.night));
            head.addView(target);
        }

        String title = item.optString("title", "用户");
        TextView badge = page.label(title, 11);
        badge.setTextColor("管理员".equals(title) ? 0xFFD32F2F : ("创作者".equals(title) ? 0xFFE53935 : 0xFF9E9E9E));
        badge.setPadding(page.dp(8), 0, 0, 0);
        head.addView(badge);

        if (item.optBoolean("pinned", false)) {
            TextView pin = page.label("置顶", 11);
            pin.setTextColor(ModuleTheme.ACCENT);
            pin.setPadding(page.dp(8), 0, 0, 0);
            head.addView(pin);
        }
        column.addView(head);

        TextView text = page.label(item.optString("text", ""), reply ? 14 : 15);
        text.setLineSpacing(0, 1.15f);
        text.setPadding(0, page.dp(4), 0, 0);
        column.addView(text);

        LinearLayout foot = new LinearLayout(activity);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, page.dp(6), 0, 0);
        TextView time = page.label(ItemDetailPage.shortTime(item.optString("created_at", "")), 11);
        time.setTextColor(page.secondary);
        foot.addView(time);
        if (item.optBoolean("creator_replied", false) && !reply) {
            TextView tag = page.label("作者回复过", 11);
            tag.setTextColor(page.secondary);
            tag.setPadding(page.dp(10), 0, 0, 0);
            foot.addView(tag);
        }
        TextView act = page.label("回复", 12);
        act.setTextColor(ModuleTheme.ACCENT);
        act.setPadding(page.dp(12), 0, 0, 0);
        act.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                replyToId = id;
                replyToName = item.optString("nickname", "用户");
                cancelReply.setText("回复 @" + replyToName + "（点击取消）");
                cancelReply.setVisibility(View.VISIBLE);
                input.setHint("回复 " + replyToName + "（最多 " + CommentSection.MAX_CHARS + " 字）");
                input.requestFocus();
            }
        });
        foot.addView(act);
        column.addView(foot);

        // 回复列表（抖音样式：默认两条 + 展开 N条回复）
        if (!reply) {
            JSONArray replies = item.optJSONArray("replies");
            int count = replies == null ? 0 : replies.length();
            if (count > 0) {
                boolean open = expanded.contains(id);
                int show = open ? count : Math.min(REPLY_PREVIEW, count);
                LinearLayout box = new LinearLayout(activity);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(page.dp(12), 0, 0, 0);
                for (int i = 0; i < show; i++) {
                    JSONObject child = replies.optJSONObject(i);
                    if (child != null) box.addView(commentView(child, true));
                }
                if (count > REPLY_PREVIEW) {
                    final long parentId = id;
                    TextView more = page.label(open ? "收起回复" : ("展开 " + count + " 条回复 ▾"), 12);
                    more.setTextColor(ModuleTheme.ACCENT);
                    more.setPadding(page.dp(36), page.dp(4), 0, page.dp(6));
                    more.setOnClickListener(new OnClickListener() {
                        public void onClick(View v) {
                            if (expanded.contains(parentId)) expanded.remove(parentId);
                            else expanded.add(parentId);
                            reload();
                        }
                    });
                    box.addView(more);
                }
                column.addView(box);
            }
        }

        // 长按：删除 / 置顶 / 取消置顶 / 回复
        if (item.optBoolean("can_delete", false) || item.optBoolean("can_pin", false)) {
            row.setOnLongClickListener(new OnLongClickListener() {
                public boolean onLongClick(View v) {
                    menu(item);
                    return true;
                }
            });
        }
        return row;
    }

    /** 点头像/昵称：自己跳个人中心，别人跳 TA 的创作者主页。 */
    private void openUser(long userId, String nickname) {
        if (userId <= 0) return;
        close();
        if (userId == (long) UserProfile.value(UserProfile.get(), "id")) {
            UserCenterPage.show(activity);
        } else {
            CreatorPage.show(activity, (int) userId, nickname);
        }
    }

    /** 长按菜单：只显示有权限的项。 */
    private void menu(final JSONObject item) {
        final List<String> labels = new ArrayList<String>();
        final List<Integer> actions = new ArrayList<Integer>();
        labels.add("回复");
        actions.add(0);
        final boolean pinned = item.optBoolean("pinned", false);
        if (item.optBoolean("can_pin", false)) {
            labels.add(pinned ? "取消置顶" : "置顶");
            actions.add(1);
        }
        if (item.optBoolean("can_delete", false)) {
            labels.add("删除");
            actions.add(2);
        }
        new ModuleDialog(activity)
                .setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        int action = actions.get(which);
                        if (action == 0) {
                            replyToId = item.optLong("id");
                            replyToName = item.optString("nickname", "用户");
                            cancelReply.setText("回复 @" + replyToName + "（点击取消）");
                            cancelReply.setVisibility(View.VISIBLE);
                            input.requestFocus();
                        } else if (action == 1) {
                            pin(item.optLong("id"), !pinned);
                        } else {
                            confirmDelete(item.optLong("id"));
                        }
                    }
                })
                .show();
    }

    private void confirmDelete(final long id) {
        new ModuleDialog(activity)
                .setTitle("删除评论")
                .setMessage("删除后不可恢复，确定删除这条评论吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        JSONObject body = new JSONObject();
                        try { body.put("id", id); } catch (Throwable ignored) { }
                        PersonaApi.request(activity, "comment_delete", body, new PersonaApi.Callback() {
                            public void done(JSONObject result, String error) {
                                if (!isAttached()) return;
                                page.message(error != null ? error : "已删除");
                                if (error == null) reload();
                            }
                        });
                    }
                })
                .show();
    }

    private void pin(final long id, final boolean on) {
        JSONObject body = new JSONObject();
        try {
            body.put("id", id);
            body.put("on", on ? 1 : 0);
        } catch (Throwable ignored) { }
        PersonaApi.request(activity, "comment_pin", body, new PersonaApi.Callback() {
            public void done(JSONObject result, String error) {
                if (!isAttached()) return;
                page.message(error != null ? error : (on ? "已置顶" : "已取消置顶"));
                if (error == null) reload();
            }
        });
    }

    private void post() {
        if (AccountStore.token(activity).length() == 0) {
            page.message("登录后才能评论");
            close();
            AccountPages.open(activity);
            return;
        }
        String text = input.getText().toString().trim();
        if (text.length() == 0) {
            page.message("评论不能为空");
            return;
        }
        if (text.length() > CommentSection.MAX_CHARS) {
            page.message("评论最多 " + CommentSection.MAX_CHARS + " 字");
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("kind", kind);
            body.put("id", targetId);
            body.put("text", text);
            if (replyToId > 0) body.put("parent_id", replyToId);
        } catch (Throwable ignored) { }
        send.setEnabled(false);
        PersonaApi.request(activity, "comment_add", body, new PersonaApi.Callback() {
            public void done(JSONObject result, String error) {
                if (!isAttached()) return;
                send.setEnabled(true);
                if (error != null) {
                    page.message(error);
                    return;
                }
                // 特效口令（比如发 mj）：服务端下发特效地址，不存评论，直接在线播放
                JSONObject effect = result == null ? null : result.optJSONObject("effect");
                if (effect != null) {
                    input.setText("");
                    clearReply();
                    EffectPlayer.logEffect(effect);
                    EffectPlayer.play(activity, effect);
                    return;
                }
                input.setText("");
                clearReply();
                page.message("评论已发布");
                reload();
            }
        });
    }

    /** 发完/删完只刷新列表，不动输入框状态（回复中的状态已由 clearReply 清掉）。 */
    private void reload() {
        if (!isAttached()) return;
        load();
    }

    private void clearReply() {
        replyToId = 0;
        replyToName = "";
        cancelReply.setVisibility(View.GONE);
        input.setHint("说点什么（最多 " + CommentSection.MAX_CHARS + " 字）");
    }
}