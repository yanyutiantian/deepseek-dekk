package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AlphaAnimation;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 全屏通话页（微信语音那种布局）。
 *
 * <p>直接叠在宿主窗口的 decorView 上，所以只要宿主 Activity 还在，通话页就一直盖着。
 * 布局从上到下：名字 / 计时 / 大头像 / 状态文字 / （来电时）接听+挂断 / （通话中）静音+挂断。
 */
final class VoiceCallUi extends FrameLayout {

    interface Listener {
        void onAccept();
        void onHangup();
        void onMic(boolean on);
    }

    private static VoiceCallUi current;

    /** 0 = 呼出等待，1 = 来电邀请，2 = 通话中。 */
    static final int OUTGOING = 0, INCOMING = 1, ACTIVE = 2;

    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView title, status, timer;
    private final CircleButton hangup, accept, mic;
    private final ImageView avatar;
    private final View ring;
    private LinearLayout hangupItem, acceptItem, micItem;
    private TextView hangupLabel;
    private int mode;
    private long startedAt;
    private boolean micOn;
    private boolean speaking;

    static VoiceCallUi current() {
        return current;
    }

    static void dismiss() {
        final VoiceCallUi ui = current;
        current = null;
        if (ui == null) return;
        try {
            ui.stopTicker();
            ViewGroup parent = (ViewGroup) ui.getParent();
            if (parent != null) parent.removeView(ui);
        } catch (Throwable ignored) { }
    }

    /** 弹出通话页；activity 为空时不显示。 */
    static VoiceCallUi show(Activity activity, int mode, String name, Listener listener) {
        if (activity == null || activity.isFinishing()) return null;
        dismiss();
        VoiceCallUi ui = new VoiceCallUi(activity, mode, name, listener);
        current = ui;
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        root.addView(ui, new ViewGroup.LayoutParams(-1, -1));
        return ui;
    }

    private VoiceCallUi(Activity activity, int mode, String name, final Listener listener) {
        super(activity);
        this.activity = activity;
        this.listener = listener;
        this.mode = mode;
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF2A2C2F, 0xFF141517}));
        setClickable(true);
        setFocusableInTouchMode(true);
        requestFocus();

        int top = statusBar(activity);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(24), top + dp(48), dp(24), dp(56));
        addView(column, new FrameLayout.LayoutParams(-1, -1));

        title = new TextView(activity);
        title.setText(name == null || name.length() == 0 ? "DeepSeek" : name);
        title.setTextColor(0xFFF5F5F5);
        title.setTextSize(22);
        title.setTypeface(MiuixUi.medium());
        title.setGravity(Gravity.CENTER);
        column.addView(title, new LinearLayout.LayoutParams(-1, -2));

        timer = new TextView(activity);
        timer.setTextColor(0x99FFFFFF);
        timer.setTextSize(14);
        timer.setGravity(Gravity.CENTER);
        timer.setPadding(0, dp(6), 0, 0);
        column.addView(timer, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout avatarArea = new FrameLayout(activity);
        LinearLayout.LayoutParams areaParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        areaParams.topMargin = dp(8);
        column.addView(avatarArea, areaParams);

        ring = new View(activity);
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setColor(0x00000000);
        halo.setStroke(dp(activity, 1.2f), 0x33FFFFFF);
        ring.setBackground(halo);
        FrameLayout.LayoutParams ringParams = new FrameLayout.LayoutParams(dp(190), dp(190));
        ringParams.gravity = Gravity.CENTER;
        avatarArea.addView(ring, ringParams);

        avatar = new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable picture = picture(activity);
        if (picture != null) avatar.setImageDrawable(picture);
        FrameLayout.LayoutParams avatarParams = new FrameLayout.LayoutParams(dp(124), dp(124));
        avatarParams.gravity = Gravity.CENTER;
        avatarArea.addView(avatar, avatarParams);

        status = new TextView(activity);
        status.setTextColor(0xB3FFFFFF);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        column.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(40), 0, 0);
        column.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        hangup = new CircleButton(activity, 0xFFFF3B30, CircleButton.HANGUP);
        hangup.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (listener != null) listener.onHangup();
            }
        });

        accept = new CircleButton(activity, 0xFF34C759, CircleButton.ACCEPT);
        accept.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (listener != null) listener.onAccept();
            }
        });

        mic = new CircleButton(activity, 0x33FFFFFF, CircleButton.MIC);
        mic.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                setMic(!micOn);
                if (listener != null) listener.onMic(micOn);
            }
        });

        hangupItem = addButton(buttons, hangup, "挂断");
        acceptItem = addButton(buttons, accept, "接听");
        micItem = addButton(buttons, mic, "麦克风");

        applyMode();
        startTicker();
    }

    /** 一个「圆形按钮 + 文字」的组合，整体显示/隐藏（早期版本只藏了圆按钮，文字会留在屏幕上）。 */
    private LinearLayout addButton(LinearLayout parent, View view, String label) {
        LinearLayout item = new LinearLayout(getContext());
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.addView(view, new LinearLayout.LayoutParams(dp(68), dp(68)));
        TextView text = new TextView(getContext());
        text.setText(label);
        text.setTextSize(12);
        text.setTextColor(0x99FFFFFF);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(10), 0, 0);
        item.addView(text, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        parent.addView(item, params);
        if (view == hangup) hangupLabel = text;
        return item;
    }

    void setMode(int next) {
        mode = next;
        applyMode();
    }

    private void applyMode() {
        boolean incoming = mode == INCOMING;
        // 来电：挂断 + 接听；呼出：只有取消；通话中：挂断 + 麦克风
        hangupItem.setVisibility(VISIBLE);
        acceptItem.setVisibility(incoming ? VISIBLE : GONE);
        micItem.setVisibility(mode == ACTIVE ? VISIBLE : GONE);
        hangupLabel.setText(mode == OUTGOING ? "取消" : "挂断");
        if (mode == OUTGOING) {
            status.setText("正在呼叫…");
            timer.setText("");
            ring.setVisibility(GONE);
        } else if (mode == ACTIVE) {
            startedAt = SystemClock.elapsedRealtime();
            status.setText("已接通");
            ring.setVisibility(VISIBLE);
            breathe();
        } else {
            status.setText("邀请你语音通话");
            timer.setText("");
            ring.setVisibility(GONE);
        }
    }

    /** 麦克风格。 */
    void setMic(boolean on) {
        micOn = on;
        mic.setColor(on ? 0xFF3482FF : 0x33FFFFFF);
        mic.setActive(on);
        mic.invalidate();
        if (mode == ACTIVE) status.setText(on ? "麦克风已开，说话就行" : "麦克风已关");
    }

    boolean micOn() {
        return micOn;
    }

    /** AI 正在说话/正在听，用于状态文字。 */
    void setSpeaking(boolean value) {
        speaking = value;
        if (mode == ACTIVE) status.setText(value ? "对方正在说话…" : (micOn ? "正在聆听" : "已接通"));
    }

    void setStatus(String text) {
        if (text != null) status.setText(text);
    }

    void fail(String reason) {
        status.setText(reason);
    }

    private void breathe() {
        ScaleAnimation scale = new ScaleAnimation(0.92f, 1.08f, 0.92f, 1.08f,
                Animation.RELATIVE_TO_SELF, .5f, Animation.RELATIVE_TO_SELF, .5f);
        scale.setDuration(1600);
        scale.setRepeatCount(Animation.INFINITE);
        scale.setRepeatMode(Animation.REVERSE);
        ring.startAnimation(scale);
    }

    private Runnable ticker;

    private void startTicker() {
        ticker = new Runnable() {
            public void run() {
                if (mode == ACTIVE && startedAt > 0) {
                    long seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000L;
                    timer.setText(String.format("%02d:%02d", Long.valueOf(seconds / 60), Long.valueOf(seconds % 60)));
                }
                main.postDelayed(this, 1000);
            }
        };
        main.postDelayed(ticker, 1000);
    }

    private void stopTicker() {
        if (ticker != null) {
            main.removeCallbacks(ticker);
            ticker = null;
        }
        ring.clearAnimation();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 通话页盖住整个窗口，返回键不应该把宿主页面关掉。
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private Drawable picture(Context context) {
        try {
            Bitmap bitmap = ChatAvatarStore.bitmap(context, false);
            if (bitmap != null) {
                return new BitmapDrawable(context.getResources(), bitmap);
            }
        } catch (Throwable ignored) { }
        try {
            Drawable icon = context.getPackageManager().getApplicationIcon(context.getPackageName());
            if (icon != null) return icon;
        } catch (Throwable ignored) { }
        return new ColorDrawable(0xFF4A4A4A);
    }

    private static int statusBar(Context context) {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? context.getResources().getDimensionPixelSize(id) : dp(context, 24);
    }

    static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    /** 实例上下文里的简写（默认用本 View 的 Context）。 */
    private int dp(float value) {
        return dp(getContext(), value);
    }

    /** 圆形按钮，图标全部手绘（不依赖 Material 图标库）。 */
    static final class CircleButton extends View {
        static final int HANGUP = 0, ACCEPT = 1, MIC = 2;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;
        private final int kind;
        private boolean active;

        CircleButton(Context context, int color, int kind) {
            super(context);
            this.color = color;
            this.kind = kind;
            iconPaint.setColor(0xFFFFFFFF);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(dp(context, 2.6f));
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setColor(int value) {
            color = value;
        }

        void setActive(boolean value) {
            active = value;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = Math.min(cx, cy) - dp(getContext(), 2);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(cx, cy, radius, paint);
            canvas.save();
            canvas.translate(cx, cy);
            float unit = radius * 0.42f;
            if (kind == HANGUP) {
                canvas.rotate(135f);
                Path path = new Path();
                path.addRoundRect(new RectF(-unit, -unit * .42f, unit, unit * .42f),
                        unit * .42f, unit * .42f, Path.Direction.CW);
                iconPaint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, iconPaint);
            } else if (kind == ACCEPT) {
                Path path = new Path();
                path.moveTo(-unit, unit * .1f);
                path.quadTo(-unit * .55f, -unit * .5f, 0, -unit * .5f);
                path.quadTo(unit * .55f, -unit * .5f, unit, unit * .1f);
                iconPaint.setStyle(Paint.Style.STROKE);
                canvas.drawPath(path, iconPaint);
            } else {
                RectF head = new RectF(-unit * .34f, -unit, unit * .34f, unit * .1f);
                iconPaint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(head, unit * .34f, unit * .34f, iconPaint);
                iconPaint.setStyle(Paint.Style.STROKE);
                RectF arc = new RectF(-unit * .62f, -unit * .5f, unit * .62f, unit * .62f);
                canvas.drawArc(arc, 0f, 180f, false, iconPaint);
                canvas.drawLine(0, unit * .62f, 0, unit * .95f, iconPaint);
                if (active) canvas.drawLine(-unit * .42f, unit * .95f, unit * .42f, unit * .95f, iconPaint);
            }
            canvas.restore();
        }
    }
}
