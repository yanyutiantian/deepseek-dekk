package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioRecord;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/**
 * 语音通话的全部挂钩。
 *
 * <ol>
 *   <li><b>相机入口</b>：点「拍照」时宿主会走权限申请 / ACTION_IMAGE_CAPTURE，
 *       这里把它换成「发起语音通话」；</li>
 *   <li><b>语音键标定</b>：记录宿主上每一次真实按下，等 {@code AudioRecord.startRecording()}
 *       被调用时把那一对坐标存成语音键位置（用户正常用一次按住说话就自动学会）；</li>
 *   <li><b>朗读链路</b>：见 {@link VoiceCallTts}；</li>
 *   <li><b>状态机</b>：见 {@link VoiceCallManager}。</li>
 * </ol>
 */
final class VoiceCallHook {

    private static final String COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private VoiceCallHook() { }

    static void install(ClassLoader loader) {
        // 钩子常驻，开关在运行时判断 —— 这样用户打开开关不用重启宿主。
        installComposeTouch(loader);
        installRecorder();
        installCamera(loader);
        installMessageText(loader);
        VoiceCallTts.install(loader);
        VoiceCallManager.install(loader);
        HotBridge.log("[DEKK][通话] 已安装（相机入口 + 语音键标定 + 朗读）");
    }

    // ── 1. 触摸标定 ─────────────────────────────────────────

    private static void installComposeTouch(ClassLoader loader) {
        try {
            Class<?> compose = HotHelpers.findClass(COMPOSE_VIEW, loader);
            HotBridge.hookMethod(compose.getMethod("dispatchTouchEvent", new Class<?>[]{MotionEvent.class}),
                    new HotHook() {
                        protected void beforeHookedMethod(HotParam param) {
                            if (param.thisObject == null) return;
                            if (!COMPOSE_VIEW.equals(param.thisObject.getClass().getName())) return;
                            if (param.args == null || param.args.length == 0) return;
                            MotionEvent event = (MotionEvent) param.args[0];
                            if (event == null || event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                            VoiceCallKey.noteTouch(event.getRawX(), event.getRawY());
                        }
                    });
        } catch (Throwable t) {
            HotBridge.log("[DEKK][通话] 触摸标定安装失败: " + t);
        }
    }

    // ── 2. 录音开始 = 语音键确认 ────────────────────────────

    private static void installRecorder() {
        try {
            HotBridge.hookMethod(AudioRecord.class.getMethod("startRecording"), new HotHook() {
                protected void beforeHookedMethod(HotParam param) {
                    VoiceCallKey.markRecording();
                    Context context = HotApp.currentApplication();
                    if (context == null) return;
                    // 宿主开始录音的这一刻，上一次 ACTION_DOWN 的位置就是语音键。
                    if (!VoiceCallStore.enabled(context)) return;
                    if (!VoiceCallKey.recentTouch(2500L)) return;
                    float x = VoiceCallKey.lastTouchX();
                    float y = VoiceCallKey.lastTouchY();
                    if (x <= 0f || y <= 0f) return;
                    VoiceCallStore.setKeyPoint(context, x, y);
                }
            });
        } catch (Throwable t) {
            HotBridge.log("[DEKK][通话] 录音打点安装失败: " + t);
        }
    }

    // ── 3. 相机 → 通话 ──────────────────────────────────────

    private static void installCamera(ClassLoader loader) {
        try {
            Class<?> instrumentation = Class.forName("android.app.Instrumentation");
            HotBridge.hookMethod(instrumentation.getMethod("execStartActivity",
                    new Class<?>[]{Context.class, android.os.IBinder.class, android.os.IBinder.class,
                            Activity.class, Intent.class, int.class, android.os.Bundle.class}),
                    new HotHook() {
                        protected void beforeHookedMethod(HotParam param) {
                            if (param.args == null || param.args.length < 5) return;
                            Intent intent = (Intent) param.args[4];
                            if (!isCamera(intent)) return;
                            Context context = HotApp.currentApplication();
                            if (context == null || !VoiceCallStore.enabled(context)) return;
                            param.setResult(Integer.valueOf(0));
                            launchCall();
                        }
                    });
        } catch (Throwable t) {
            HotBridge.log("[DEKK][通话] 相机 Intent 拦截失败: " + t);
        }

        try {
            HotBridge.hookMethod(Activity.class.getMethod("requestPermissions",
                    new Class<?>[]{String[].class, int.class}), new HotHook() {
                protected void beforeHookedMethod(HotParam param) {
                    if (param.args == null || param.args.length < 1) return;
                    String[] permissions = (String[]) param.args[0];
                    if (permissions == null) return;
                    boolean camera = false;
                    for (int i = 0; i < permissions.length; i++) {
                        if (android.Manifest.permission.CAMERA.equals(permissions[i])) camera = true;
                    }
                    if (!camera) return;
                    Context context = HotApp.currentApplication();
                    if (context == null || !VoiceCallStore.enabled(context)) return;
                    param.setResult(null);
                    launchCall();
                }
            });
        } catch (Throwable t) {
            HotBridge.log("[DEKK][通话] 相机权限拦截失败: " + t);
        }
    }

    private static boolean isCamera(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if ("android.media.action.IMAGE_CAPTURE".equals(action)
                || "android.media.action.IMAGE_CAPTURE_SECURE".equals(action)) {
            return true;
        }
        String type = intent.getType();
        if (type != null && type.startsWith("image/")
                && "android.intent.action.GET_CONTENT".equals(action)) {
            // 相册不算，只处理拍照
            return false;
        }
        return false;
    }

    private static void launchCall() {
        MAIN.post(new Runnable() {
            public void run() {
                Activity activity = DekkSettingsPage.currentActivity();
                if (activity != null) VoiceCallManager.requestFromUi(activity);
            }
        });
    }

    // ── 4. AI 正文里的通话标记 ──────────────────────────────

    /**
     * {@code nx.C()} 是 AI 正文的读取口。这里做两件事：
     * 把原文记进 {@link VoiceCallText}（状态机要用标记），再把标记从显示文本里去掉。
     */
    private static void installMessageText(ClassLoader loader) {
        try {
            Class<?> message = HotHelpers.findClass("nx", loader);
            final java.lang.reflect.Method body = message.getMethod("C");
            HotBridge.hookMethod(body, new HotHook() {
                protected void afterHookedMethod(HotParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof String)) return;
                    String raw = (String) result;
                    if (raw.length() == 0) return;
                    VoiceCallText.remember(param.thisObject, raw);
                    if (raw.indexOf("[ctx:call]") < 0 && raw.indexOf("[call]") < 0) return;
                    String visible = VoiceCallText.strip(raw);
                    if (visible != null && visible.length() != raw.length()) param.setResult(visible);
                }
            });
        } catch (Throwable t) {
            HotBridge.log("[DEKK][通话] AI 正文清洗安装失败: " + t);
        }
    }

    // ── 5. 通话页的 View 兜底 ──────────────────────────────

    /** 通话页挂在 decorView 上时，宿主可能重设内容；这里只做诊断用。 */
    static boolean attached(Activity activity) {
        VoiceCallUi ui = VoiceCallUi.current();
        if (ui == null || activity == null) return false;
        View parent = (View) ui.getParent();
        return parent != null;
    }
}
