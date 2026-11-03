package com.dekk.yanyu;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Miuix 风格的胶囊开关，保留原有业务回调接口。
 *
 * <p>只依赖框架自身，不依赖 Compose / Material 库，因此不会和宿主主题冲突。
 */
public class M3SwitchView extends View {

    /** 状态变化回调。 */
    public interface OnCheckedChangeListener {
        void onCheckedChanged(M3SwitchView view, boolean checked);
    }

    /** 轨道尺寸（Material3 规范：52 x 32 dp，滑块 24 dp）。 */
    private static final float TRACK_WIDTH_DP = 52f;
    private static final float TRACK_HEIGHT_DP = 30f;
    private static final float THUMB_SIZE_DP = 24f;
    private static final int ANIM_DURATION_MS = 180;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final float density;

    private boolean checked;
    private float thumbProgress;
    private ValueAnimator animator;

    private OnCheckedChangeListener listener;

    private int trackOnColor = 0xFF4D6BFE;
    private int trackOffColor = 0x1F000000;
    private int thumbColor = Color.WHITE;

    public M3SwitchView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(true);
        // 按下时轻微回缩（与滑块回弹叠加，点起来更"跟手"）
        Motion.pressable(this, 0.93f);
    }

    public void setTrackColors(int onColor, int offColor) {
        this.trackOnColor = ModuleTheme.ACCENT;
        this.trackOffColor = (getResources().getConfiguration().uiMode & 48)==32?0xFF454545:0xFFE0E0E0;
        invalidate();
    }

    public void setThumbColor(int color) {
        this.thumbColor = color;
        invalidate();
    }

    public boolean isChecked() {
        return checked;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    public void setChecked(boolean value) {
        setChecked(value, false);
    }

    public void setChecked(boolean value, boolean animate) {
        if (checked == value && animate) {
            return;
        }
        checked = value;
        if (animate) {
            startAnim(value ? 1f : 0f);
        } else {
            thumbProgress = value ? 1f : 0f;
            invalidate();
        }
    }

    private void startAnim(float target) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(thumbProgress, target);
        animator.setDuration(ANIM_DURATION_MS);
        // 滑块滑到头时轻微回弹一下，比线性到位更有"卡进去"的手感
        animator.setInterpolator(new android.view.animation.OvershootInterpolator(1.35f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                thumbProgress = ((Float) animation.getAnimatedValue()).floatValue();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension((int) (TRACK_WIDTH_DP * density + 0.5f),
                (int) (TRACK_HEIGHT_DP * density + 0.5f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float radius = h / 2f;

        trackRect.set(0f, 0f, w, h);
        trackPaint.setColor(blend(trackOffColor, trackOnColor, thumbProgress));
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint);

        float thumbRadius = THUMB_SIZE_DP * density / 2f;
        float padding = (h - thumbRadius * 2f) / 2f;
        float startX = padding + thumbRadius;
        float endX = w - padding - thumbRadius;
        float thumbX = startX + (endX - startX) * thumbProgress;

        thumbPaint.setColor(thumbColor);
        canvas.drawCircle(thumbX, h / 2f, thumbRadius, thumbPaint);
    }

    @Override
    public boolean performClick() {
        if (!isEnabled()) return false;
        toggle();
        super.performClick();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(checked);
    }

    private void toggle() {
        checked = !checked;
        startAnim(checked ? 1f : 0f);
        if (listener != null) {
            listener.onCheckedChanged(this, checked);
        }
    }

    private static int blend(int from, int to, float fraction) {
        float f = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.argb(a, r, g, b);
    }
}