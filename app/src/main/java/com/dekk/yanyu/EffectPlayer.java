package com.dekk.yanyu;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * 评论特效播放（照着参考页面的做法：**全屏透明浮层**，动画浮在界面上，没有黑边）。
 *
 * <p>服务端发来的特效包含三份文件（都不落到用户本地）：
 * <ul>
 *   <li>{@code anim_url}：带透明通道的动图（webp），在内存里解码成 {@link AnimatedImageDrawable}
 *       叠在界面上 —— 所以透明处能看见底下的聊天界面，不会出现黑边；</li>
 *   <li>{@code audio_url}：音效，边下边播；</li>
 *   <li>{@code video_url}：整段视频，只有老系统 / 动图解码失败时才用（铺满屏幕裁剪显示）。</li>
 * </ul>
 *
 * <p>这一层本身**不吃触摸**（和参考页面的 pointer-events:none 一样），放完自动消失，
 * 也可以按返回键把它关掉。
 */
final class EffectPlayer extends FrameLayout {

    /** 和 CenterPage / CommentSheet 一样固定 id，方便返回键找到它。 */
    private static final int ID = 0x00DE0005;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Activity activity;
    private final JSONObject effect;

    private ImageView image;
    private Runnable timer;
    private boolean started;
    private MediaPlayer audio;
    private MediaPlayer videoPlayer;
    private TextureView videoTexture;
    private Drawable animation;
    private boolean closed;

    private EffectPlayer(Activity activity, JSONObject effect) {
        super(activity);
        this.activity = activity;
        this.effect = effect;
    }

    /** 播放一段特效（effect 来自服务端）。 */
    static void play(Activity activity, JSONObject effect) {
        if (activity == null || effect == null) return;
        closeExisting(activity);
        EffectPlayer overlay = new EffectPlayer(activity, effect);
        overlay.setId(ID);
        overlay.attach();
    }

    private static void closeExisting(Activity activity) {
        View old = activity.findViewById(ID);
        if (old instanceof EffectPlayer) ((EffectPlayer) old).close();
    }

    /** 返回键：特效放着就先关特效。 */
    static boolean handleBack(Activity activity) {
        View view = activity.findViewById(ID);
        if (view instanceof EffectPlayer) {
            ((EffectPlayer) view).close();
            return true;
        }
        return false;
    }

    private void attach() {
        // 完全透明的一层，铺满屏幕；不设 OnClickListener，触摸会穿透到下面的界面
        setBackgroundColor(0x00000000);
        setClickable(false);
        setFocusableInTouchMode(true);

        FrameLayout stage = new FrameLayout(activity);
        stage.setClickable(false);
        addView(stage, new FrameLayout.LayoutParams(-1, -1));

        // 铺满屏幕能放的下的最大尺寸（正方形素材就是「满宽」），居中显示
        int side = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(side, side);
        imageParams.gravity = Gravity.CENTER;
        stage.addView(image, imageParams);

        videoTexture = new TextureView(activity);
        videoTexture.setVisibility(View.GONE);
        stage.addView(videoTexture, new FrameLayout.LayoutParams(-1, -1));

        ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
        if (root == null) return;
        root.addView(this, new ViewGroup.LayoutParams(-1, -1));
        requestFocus();

        startAnimation();
    }

    // ---- 透明动图（首选）----

    private void startAnimation() {
        final String animUrl = effect.optString("anim_url", "");
        if (animUrl.length() == 0 || Build.VERSION.SDK_INT < 28) {
            startVideo();
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                byte[] data = null;
                try {
                    data = fetch(animUrl);
                } catch (Throwable t) {
                    HotBridge.log("[DEKK][特效] 动图下载失败：" + t);
                }
                final byte[] bytes = data;
                MAIN.post(new Runnable() {
                    public void run() {
                        if (closed) return;
                        if (bytes == null || !decodeAnimation(bytes)) startVideo();
                    }
                });
            }
        }, "dekk-effect-anim").start();
    }

    /** 在内存里解码动图（不写任何文件），拿到透明动画就开始播。 */
    private boolean decodeAnimation(byte[] bytes) {
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(bytes));
            Drawable drawable = ImageDecoder.decodeDrawable(source, new ImageDecoder.OnHeaderDecodedListener() {
                public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source src) {
                    // 动图必须用软件位图，否则透明通道 / 动画都不对
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                }
            });
            animation = drawable;
            image.setImageDrawable(drawable);
            if (drawable instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) drawable).start();
            } else if (drawable instanceof Animatable) {
                ((Animatable) drawable).start();
            }
            HotBridge.log("[DEKK][特效] 透明动图已开始播放");
            begin();
            return true;
        } catch (Throwable t) {
            HotBridge.log("[DEKK][特效] 动图解码失败，改用视频：" + t);
            return false;
        }
    }

    /** 真正开始播的那一刻：起音效（对齐画面）并开始兜底计时。 */
    private void begin() {
        if (started || closed) return;
        started = true;
        startAudio();
        long duration = Math.max(1000, effect.optLong("duration_ms", 3000));
        timer = new Runnable() {
            public void run() { close(); }
        };
        MAIN.postDelayed(timer, duration + 600);
    }

    // ---- 兜底：整段视频铺满屏幕（老系统 / 动图解不出来时）----

    private void startVideo() {
        final String videoUrl = effect.optString("video_url", "");
        final String fallbackUrl = effect.optString("url", "");
        final String url = videoUrl.length() > 0 ? videoUrl : fallbackUrl;
        if (url.length() == 0 || closed || videoTexture == null) return;
        image.setVisibility(View.GONE);
        videoTexture.setVisibility(View.VISIBLE);
        videoTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                try {
                    MediaPlayer mp = new MediaPlayer();
                    mp.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
                    mp.setDataSource(url);
                    mp.setSurface(new android.view.Surface(surface));
                    // 铺满屏幕 + 裁剪，避免视频四周出现黑边
                    mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    // 有单独音轨时视频静音，避免两份声音重叠
                    if (effect.optString("audio_url", "").length() > 0) mp.setVolume(0f, 0f);
                    mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        public void onPrepared(MediaPlayer mp2) {
                            if (closed) return;
                            mp2.start();
                            begin();
                        }
                    });
                    mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        public void onCompletion(MediaPlayer mp2) { close(); }
                    });
                    mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        public boolean onError(MediaPlayer mp2, int what, int extra) {
                            close();
                            return true;
                        }
                    });
                    videoPlayer = mp;
                    mp.prepareAsync();
                } catch (Throwable t) {
                    HotBridge.log("[DEKK][特效] 视频播放失败：" + t);
                    close();
                }
            }

            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }

            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }

            public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        });
    }

    // ---- 音效（在线播放）----

    private void startAudio() {
        final String audioUrl = effect.optString("audio_url", "");
        if (audioUrl.length() == 0 || closed) {
            // 没有单独音轨：让视频自己出声（兜底路径）
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setDataSource(audioUrl);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp2) {
                    if (closed) return;
                    mp2.start();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp2) { close(); }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp2, int what, int extra) {
                    return true;
                }
            });
            audio = mp;
            mp.prepareAsync();
        } catch (Throwable t) {
            HotBridge.log("[DEKK][特效] 音效播放失败：" + t);
        }
    }

    /** 边下边读进内存（不落本地文件）。 */
    private static byte[] fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
TlsDebug.apply(conn);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("Accept-Encoding", "identity");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = conn.getInputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            in.close();
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private void close() {
        if (closed) return;
        closed = true;
        if (timer != null) MAIN.removeCallbacks(timer);
        if (animation instanceof Animatable) {
            try { ((Animatable) animation).stop(); } catch (Throwable ignored) { }
        }
        animation = null;
        release(audio);
        audio = null;
        release(videoPlayer);
        videoPlayer = null;
        if (image != null) image.setImageDrawable(null);
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
    }

    private static void release(MediaPlayer player) {
        if (player == null) return;
        try { player.stop(); } catch (Throwable ignored) { }
        try { player.release(); } catch (Throwable ignored) { }
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** 调试/排障用：把特效信息打一行日志。 */
    static void logEffect(JSONObject effect) {
        if (effect == null) return;
        HotBridge.log("[DEKK][特效] 下发 " + effect.optString("name")
                + " anim=" + effect.optString("anim_url", "-")
                + " audio=" + effect.optString("audio_url", "-"));
    }
}