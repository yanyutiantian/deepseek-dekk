package com.dekk.yanyu;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 分段标签 / 导航栏的「水滴胶囊」：选中项背后的色块像一滴水一样流向下一个页签。
 *
 * <p>它不是简单的「左边界一起动」——那样只会看到一个方块平移。水滴感来自
 * <b>前沿（leading）和后沿（trailing）用两条不同的时间曲线</b>：
 * <ol>
 *   <li>前沿先冲出去（easeOutCubic + 轻微过冲），后沿慢半拍才跟上（延迟 16% 后走 easeInOutCubic）；</li>
 *   <li>前后沿之间的距离 = 被拉长的程度，胶囊因此在中途变长；</li>
 *   <li>同时按拉长程度把高度压扁一点（最多 22%），观感才像被拉伸的水滴；</li>
 *   <li>最后 20% 做轻微回弹，停稳时不生硬。</li>
 * </ol>
 * 全程 {@link #DURATION} 毫秒。
 */
final class TabLiquid {
    /** 整体时长（毫秒）。 */
    static final long DURATION = 400L;
    /** 后沿相对前沿的启动延迟（占总时长的比例）。 */
    private static final float TRAIL_DELAY = 0.16f;
    /** 前沿的过冲强度：越大回弹越明显。 */
    private static final float LEAD_BACK = 0.16f;
    /** 拉长时最多压扁多少高度。 */
    private static final float SQUASH = 0.22f;

    private final View host;
    private final List<View> items = new ArrayList<View>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF capsule = new RectF();

    private int color = 0xFFFFFFFF;
    private float radiusDp = 12f;
    private float offsetX, offsetY;
    private int fromIndex = -1;
    private int toIndex = -1;
    private float progress = 1f;
    private ValueAnimator animator;
    /** 只画一次的静态状态（列表还没布局时用来占位）。 */
    private boolean dirty = true;

    TabLiquid(View host) {
        this.host = host;
    }

    void setColor(int value, float radius) {
        color = value;
        radiusDp = radius;
        host.invalidate();
    }

    /** tabs 相对 host 的偏移（例如 items 被居中摆放时，要补上它的 left/top）。 */
    void setItemsOffset(float dx, float dy) {
        offsetX = dx;
        offsetY = dy;
    }

    void addItem(View item) {
        items.add(item);
    }

    int size() {
        return items.size();
    }

    /** 不带动画：直接落在指定项上（首次显示 / 重建页面时用）。 */
    void setImmediate(int index) {
        stop();
        fromIndex = index;
        toIndex = index;
        progress = 1f;
        dirty = true;
        host.invalidate();
    }

    /** 水滴流动到指定项。 */
    void animateTo(int index) {
        if (index < 0 || index >= items.size()) return;
        if (toIndex == index && progress >= 1f) return;
        fromIndex = toIndex >= 0 ? toIndex : index;
        toIndex = index;
        progress = 0f;
        stop();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                progress = animation.getAnimatedFraction();
                host.invalidate();
            }
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    /** 由 host 的 onDraw 调用：把胶囊画在背景之上、文字之下。 */
    void drawCapsule(Canvas canvas) {
        if (items.isEmpty() || toIndex < 0) return;
        int count = items.size();

        // 边界每帧现取：这样布局完成后自动就是对的，不需要额外的同步逻辑
        View target = items.get(Math.min(toIndex, count - 1));
        if (target.getWidth() <= 0) return;

        float lead, trail;
        if (fromIndex < 0 || fromIndex >= count || fromIndex == toIndex) {
            lead = 1f;
            trail = 1f;
        } else {
            float t = progress;
            lead = clamp(back(easeOutCubic(clamp(t * 1.12f)), LEAD_BACK));
            trail = easeInOutCubic(clamp((t - TRAIL_DELAY) / (1f - TRAIL_DELAY)));
        }

        View from = items.get(fromIndex >= 0 ? Math.min(fromIndex, count - 1) : toIndex);
        float fromLeft = from.getLeft() + offsetX;
        float fromRight = from.getRight() + offsetX;
        float toLeft = target.getLeft() + offsetX;
        float toRight = target.getRight() + offsetX;

        float left, right;
        if (toLeft >= fromLeft) {
            // 往右流：右边界是前沿，左边界是后沿
            left = lerp(fromLeft, toLeft, trail);
            right = lerp(fromRight, toRight, lead);
        } else {
            left = lerp(fromLeft, toLeft, lead);
            right = lerp(fromRight, toRight, trail);
        }

        // 拉长程度 → 高度压扁一点，像被拉伸的水滴
        float stretch = Math.abs(lead - trail);
        float full = target.getHeight();
        float height = full * (1f - SQUASH * stretch);
        float top = target.getTop() + offsetY + (full - height) / 2f;

        capsule.set(left, top, Math.max(right, left + height), top + height);
        paint.setColor(color);
        float r = Math.min(radiusDp * host.getResources().getDisplayMetrics().density,
                capsule.height() / 2f);
        canvas.drawRoundRect(capsule, r, r, paint);
    }

    void drawIfDirty(Canvas canvas) {
        if (dirty) {
            dirty = false;
            if (toIndex >= 0 && fromIndex < 0) fromIndex = toIndex;
        }
        drawCapsule(canvas);
    }

    // ───────── 缓动 ─────────

    private static float clamp(float t) {
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static float easeInOutCubic(float t) {
        return t < 0.5f ? 4f * t * t * t
                : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    /** 轻微过冲后再收回：t=1 时回到 1，中途越界一点。 */
    private static float back(float t, float s) {
        float u = t - 1f;
        return 1f + (s + 1f) * u * u * u + s * u * u;
    }

    // ───────── 选中项的"先缩再弹回" ─────────

    /** 新选中的图标/文字先缩小、再弹出并回稳。 */
    static void popIn(final View view) {
        if (view == null) return;
        view.setScaleX(0.88f);
        view.setScaleY(0.88f);
        view.animate().scaleX(1.06f).scaleY(1.06f)
                .setDuration(140L)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(new Runnable() {
                    public void run() {
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(180L)
                                .setInterpolator(new OvershootInterpolator(1.5f))
                                .start();
                    }
                }).start();
    }

    /** 文字颜色渐变：旧项淡出强调色，新项淡入强调色。 */
    static void fadeTextColor(TextView out, TextView in, int idle, int active, long duration) {
        if (out == null && in == null) return;
        final ArgbEvaluator evaluator = new ArgbEvaluator();
        final TextView from = out, to = in;
        final int idleColor = idle, activeColor = active;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(duration);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                float f = animation.getAnimatedFraction();
                if (from != null) {
                    from.setTextColor((Integer) evaluator.evaluate(f, activeColor, idleColor));
                }
                if (to != null) {
                    to.setTextColor((Integer) evaluator.evaluate(f, idleColor, activeColor));
                }
            }
        });
        va.start();
    }

    // ───────── 承载胶囊的容器 ─────────

    /**
     * 分段标签的轨道：自身是灰底圆角，胶囊在它的 onDraw 里绘制，
     * 因此位于背景之上、文字之下（正好是"选中项背后的色块"）。
     */
    static final class LiquidTrack extends LinearLayout {
        private TabLiquid liquid;

        LiquidTrack(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void bind(TabLiquid value) {
            liquid = value;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (liquid != null) liquid.drawCapsule(canvas);
        }
    }
}
