package com.dekk.yanyu;

import android.view.animation.Interpolator;

/**
 * 弹簧曲线（解析解，不是猜出来的回弹）。
 *
 * <p>阻尼振荡的归一化位置：{@code x(t) = 1 - e^(-ζωt)·cos(ω·2π·t)}。
 * {@code t=1} 时 {@code e^(-ζω)} 已经很小（默认 &lt;0.3%），所以动画结束时不会留一个
 * 停不下来的偏移，只用来看"末端那点回弹"。
 */
final class SpringInterpolator implements Interpolator {

    /** 弹窗升起 / 关闭：末端有很轻的回弹（默认用它）。 */
    static final Interpolator DEFAULT = new SpringInterpolator(6.0f, 0.95f);
    /** 背后页面缩放这类"配角"动画：几乎不过冲，避免和主对象打架。 */
    static final Interpolator SOFT = new SpringInterpolator(8.0f, 0.45f);
    /** 拖拽回弹：更"弹"一点，手感更明确。 */
    static final Interpolator BOUNCY = new SpringInterpolator(5.0f, 1.1f);

    private final float damping;
    private final float frequency;

    SpringInterpolator(float damping, float frequency) {
        this.damping = damping;
        this.frequency = frequency;
    }

    public float getInterpolation(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        double decay = Math.exp(-damping * t);
        return (float) (1d - decay * Math.cos(frequency * 2d * Math.PI * t));
    }
}