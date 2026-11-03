package com.dekk.yanyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

/** 把模板、消息、头像、壁纸组装成最终 HTML。 */
final class HtmlUiRenderer {

    /** 头像解码上限，避免模板里塞一个巨大的 base64。 */
    private static final int AVATAR_MAX = 256;
    /** 壁纸解码上限：够 1080p 铺满即可。 */
    private static final int WALLPAPER_MAX = 1080;

    private HtmlUiRenderer() { }

    static String build(Context context, List<HtmlUiMessages.Msg> messages) {
        String template = HtmlUiStore.template(context);
        String json = HtmlUiMessages.toJson(messages);
        String avatar = HtmlUiStore.useAvatar(context) ? dataUri(avatarFile(context, false), AVATAR_MAX) : "";
        String userAvatar = HtmlUiStore.useAvatar(context) ? dataUri(avatarFile(context, true), AVATAR_MAX) : "";
        String wallpaper = HtmlUiStore.useWallpaper(context) ? dataUri(wallpaperFile(context), WALLPAPER_MAX) : "";
        String wallpaperCss = wallpaper.length() == 0 ? "none" : "url(\"" + wallpaper + "\")";

        String html = template
                .replace("{{wallpaper_css}}", wallpaperCss)
                .replace("{{wallpaper}}", wallpaper)
                .replace("{{user_avatar}}", userAvatar)
                .replace("{{messages}}", json)
                .replace("{{avatar}}", avatar);
        // 把留白尺寸交给页面自己避开标题栏 / 输入区（全屏铺满时尤其重要）
        String vars = "<style>:root{--dekk-top-inset:" + HtmlUiStore.topInset(context) + "px;"
                + "--dekk-composer-inset:" + HtmlUiStore.bottomInset(context) + "px;}</style>";
        int headAt = html.indexOf("</head>");
        if (headAt >= 0) {
            html = html.substring(0, headAt) + vars + html.substring(headAt);
        } else {
            html = vars + html;
        }
        // 老模板（没适配全屏的）自动补一段避让样式：
        // 强制 body 透明 + 用 CSS 变量让出标题栏与输入区，并把底色只画在中间那一段。
        // 适配过的新模板（自己用了 --dekk-composer-inset）不会被动。
        if (HtmlUiStore.fullscreen(context) && template.indexOf("--dekk-composer-inset") < 0) {
            String adapt = "<style id='dekk-adapt'>"
                    + "html,body{background:transparent!important;overflow:hidden!important;}"
                    + "body{padding:var(--dekk-top-inset,80px) 0 var(--dekk-composer-inset,132px)"
                    + "!important;box-sizing:border-box!important;}"
                    + "body::before{content:'';position:fixed;left:0;right:0;"
                    + "top:var(--dekk-top-inset,80px);bottom:var(--dekk-composer-inset,132px);"
                    + "z-index:-1;background:#F2F2F7 " + wallpaperCss + " center/cover no-repeat;}"
                    + "</style>";
            int adaptAt = html.indexOf("</head>");
            if (adaptAt >= 0) {
                html = html.substring(0, adaptAt) + adapt + html.substring(adaptAt);
            } else {
                html = adapt + html;
            }
        }
        // 无论模板有没有用占位符，都把消息喂给 window.render(messages)：
        // 立即渲染一次；模板还没定义 render 就每 50ms 重试（最多 10 秒）；
        // load 之后再补渲染一次，照顾把 render 写在 DOMContentLoaded 里的模板。
        String bootstrap = "<script>window.__DEKK_MESSAGES__=" + json
                + ";(function(){var n=0;function go(){try{if(typeof window.render==='function'){"
                + "window.render(window.__DEKK_MESSAGES__||[]);return true;}}catch(e){}return false;}"
                + "if(!go()){var t=setInterval(function(){if(go()||++n>200){clearInterval(t);}},50);}"
                + "window.addEventListener('load',function(){go();});})();</script>";
        int at = html.lastIndexOf("</body>");
        if (at >= 0) {
            return html.substring(0, at) + bootstrap + html.substring(at);
        }
        return html + bootstrap;
    }

    static String sample(Context context) {
        return build(context, HtmlUiMessages.sample());
    }

    private static File avatarFile(Context context, boolean user) {
        try {
            File file = ChatAvatarStore.file(context, user);
            return file != null && file.exists() ? file : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File wallpaperFile(Context context) {
        try {
            String path = WallpaperFile.ensure(context, BeautifyStore.get(context));
            if (path == null || path.length() == 0) {
                return null;
            }
            File file = new File(path);
            return file.exists() ? file : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 图片 → data URI；先按上限降采样再压 JPEG，避免把几十 MB 塞进 HTML。 */
    private static String dataUri(File file, int maxWidth) {
        if (file == null || !file.exists()) {
            return "";
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return "";
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxWidth) {
                sample *= 2;
            }
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) {
                return "";
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out);
            bitmap.recycle();
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return "";
        }
    }
}