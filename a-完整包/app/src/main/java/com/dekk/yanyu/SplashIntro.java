package com.dekk.yanyu;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块功能页的开屏动画：白底黑字，DEKK 四个字母逐笔描线画出来。
 *
 * <p>只播一次：播完（或被打断）就把 {@code dekk_prefs/splash_played} 置 true，
 * 之后打开功能页直接进内容，不再走动画。想重放就清模块数据或把这个键删掉。
 *
 * <p>几何按 512×180 的签名手稿（dekk_splash_preview.svg）1:1 搬进来，
 * 描线用 {@link PathMeasure#getSegment} 按笔画进度截取，不依赖字体。
 */
final class SplashIntro extends View {

    private static final String PREF = "dekk_prefs";
    private static final String KEY = "splash_played";

    /** 稿件坐标系。 */
    private static final float BOX_W = 472f;
    private static final float BOX_H = 140f;
    private static final float STROKE_WIDTH = 12f;
    private static final long DRAW_MS = 1500L;
    private static final long FADE_MS = 240L;
    /** 保险丝：动画没起来也不该把页面挡住。 */
    private static final long WATCHDOG_MS = 4000L;

    private static final int INK = 0xFF0B0B0C;
    private static final int PAPER = 0xFFFFFFFF;

    private final List<Path> strokes = new ArrayList<Path>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path scratch = new Path();
    private final PathMeasure measure = new PathMeasure();

    private float progress;
    private boolean finished;
    private Runnable after;
    private ValueAnimator animator;

    private SplashIntro(Context context) {
        super(context);
        buildStrokes();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(STROKE_WIDTH);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(INK);
        setBackgroundColor(PAPER);
        setClickable(true);
    }

    /**
     * 第一次打开功能页时播放。
     *
     * @return true 表示这次真的播了（{@code after} 会在动画结束后回调）；false 表示已经播过，调用方自己接着跑。
     */
    static boolean playOnce(android.app.Activity activity, final Runnable after) {
        if (activity == null) return false;
        // 挂在 window 的 content 根上：功能页里 rebuildContent() 会 removeAllViews，
        // 挂在页面容器里会被一起清掉，动画就断了。
        View root = activity.findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) return false;
        final ViewGroup host = (ViewGroup) root;
        SharedPreferences prefs = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY, false)) return false;
        // 先落标记：动画中途被杀掉也不该下次再播一遍
        if (!prefs.edit().putBoolean(KEY, true).commit()) return false;

        final SplashIntro splash = new SplashIntro(activity);
        splash.after = after;
        host.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        splash.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { splash.finishNow(); }
        });
        splash.postDelayed(new Runnable() {
            public void run() { splash.finishNow(); }
        }, WATCHDOG_MS);
        splash.start();
        return true;
    }

    /** 想重放一次（调试用）。 */
    static void forget(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    private void start() {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DRAW_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.2f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                Object value = animation.getAnimatedValue();
                if (value instanceof Float) {
                    progress = ((Float) value).floatValue();
                    invalidate();
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                progress = 1f;
                invalidate();
                // 画完停一拍再淡出，不然最后一笔看不清
                postDelayed(new Runnable() { public void run() { fadeOut(); } }, 180L);
            }
        });
        animator.start();
    }

    private void fadeOut() {
        animate().alpha(0f).setDuration(FADE_MS).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finishNow();
            }
        }).start();
    }

    private void finishNow() {
        if (finished) return;
        finished = true;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        ViewGroup parent = getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null;
        if (parent != null) parent.removeView(this);
        Runnable next = after;
        after = null;
        if (next != null) next.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        // 留 38% 的边距，和谷歌开屏那种「字在大片留白里」的比例接近
        float scale = Math.min(width * 0.62f / BOX_W, height * 0.62f / BOX_H);
        float left = (width - BOX_W * scale) / 2f;
        float top = (height - BOX_H * scale) / 2f;

        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        float per = progress * strokes.size();
        for (int i = 0; i < strokes.size(); i++) {
            float local = per - i;
            if (local <= 0f) break;
            if (local > 1f) local = 1f;
            Path stroke = strokes.get(i);
            measure.setPath(stroke, false);
            scratch.reset();
            measure.getSegment(0f, measure.getLength() * local, scratch, true);
            canvas.drawPath(scratch, paint);
        }
        canvas.restore();
    }

    private void line(float x1, float y1, float x2, float y2) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        strokes.add(path);
    }

    private void curve(float x1, float y1, float c1x, float c1y, float c2x, float c2y, float x2, float y2) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.cubicTo(c1x, c1y, c2x, c2y, x2, y2);
        strokes.add(path);
    }

    /** DEKK 的 13 笔，顺序就是手写顺序。 */
    private void buildStrokes() {
        // D
        line(0f, 0f, 0f, 140f);
        curve(0f, 0f, 72f, 0f, 104f, 26f, 104f, 70f);
        curve(104f, 70f, 104f, 114f, 72f, 140f, 0f, 140f);
        // E
        line(138f, 0f, 138f, 140f);
        line(138f, 0f, 220f, 0f);
        line(138f, 70f, 204f, 70f);
        line(138f, 140f, 220f, 140f);
        // K
        line(254f, 0f, 254f, 140f);
        line(254f, 78f, 338f, 0f);
        line(254f, 78f, 346f, 140f);
        // K
        line(380f, 0f, 380f, 140f);
        line(380f, 78f, 464f, 0f);
        line(380f, 78f, 472f, 140f);
    }
}
