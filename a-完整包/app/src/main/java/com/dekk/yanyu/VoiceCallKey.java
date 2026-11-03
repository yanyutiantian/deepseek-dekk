package com.dekk.yanyu;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;

/**
 * 「替用户长按宿主语音键」。
 *
 * <p>宿主只支持按住说话，通话页里不想让用户一直按着，所以这里合成一对触摸事件
 * 直接投给宿主的 {@code AndroidComposeView}：
 * <ul>
 *   <li>{@link #press}：先按「已标定的点」，没标定就按输入框右下角一片区域逐点扫描；</li>
 *   <li>每个点按下后等 900ms，看宿主有没有真的开始录音（{@code AudioRecord.startRecording}
 *       被 {@link VoiceCallHook} 打了点）。没录上就换下一个点，录上了就采纳并记下来；</li>
 *   <li>{@link #release}：发 ACTION_UP，宿主自己走「松开 → 识别 → 发送」。</li>
 * </ul>
 * 这样即使分辨率、输入栏高度变了，第一次也能自己摸到位置。
 */
final class VoiceCallKey {

    interface Callback {
        void onResult(boolean recording);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView";
    private static final int CONFIRM_MS = 900;

    private static View target;
    private static int originX, originY;
    private static long downTime;
    private static float localX, localY;
    private static boolean holding;
    private static volatile long recordingAt;

    private static Activity activity;
    private static Callback callback;
    private static ArrayList<float[]> queue;
    private static int cursor;
    private static long attemptMark;
    private static float pointX, pointY;

    private VoiceCallKey() { }

    static long recordingAt() {
        return recordingAt;
    }

    /** 宿主真正开始录音的时间（由 {@link VoiceCallHook} 打点）。 */
    static void markRecording() {
        recordingAt = SystemClock.elapsedRealtime();
    }

    static boolean holding() {
        return holding;
    }

    // ── 触摸点记录（自动标定用） ────────────────────────────

    private static float lastTouchX, lastTouchY;
    private static long lastTouchAt;

    static void noteTouch(float screenX, float screenY) {
        lastTouchX = screenX;
        lastTouchY = screenY;
        lastTouchAt = SystemClock.elapsedRealtime();
    }

    static boolean recentTouch(long windowMs) {
        return SystemClock.elapsedRealtime() - lastTouchAt < windowMs;
    }

    static float lastTouchX() {
        return lastTouchX;
    }

    static float lastTouchY() {
        return lastTouchY;
    }

    // ── 按下 / 松开 ────────────────────────────────────────

    static void press(Activity host, Callback result) {
        if (holding) {
            if (result != null) result.onResult(true);
            return;
        }
        activity = host;
        callback = result;
        if (!locate(host)) {
            HotBridge.log("[DEKK][通话] 找不到 Compose 根 View，无法模拟长按");
            finish(false);
            return;
        }
        queue = candidates(host);
        cursor = 0;
        HotBridge.log("[DEKK][通话] 开始定位语音键，候选点 " + queue.size() + " 个");
        tryNext();
    }

    static void release() {
        if (!holding) return;
        holding = false;
        MAIN.removeCallbacks(confirm);
        MAIN.removeCallbacks(keepAlive);
        send(MotionEvent.ACTION_UP);
        HotBridge.log("[DEKK][通话] 已松开语音键");
    }

    /** 挂断时用：连扫描一起停掉，别让回调再弹提示。 */
    static void cancel() {
        MAIN.removeCallbacks(confirm);
        MAIN.removeCallbacks(keepAlive);
        queue = null;
        callback = null;
        if (holding) {
            holding = false;
            send(MotionEvent.ACTION_CANCEL);
        }
    }

    private static void finish(boolean ok) {
        Callback cb = callback;
        callback = null;
        queue = null;
        if (cb != null) cb.onResult(ok);
    }

    private static void tryNext() {
        if (queue == null) return;
        if (cursor >= queue.size()) {
            HotBridge.log("[DEKK][通话] 所有候选点都没录上，语音键定位失败");
            finish(false);
            return;
        }
        float[] point = queue.get(cursor++);
        pointX = point[0];
        pointY = point[1];
        localX = pointX - originX;
        localY = pointY - originY;
        attemptMark = recordingAt;
        downTime = SystemClock.uptimeMillis();
        boolean accepted = send(MotionEvent.ACTION_DOWN);
        if (!accepted) {
            send(MotionEvent.ACTION_CANCEL);
            MAIN.postDelayed(new Runnable() {
                public void run() {
                    tryNext();
                }
            }, 120);
            return;
        }
        holding = true;
        HotBridge.log("[DEKK][通话] 试点 @" + (int) pointX + "," + (int) pointY);
        MAIN.postDelayed(confirm, CONFIRM_MS);
    }

    private static final Runnable confirm = new Runnable() {
        public void run() {
            if (!holding) return;
            if (recordingAt > attemptMark) {
                // 宿主真的开始录音了 —— 这个点就是语音键
                if (activity != null) VoiceCallStore.setKeyPoint(activity, pointX, pointY);
                HotBridge.log("[DEKK][通话] 语音键命中 @" + (int) pointX + "," + (int) pointY);
                MAIN.postDelayed(keepAlive, 200);
                finish(true);
                return;
            }
            // 没录上：松开换下一个点
            holding = false;
            send(MotionEvent.ACTION_UP);
            MAIN.postDelayed(new Runnable() {
                public void run() {
                    tryNext();
                }
            }, 260);
        }
    };

    private static final Runnable keepAlive = new Runnable() {
        public void run() {
            if (!holding) return;
            send(MotionEvent.ACTION_MOVE);
            MAIN.postDelayed(this, 200);
        }
    };

    // ── 候选点 ─────────────────────────────────────────────

    private static ArrayList<float[]> candidates(Activity host) {
        ArrayList<float[]> list = new ArrayList<float[]>();
        if (VoiceCallStore.hasKeyPoint(host)) {
            list.add(new float[]{VoiceCallStore.keyX(host), VoiceCallStore.keyY(host)});
        }
        int width = target.getWidth();
        int height = target.getHeight();
        int dx = dp(host, 30);
        int dy = dp(host, 22);
        // 第一列：贴右边那一竖排（最常见的位置）
        int firstX = width - dp(host, 46);
        for (int i = 0; i < 6; i++) {
            list.add(new float[]{firstX, height - dp(host, 96) - dy * i});
        }
        // 再往左两列，防止图标位置更靠内
        for (int column = 1; column <= 2; column++) {
            int x = firstX - dx * column;
            for (int i = 0; i < 4; i++) {
                list.add(new float[]{x, height - dp(host, 110) - dy * 1.4f * i});
            }
        }
        return list;
    }

    // ── 坐标 ───────────────────────────────────────────────

    private static boolean locate(Activity host) {
        if (host == null) return false;
        target = find(host.getWindow().getDecorView());
        if (target == null) return false;
        int[] loc = new int[2];
        target.getLocationOnScreen(loc);
        originX = loc[0];
        originY = loc[1];
        return target.getWidth() > 0 && target.getHeight() > 0;
    }

    private static View find(View view) {
        if (view == null) return null;
        if (COMPOSE_VIEW.equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = find(group.getChildAt(i));
                if (child != null) return child;
            }
        }
        return null;
    }

    private static boolean send(int action) {
        if (target == null) return false;
        try {
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    action, localX, localY, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            boolean handled = target.dispatchTouchEvent(event);
            event.recycle();
            return handled;
        } catch (Throwable t) {
            HotBridge.log("[DEKK][通话] 触摸投递失败: " + t);
            return false;
        }
    }

    private static int dp(android.content.Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }
}
