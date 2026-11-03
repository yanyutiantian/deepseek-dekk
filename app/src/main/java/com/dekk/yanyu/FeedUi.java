package com.dekk.yanyu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * 信息流加载体验组件集合。
 *
 * <p>包含四件事，全部与具体业务无关，人设市场 / 插件中心 / 以后的信息流都能直接用：
 * <ol>
 *   <li>{@link #skeletonCard} 骨架卡：外形与真实卡片一致（圆头像 + 标题条 + 两行简介 + 作者条 + 按钮条）；
 *       所有骨架共用同一道斜向光带，每 1.5 秒从左往右扫一次（相位全局同步）；</li>
 *   <li>{@link #revealBlocks} 数据到达后，按「头像 → 标题 → 简介 → 作者」从上到下每块错开 60ms
 *       淡入 + 上移 8px，同时骨架整卡淡出；</li>
 *   <li>{@link #enterCard} 分页追加的新卡片，每张错开 50ms，从下方 24px 滑上来并淡入（0.45s）；</li>
 *   <li>{@link #attachPreload} 滚到离底部约一屏时提前触发下一页；{@link #dots} 底部跳动小圆点。</li>
 * </ol>
 */
final class FeedUi {
    private FeedUi() { }

    /** 骨架块 / 文字条的颜色（浅灰，夜间模式略亮）。 */
    static int blockColor(boolean night) {
        return night ? 0x1FFFFFFF : 0x11000000;
    }

    /** 头像直径、标题条高度等骨架尺寸，全部按真实卡片的度量来，保证占位高度一致。 */
    static final int AVATAR_DP = 44;

    /**
     * 列表项的作者头像 URL。
     *
     * <p>列表接口已经返回 {@code user_id} / {@code author_avatar}，头像走公开端点
     * （不需要加密信封）：{@code ?action=avatar&id=<user_id>}；服务端在没设头像时
     * 会下发内置默认头像，所以只要 user_id 有效就一定能取到图。
     *
     * @return 头像地址；user_id 无效时返回 null（调用方退回本地默认头像）
     */
    static String authorAvatarUrl(org.json.JSONObject row) {
        if (row == null) return null;
        long userId = row.optLong("user_id", 0L);
        if (userId <= 0) {
            // 有的接口把作者 id 放在其它字段名下
            userId = row.optLong("author_id", row.optLong("uid", 0L));
        }
        if (userId <= 0) return null;
        return "https://dekk.example.com/API/?action=avatar&id=" + userId;
    }

    // ───────────────────────── ① 骨架卡 ─────────────────────────

    /**
     * 一张骨架卡：结构与真实卡片一致（左侧圆头像 + 右侧标题条/两行简介条，下方作者条 + 底部按钮条）。
     *
     * @param p 页面（取 dp / 夜间 / 卡片样式）
     */
    static ShimmerView skeletonCard(android.app.Activity a, CenterPage p) {
        ShimmerView wrap = new ShimmerView(a);
        wrap.setClipToPadding(false);

        LinearLayout card = MiuixUi.card(a, p.night);
        wrap.addView(card, new FrameLayout.LayoutParams(-1, -2));

        // 上半部分：圆头像 + 文本条
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.TOP);
        head.setPadding(p.dp(16), p.dp(18), p.dp(16), 0);
        card.addView(head);

        // 圆头像
        View avatar = new View(a);
        avatar.setBackground(MiuixUi.shape(a, blockColor(p.night), AVATAR_DP / 2));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(p.dp(AVATAR_DP), p.dp(AVATAR_DP));
        head.addView(avatar, avatarParams);

        // 右侧两行：标题条 + 简介条
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1f);
        textsParams.leftMargin = p.dp(12);
        head.addView(texts, textsParams);

        texts.addView(bar(a, p, p.dp(p.night ? 150 : 150), p.dp(15)));      // 标题
        View d1 = bar(a, p, 0, p.dp(11));                                   // 简介第一行（通栏）
        LinearLayout.LayoutParams d1p = new LinearLayout.LayoutParams(-1, p.dp(11));
        d1p.topMargin = p.dp(10);
        texts.addView(d1, d1p);
        View d2 = bar(a, p, p.dp(170), p.dp(11));                           // 简介第二行（短一些）
        LinearLayout.LayoutParams d2p = new LinearLayout.LayoutParams(p.dp(170), p.dp(11));
        d2p.topMargin = p.dp(8);
        texts.addView(d2, d2p);

        // 作者条
        View author = bar(a, p, p.dp(90), p.dp(11));
        LinearLayout.LayoutParams authorParams = new LinearLayout.LayoutParams(p.dp(90), p.dp(11));
        authorParams.leftMargin = p.dp(16);
        authorParams.topMargin = p.dp(14);
        card.addView(author, authorParams);

        // 底部按钮条（右侧两个小块，对应"下载 / 编辑"）
        LinearLayout footer = new LinearLayout(a);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.RIGHT);
        footer.setPadding(p.dp(16), p.dp(16), p.dp(16), p.dp(14));
        card.addView(footer);
        footer.addView(bar(a, p, p.dp(64), p.dp(30)));
        View btn2 = bar(a, p, p.dp(64), p.dp(30));
        LinearLayout.LayoutParams btn2p = new LinearLayout.LayoutParams(p.dp(64), p.dp(30));
        btn2p.leftMargin = p.dp(8);
        footer.addView(btn2, btn2p);

        // 卡片自带圆角，光带只画在卡内
        wrap.setRadius(p.dp(18));
        return wrap;
    }

    /** 一根灰色条（圆角），宽 <= 0 表示通栏。 */
    private static View bar(android.app.Activity a, CenterPage p, int width, int height) {
        View v = new View(a);
        v.setBackground(MiuixUi.shape(a, blockColor(p.night), Math.max(3, height / 2)));
        v.setLayoutParams(new LinearLayout.LayoutParams(width <= 0 ? -1 : width, height));
        return v;
    }

    /** 连续放 n 张骨架（首屏加载用）。返回骨架高度总和，供"最少占满一屏"判断。 */
    static void showSkeletons(LinearLayout container, android.app.Activity a, CenterPage p, int n) {
        container.removeAllViews();
        for (int i = 0; i < n; i++) {
            ShimmerView skeleton = skeletonCard(a, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = p.dp(14);
            container.addView(skeleton, lp);
        }
    }

    // ───────────────────────── ② 扫光（全局同一道） ─────────────────────────

    /**
     * 带扫光的骨架容器。
     *
     * <p>光带相位是全局共享的（所有骨架在同一时刻亮在同一相对位置），因此视觉上
     * 是"一道光带同时扫过整个列表"，而不是每张卡各自闪。周期 1.5 秒。
     */
    static final class ShimmerView extends FrameLayout {
        private static final long PERIOD_MS = 1500L;

        /** 当前所有挂载中的骨架，由 {@link #driver} 统一驱动重绘。 */
        private static final List<ShimmerView> ACTIVE = new ArrayList<ShimmerView>();
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        private static boolean ticking;
        private static final Runnable DRIVER = new Runnable() {
            public void run() {
                ticking = false;
                for (int i = ACTIVE.size() - 1; i >= 0; i--) {
                    ShimmerView v = ACTIVE.get(i);
                    if (v.getParent() == null) {
                        ACTIVE.remove(i);
                        continue;
                    }
                    v.invalidate();
                }
                if (!ACTIVE.isEmpty()) start();
            }
        };

        private static void start() {
            if (ticking) return;
            ticking = true;
            MAIN.postDelayed(DRIVER, 16L);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private int radius;
        private LinearGradient gradient;
        private int gradientWidth;
        private int gradientHeight;

        ShimmerView(Context c) { super(c); }

        ShimmerView(Context c, AttributeSet attrs) { super(c, attrs); }

        void setRadius(int r) { radius = r; }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            setWillNotDraw(false);
            if (!ACTIVE.contains(this)) ACTIVE.add(this);
            start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            ACTIVE.remove(this);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            gradient = null;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;

            // 斜向光带：宽度约为卡片宽度的 60%，从左（-band）扫到右（width）。
            // 渐变只在尺寸变化时构建一次，每帧只做 translate —— 滚动期间几乎零分配。
            int w = getWidth(), h = getHeight();
            int band = Math.max(w * 6 / 10, MiuixUi.dp(getContext(), 120));
            float phase = (SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS;
            float x = -band + (w + band) * phase;
            float skew = h * 0.45f;
            if (gradient == null || gradientWidth != w || gradientHeight != h) {
                gradientWidth = w;
                gradientHeight = h;
                gradient = new LinearGradient(-band, -skew, band, h + skew,
                        new int[]{0x00FFFFFF, 0x22FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            }
            paint.setShader(gradient);

            int save = canvas.save();
            canvas.clipPath(clip);
            canvas.translate(x, 0f);
            canvas.drawRect(0, 0, w + band * 2, h, paint);
            canvas.restoreToCount(save);
            paint.setShader(null);
        }
    }

    // ───────────────────────── ③ 数据到达：块级错开浮现 ─────────────────────────

    /** 每块之间的错开间隔（毫秒）。 */
    private static final long BLOCK_STEP_MS = 60L;

    /**
     * 让真实卡片里的各个块从上到下依次浮现（淡入 + 上移 8px），同时把对应骨架淡出。
     *
     * @param card     真实卡片（其直接子 View 会被视为"块"）
     * @param skeleton 对应的骨架容器；可为 null。为 null 时只做浮现，不做骨架淡出
     */
    static void revealBlocks(final View card, final View skeleton, final boolean animate) {
        if (card == null) return;
        card.setVisibility(View.VISIBLE);
        if (!animate) {
            setAlphaTree(card, 1f);
            removeSkeleton(skeleton);
            return;
        }
        float offset = MiuixUi.dp(card.getContext(), 8);
        int blockIndex = 0;
        if (card instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) card;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                child.setAlpha(0f);
                child.setTranslationY(offset);
                child.animate().alpha(1f).translationY(0f)
                        .setStartDelay(blockIndex * BLOCK_STEP_MS)
                        .setDuration(240L)
                        .setInterpolator(new DecelerateInterpolator(1.6f))
                        .start();
                blockIndex++;
            }
        } else {
            card.setAlpha(0f);
            card.setTranslationY(offset);
            card.animate().alpha(1f).translationY(0f).setDuration(240L).start();
        }
        // 骨架同步淡出（比最后一块稍微晚一点收尾，视觉上是"内容盖上来"）
        if (skeleton != null) {
            long delay = Math.max(0, (blockIndex - 1)) * BLOCK_STEP_MS / 2;
            skeleton.animate().alpha(0f).setStartDelay(delay).setDuration(220L)
                    .withEndAction(new Runnable() {
                        public void run() { removeSkeleton(skeleton); }
                    }).start();
            // 淡出期间不接收触摸，避免"点到了已经没内容的骨架"
            skeleton.setEnabled(false);
        }
    }

    private static void setAlphaTree(View v, float alpha) {
        v.setAlpha(alpha);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).setAlpha(alpha);
        }
    }

    private static void removeSkeleton(View skeleton) {
        if (skeleton == null) return;
        android.view.ViewParent parent = skeleton.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(skeleton);
        }
    }

    // ───────────────────────── ④ 分页追加：卡片入场 ─────────────────────────

    private static final long CARD_STEP_MS = 50L;
    private static final long CARD_DURATION_MS = 450L;
    private static final int CARD_OFFSET_DP = 24;

    /** 新卡片从下方 24px 滑上来并淡入；同一批按 index 错开 50ms，像传送带接上。 */
    static void enterCard(View card, int index, boolean animate) {
        if (card == null) return;
        if (!animate) return;
        card.setAlpha(0f);
        card.setTranslationY(MiuixUi.dp(card.getContext(), CARD_OFFSET_DP));
        card.animate().alpha(1f).translationY(0f)
                .setStartDelay(index * CARD_STEP_MS)
                .setDuration(CARD_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    // ───────────────────────── ⑤ 触底预加载 ─────────────────────────

    /**
     * 滑到「离底部还剩约一屏」时提前触发加载。
     *
     * @param anchor 列表容器（用来向上找 ScrollView）
     * @param onLoad 触发回调（调用方自行去重，重复调用应当安全）
     */
    static void attachPreload(final View anchor, final Runnable onLoad) {
        final ScrollView scroll = findScroll(anchor);
        if (scroll == null || onLoad == null) return;
        final boolean[] armed = {false};
        scroll.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    public void onScrollChanged() {
                        View content = scroll.getChildCount() > 0 ? scroll.getChildAt(0) : null;
                        if (content == null) return;
                        int remaining = content.getHeight() - (scroll.getScrollY() + scroll.getHeight());
                        int screen = scroll.getHeight();
                        boolean near = remaining <= screen;
                        if (near && !armed[0]) {
                            armed[0] = true;
                            onLoad.run();
                        } else if (!near) {
                            armed[0] = false;
                        }
                    }
                });
    }

    /** 从 anchor 向上找最近的 ScrollView。 */
    static ScrollView findScroll(View anchor) {
        View cur = anchor;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur instanceof ScrollView) return (ScrollView) cur;
            android.view.ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    // ───────────────────────── ⑥ 底部状态：三个跳动小圆点 / 没有更多了 ─────────────────────────

    /** 三个依次跳动的小圆点（加载中）。 */
    static View dots(final android.app.Activity a, final CenterPage p, final boolean night) {
        final LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, p.dp(10), 0, p.dp(18));
        final View[] dot = new View[3];
        for (int i = 0; i < 3; i++) {
            dot[i] = new View(a);
            dot[i].setBackground(MiuixUi.shape(a, night ? 0x66FFFFFF : 0x55000000, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(p.dp(8), p.dp(8));
            if (i > 0) lp.leftMargin = p.dp(8);
            row.addView(dot[i], lp);
        }
        // 每个点自己循环跳：错开启动时间，形成"依次"的效果
        for (int i = 0; i < 3; i++) {
            final View d = dot[i];
            d.postDelayed(new Runnable() {
                public void run() {
                    if (d.getParent() == null) return;
                    d.animate().translationY(-p.dp(5)).setDuration(260L)
                            .withEndAction(new Runnable() {
                                public void run() {
                                    d.animate().translationY(0f).setDuration(260L).start();
                                }
                            }).start();
                    d.postDelayed(this, 780L);
                }
            }, i * 160L);
        }
        return row;
    }

    /** 底部文案（"正在加载" / "没有更多了" / "重试"）。 */
    static TextView footerText(final android.app.Activity a, final CenterPage p, String text) {
        TextView t = p.label(text, 13);
        t.setTextColor(p.secondary);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, p.dp(12), 0, p.dp(22));
        return t;
    }

    // ───────────────────────── ⑦ 多余骨架的收尾 ─────────────────────────

    /**
     * 淡出并移除一张用不上的骨架。
     *
     * <p>用于「首屏只返回了 3 条、骨架却铺了 6 张」的情况：多出来的骨架必须跟着消失，
     * 否则列表底部会残留几块灰色占位，看起来像"自动加载还在跑"。
     */
    static void fadeOutAndRemove(final View skeleton) {
        if (skeleton == null) return;
        skeleton.setEnabled(false);
        skeleton.animate().alpha(0f).setDuration(160L).withEndAction(new Runnable() {
            public void run() { removeSkeleton(skeleton); }
        }).start();
    }

    /** 把一批骨架里没有对应真实卡片的那些，全部淡出移除。 */
    static void dropUnusedSkeletons(java.util.List<View> skeletons, java.util.Set<View> used) {
        if (skeletons == null) return;
        for (int i = 0; i < skeletons.size(); i++) {
            View s = skeletons.get(i);
            if (used != null && used.contains(s)) continue;
            fadeOutAndRemove(s);
        }
    }

    // ───────────────────────── ⑧ 评论骨架 ─────────────────────────

    /**
     * 一组评论骨架：每条 = 小圆头像 + 昵称条 + 内容条，整体形状与真实评论一致。
     *
     * @param n 条数（首屏一般 2~3 条）
     */
    static LinearLayout commentSkeleton(android.app.Activity a, CenterPage p, int n) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < n; i++) {
            ShimmerView row = new ShimmerView(a);
            row.setRadius(p.dp(12));

            LinearLayout inner = new LinearLayout(a);
            inner.setOrientation(LinearLayout.HORIZONTAL);
            inner.setPadding(p.dp(2), p.dp(10), p.dp(2), p.dp(10));
            row.addView(inner, new FrameLayout.LayoutParams(-1, -2));

            View avatar = new View(a);
            avatar.setBackground(MiuixUi.shape(a, blockColor(p.night), 16));
            inner.addView(avatar, new LinearLayout.LayoutParams(p.dp(32), p.dp(32)));

            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1f);
            textsParams.leftMargin = p.dp(10);
            inner.addView(texts, textsParams);
            texts.addView(bar(a, p, p.dp(76), p.dp(10)));
            View body = bar(a, p, 0, p.dp(10));
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, p.dp(10));
            bodyParams.topMargin = p.dp(9);
            texts.addView(body, bodyParams);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = p.dp(6);
            box.addView(row, lp);
        }
        return box;
    }

    // ───────────────────────── ⑩ 内容随 Tab 方向轻推入 ─────────────────────────

    /**
     * 页面内容沿点击方向轻推入：从偏移位置滑到原位并淡入，与胶囊流动同步（约 300ms）。
     *
     * @param direction +1 = 往右切（内容从右侧推入）；-1 = 往左切
     */
    static void pushIn(final View content, int direction) {
        if (content == null) return;
        float offset = MiuixUi.dp(content.getContext(), 28) * (direction >= 0 ? 1 : -1);
        content.animate().cancel();
        content.setTranslationX(offset);
        content.setAlpha(0.72f);
        content.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(300L)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
    }

    // ───────────────────────── ⑨ 详情页骨架 ─────────────────────────

    /**
     * 详情页骨架：圆头像 + 标题条 +「简介」块 +「创作者/上传时间」块 + 按钮行 + 评论区。
     * 结构与 {@link ItemDetailPage} 的真实版面一一对应，所以内容浮现时页面不会跳。
     */
    static LinearLayout detailSkeleton(android.app.Activity a, CenterPage p) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);

        // ① 顶部：圆头像 + 两行标题
        box.addView(skeletonLines(a, p, new int[]{0, 150, 220}));

        // ② 「简介」折叠块
        box.addView(skeletonLines(a, p, new int[]{110, 0, 200}));

        // ③ 「创作者 / 上传时间 / 查看主页」三行
        box.addView(skeletonLines(a, p, new int[]{90, 90, 90}));

        // ④ 按钮行 + 头像圈
        box.addView(skeletonLines(a, p, new int[]{-1}));

        // ⑤ 评论区（两条）
        box.addView(commentSkeleton(a, p, 2));
        return box;
    }

    /**
     * 个人中心 / 创作者主页的骨架：头像 + 昵称 + 关注按钮 + 两块列表内容。
     * 结构照着这两个页面的真实版面来，所以数据到达时不会跳。
     */
    static LinearLayout profileSkeleton(android.app.Activity a, CenterPage p) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        // 圆头像 + 昵称两条
        box.addView(skeletonLines(a, p, new int[]{-2}));
        // 关注 / 编辑按钮那一行
        box.addView(skeletonLines(a, p, new int[]{-1}));
        // 四列计数
        box.addView(skeletonLines(a, p, new int[]{0, 200}));
        // 下面两块内容
        box.addView(skeletonLines(a, p, new int[]{0, 0, 0}));
        return box;
    }

    /**
     * 一个"卡片状"骨架块：外面一张圆角卡，里面按 widths 逐行放灰条。
     *
     * @param widths 每行的宽度（dp）；0 = 通栏，-1 = 按钮行（按钮块 + 圆头像），
     *               -2 = 头像行（大圆头像 + 右侧两条）
     */
    private static ShimmerView skeletonLines(android.app.Activity a, CenterPage p, int[] widths) {
        ShimmerView card = new ShimmerView(a);
        card.setRadius(p.dp(ModuleTheme.CARD_RADIUS));

        LinearLayout inner = new LinearLayout(a);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(p.dp(16), p.dp(18), p.dp(16), p.dp(18));
        inner.setBackground(MiuixUi.shape(a, ModuleTheme.surface(p.night), ModuleTheme.CARD_RADIUS));
        card.addView(inner, new FrameLayout.LayoutParams(-1, -2));

        for (int i = 0; i < widths.length; i++) {
            View line;
            LinearLayout.LayoutParams lp;
            if (widths[i] == -2) {
                // 头像行：大圆头像 + 右侧两条（昵称 / 说明）
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.TOP);
                View circle = bar(a, p, p.dp(64), p.dp(64));
                row.addView(circle, new LinearLayout.LayoutParams(p.dp(64), p.dp(64)));
                LinearLayout right = new LinearLayout(a);
                right.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
                rp.leftMargin = p.dp(14);
                row.addView(right, rp);
                right.addView(bar(a, p, p.dp(120), p.dp(16)));
                View second = bar(a, p, p.dp(190), p.dp(12));
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(p.dp(190), p.dp(12));
                sp.topMargin = p.dp(12);
                right.addView(second, sp);
                lp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) lp.topMargin = p.dp(12);
                inner.addView(row, lp);
                continue;
            }
            if (widths[i] == -1) {
                // 按钮行：左侧一个按钮块 + 右侧一个圆头像
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                View button = bar(a, p, 0, p.dp(44));
                row.addView(button, new LinearLayout.LayoutParams(0, p.dp(44), 1f));
                View circle = bar(a, p, p.dp(44), p.dp(44));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(p.dp(44), p.dp(44));
                cp.leftMargin = p.dp(12);
                row.addView(circle, cp);
                lp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) lp.topMargin = p.dp(12);
                inner.addView(row, lp);
                continue;
            }
            line = bar(a, p, widths[i] == 0 ? 0 : p.dp(widths[i]), p.dp(13));
            lp = new LinearLayout.LayoutParams(widths[i] == 0 ? -1 : p.dp(widths[i]), p.dp(13));
            if (i > 0) lp.topMargin = p.dp(14);
            inner.addView(line, lp);
        }

        LinearLayout.LayoutParams out = new LinearLayout.LayoutParams(-1, -2);
        out.bottomMargin = p.dp(12);
        card.setLayoutParams(out);
        return card;
    }
}