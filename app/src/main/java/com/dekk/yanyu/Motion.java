package com.dekk.yanyu;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import java.util.List;
import java.util.WeakHashMap;

/**
 * 模块内统一的交互 / 状态动效。
 *
 * <p>硬规矩（照 finesse 的动效规则来的，简单但有效）：
 * <ol>
 *   <li><b>只动 transform 与 opacity</b>（scale / translation / alpha）——不触发 requestLayout，
 *       中端机上稳定 60fps；不碰 width / top / margin；</li>
 *   <li><b>每个动效都有理由</b>：反馈（按下了）、状态（开/关、加载中）、层级（页面进出）；</li>
 *   <li><b>终态先写好</b>，动画只是从别处"接上"；把动画全去掉，页面依然完整可读；
 *       所以这里所有方法都是"设一下就恢复原来的样子"，不会把控件留在半途。</li>
 * </ol>
 */
final class Motion {

    private Motion() { }

    /** 按下时的缩放（轻微即可，过头会显得廉价）。 */
    private static final float PRESS_SCALE = 0.965f;

    // ───────── ① 按压反馈 ─────────

    /**
     * 给任意可点 View 加统一按压反馈：按下轻微缩小 + 略暗，松手用弹簧回位。
     *
     * <p>返回 false，所以不会吃掉原来的 click 事件。
     */
    static void pressable(final View view) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!v.isEnabled()) return false;
                        v.animate().cancel();
                        v.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).alpha(0.9f)
                                .setDuration(90L)
                                .setInterpolator(new DecelerateInterpolator(1.5f))
                                .start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(260L)
                                .setInterpolator(new OvershootInterpolator(1.2f))
                                .start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /** 批量加按压反馈。 */
    static void pressable(View... views) {
        if (views == null) return;
        for (int i = 0; i < views.length; i++) pressable(views[i]);
    }

    /** 整行/整卡这类"大块"目标的按下缩放：更轻，避免视觉上"整块抖动"。 */
    static final float ROW_PRESS_SCALE = 0.985f;

    /** 指定缩放幅度的按压反馈（大块目标用它）。 */
    static void pressable(final View view, final float scale) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!v.isEnabled()) return false;
                        v.animate().cancel();
                        v.animate().scaleX(scale).scaleY(scale).alpha(0.92f)
                                .setDuration(90L)
                                .setInterpolator(new DecelerateInterpolator(1.5f))
                                .start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(260L)
                                .setInterpolator(new OvershootInterpolator(1.2f))
                                .start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /** 点击确认的轻微脉冲：用于「已复制 / 已保存」这类即时反馈。 */
    static void pulse(final View view) {
        if (view == null) return;
        view.animate().cancel();
        view.animate().scaleX(1.06f).scaleY(1.06f)
                .setDuration(130L)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(new Runnable() {
                    public void run() {
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(200L)
                                .setInterpolator(new OvershootInterpolator(1.4f))
                                .start();
                    }
                }).start();
    }

    // ───────── ② 加载 / 忙碌态 ─────────

    /** 正在呼吸（加载中）的 View，用于 stopped 时取消。 */
    private static final WeakHashMap<View, Boolean> BREATHING = new WeakHashMap<View, Boolean>();

    /**
     * 加载态呼吸：透明度在 0.55~1 之间柔和往返。
     *
     * <p>用在"正在加载…"这类文案上，比一个静止的灰字更明确地告诉用户"还在跑"。
     */
    static void breath(final View view) {
        if (view == null) return;
        BREATHING.put(view, Boolean.TRUE);
        breathLoop(view, true);
    }

    private static void breathLoop(final View view, final boolean dim) {
        if (!BREATHING.containsKey(view) || view.getParent() == null) return;
        view.animate().alpha(dim ? 0.55f : 1f)
                .setDuration(820L)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() { breathLoop(view, !dim); }
                }).start();
    }

    /** 停止呼吸并恢复不透明。 */
    static void stopBreath(View view) {
        if (view == null) return;
        BREATHING.remove(view);
        view.animate().cancel();
        view.setAlpha(1f);
    }

    /**
     * 按钮忙碌态：禁用 + 呼吸。
     *
     * <p>替代"点一下变成'加载中…'然后没反应"，让按钮自己说明状态。
     */
    static void busy(TextView button, boolean busy) {
        if (button == null) return;
        button.setEnabled(!busy);
        if (busy) breath(button);
        else stopBreath(button);
    }

    // ───────── ③ 状态过渡 ─────────

    /** 文字颜色渐变（不会硬切）；结束时一定落在目标色上，去掉动画也正确。 */
    static void color(final TextView view, final int to, long duration) {
        if (view == null) return;
        final int from = view.getCurrentTextColor();
        if (from == to) {
            view.setTextColor(to);
            return;
        }
        final ArgbEvaluator evaluator = new ArgbEvaluator();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                float f = animation.getAnimatedFraction();
                view.setTextColor((Integer) evaluator.evaluate(f, from, to));
            }
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                view.setTextColor(to);
            }
        });
        animator.start();
    }

    /** 显隐过渡：展开/收起这类状态改变用淡入 + 轻微上移，避免"啪"地出现。 */
    static void reveal(final View view, boolean visible, final boolean animate) {
        if (view == null) return;
        if (!animate) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
            view.setAlpha(1f);
            view.setTranslationY(0f);
            return;
        }
        if (visible) {
            view.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            view.setTranslationY(MiuixUi.dp(view.getContext(), 8));
            view.animate().alpha(1f).translationY(0f)
                    .setDuration(240L)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
        } else {
            view.animate().alpha(0f).translationY(MiuixUi.dp(view.getContext(), 4))
                    .setDuration(160L)
                    .withEndAction(new Runnable() {
                        public void run() {
                            view.setVisibility(View.GONE);
                            view.setAlpha(1f);
                            view.setTranslationY(0f);
                        }
                    }).start();
        }
    }

    // ───────── ④ 列表错开入场 ─────────

    /** 一组 View 从下方 10px 处依次浮现（每项错开 stepMs）。 */
    static void stagger(List<View> views, long stepMs) {
        if (views == null) return;
        for (int i = 0; i < views.size(); i++) {
            final View view = views.get(i);
            if (view == null) continue;
            view.setAlpha(0f);
            view.setTranslationY(MiuixUi.dp(view.getContext(), 10));
            view.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * stepMs)
                    .setDuration(260L)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
        }
    }
}