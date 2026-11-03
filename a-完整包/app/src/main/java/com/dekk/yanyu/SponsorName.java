package com.dekk.yanyu;

import android.animation.ValueAnimator;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 赞助用户的昵称样式：灿金 + 扫光（骨架屏那种光带，从名字左边扫到右边）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>整条光带用 {@link LinearGradient} + local matrix 平移，不重绘位图、不改布局；</li>
 *   <li><b>所有名字共用一个 ValueAnimator</b>：评论区一屏十几个名字也不会开十几个动画；</li>
 *   <li>每次刷新只 {@code invalidate()} 那个 TextView，掉出屏幕 / 被移除的自动注销，
 *       不留动画、不留引用。</li>
 * </ul>
 */
final class SponsorName {

    /** 灿金主色。 */
    static final int GOLD = 0xFFF3C64B;
    /** 扫光高光。 */
    private static final int SHINE = 0xFFFFF7DC;
    /** 光带单程时长（含扫完后的停顿）。 */
    private static final long CYCLE_MS = 2100L;
    /** 一个周期里真正在扫的比例，剩下的时间是「停住不闪」。 */
    private static final float SWEEP_RATIO = 0.62f;

    private static final List<Shine> live = new ArrayList<Shine>();
    private static ValueAnimator animator;

    private SponsorName() { }

    /** 非赞助用户：恢复普通颜色并摘掉渐变；赞助用户：上金 + 开始扫光。 */
    static void apply(TextView view, boolean sponsor, int normalColor) {
        if (view == null) return;
        try {
            if (!sponsor) {
                Paint paint = view.getPaint();
                if (paint != null) paint.setShader(null);
                view.setTextColor(normalColor);
                return;
            }
            view.setTextColor(GOLD);
            register(view);
        } catch (Throwable t) {
            // 扫光是装饰：失败就退回普通颜色，绝不让页面崩
            try {
                Paint paint = view.getPaint();
                if (paint != null) paint.setShader(null);
                view.setTextColor(normalColor);
            } catch (Throwable ignored) { }
        }
    }

    private static final class Shine {
        final WeakReference<TextView> view;
        LinearGradient gradient;
        final Matrix matrix = new Matrix();
        float span;

        Shine(TextView value) {
            view = new WeakReference<TextView>(value);
        }
    }

    private static void register(final TextView view) {
        synchronized (live) {
            for (int i = 0; i < live.size(); i++) {
                TextView item = live.get(i).view.get();
                if (item == view) return;          // 已经在了
                if (item == null) live.remove(i--);
            }
            live.add(new Shine(view));
            if (animator == null) start();
        }
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { }
            public void onViewDetachedFromWindow(View v) { unregister(view); }
        });
    }

    private static void unregister(TextView view) {
        synchronized (live) {
            for (int i = 0; i < live.size(); i++) {
                TextView item = live.get(i).view.get();
                if (item == null || item == view) {
                    if (item == view) item.getPaint().setShader(null);
                    live.remove(i--);
                }
            }
            if (live.isEmpty()) stop();
        }
    }

    private static void start() {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                Object value = animation.getAnimatedValue();
                if (!(value instanceof Float)) return;
                frame(((Float) value).floatValue());
            }
        });
        animator.start();
    }

    private static void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    /** 一帧：把每一条光带按同一个进度平移过去。 */
    private static void frame(float value) {
        float sweep = Math.min(1f, value / SWEEP_RATIO);
        synchronized (live) {
            for (int i = 0; i < live.size(); i++) {
                Shine shine = live.get(i);
                TextView view = shine.view.get();
                if (view == null) {
                    live.remove(i--);
                    continue;
                }
                int width = view.getWidth();
                if (width <= 0) continue;
                Paint paint = view.getPaint();
                if (paint == null) continue;
                try {
                if (shine.gradient == null || shine.span != width) {
                    shine.gradient = new LinearGradient(0f, 0f, width, 0f,
                            new int[]{GOLD, GOLD, SHINE, GOLD, GOLD},
                            new float[]{0f, 0.38f, 0.5f, 0.62f, 1f},
                            Shader.TileMode.CLAMP);
                    shine.span = width;
                    paint.setShader(shine.gradient);
                }
                // 光带宽度 = 文本宽度；从左外侧走到右外侧
                float travel = width * 1.7f;
                float dx = -width * 0.35f + travel * sweep;
                shine.matrix.setTranslate(dx, 0f);
                shine.gradient.setLocalMatrix(shine.matrix);
                view.invalidate();
                } catch (Throwable ignored) { }
            }
            if (live.isEmpty()) stop();
        }
    }

    /** 从 JSON 里读赞助标记（服务端下发 0/1，也兼容布尔）。 */
    static boolean flag(org.json.JSONObject row, String key) {
        if (row == null) return false;
        if (row.optBoolean(key, false)) return true;
        return row.optInt(key, 0) == 1;
    }
}
