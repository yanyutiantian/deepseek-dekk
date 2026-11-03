package com.dekk.yanyu;

import android.content.Context;

/** 自定义 HTML 聊天 UI 的配置：开关、接管、模板、正则、头像与壁纸开关。 */
final class HtmlUiStore {
    private static final String PREF = "dekk_html_ui";

    static final String DEFAULT_USER_REGEX = "\\[DEEK-USER\\]([\\s\\S]*?)\\[/DEEK-USER\\]";
    static final String DEFAULT_AI_REGEX = "\\[DEEK-AI\\]([\\s\\S]*?)\\[/DEEK-AI\\]";

    private HtmlUiStore() { }

    /**
     * 功能总开关：自定义 HTML 聊天 UI。
     * 打开后 DekkEntry 会安装渲染 hook，用户可在「聊天美化 → 自定义 UI」里开启测试。
     * 关闭它 = 完全下线（不装 hook、不渲染任何覆盖层）。
     */
    static final boolean FEATURE_ON = true;

    static boolean enabled(Context c) {
        return FEATURE_ON && c != null && c.getSharedPreferences(PREF, 0).getBoolean("enabled", false);
    }
    static void setEnabled(Context c, boolean value) {
        c.getSharedPreferences(PREF, 0).edit().putBoolean("enabled", value).apply();
        HtmlUiHook.onConfigChanged(c);
    }

    /** 在聊天页接管（把宿主气泡盖住换成我们的 WebView）。默认关闭，需要能跑再开。 */
    static boolean takeover(Context c) {
        return c != null && c.getSharedPreferences(PREF, 0).getBoolean("takeover", false);
    }
    static void setTakeover(Context c, boolean value) {
        c.getSharedPreferences(PREF, 0).edit().putBoolean("takeover", value).apply();
        HtmlUiHook.onConfigChanged(c);
    }

    static String template(Context c) {
        String stored = c == null ? "" : c.getSharedPreferences(PREF, 0).getString("template", "");
        return stored.length() == 0 ? HtmlUiTemplate.DEFAULT : stored;
    }
    static void setTemplate(Context c, String value) {
        c.getSharedPreferences(PREF, 0).edit().putString("template", value == null ? "" : value).apply();
        HtmlUiHook.onConfigChanged(c);
    }
    static void resetTemplate(Context c) {
        c.getSharedPreferences(PREF, 0).edit().remove("template").apply();
        HtmlUiHook.onConfigChanged(c);
    }
    static boolean customTemplate(Context c) {
        return c != null && c.getSharedPreferences(PREF, 0).getString("template", "").length() > 0;
    }

    static String userRegex(Context c) {
        String value = c == null ? "" : c.getSharedPreferences(PREF, 0).getString("user_regex", "");
        return value.length() == 0 ? DEFAULT_USER_REGEX : value;
    }
    static String aiRegex(Context c) {
        String value = c == null ? "" : c.getSharedPreferences(PREF, 0).getString("ai_regex", "");
        return value.length() == 0 ? DEFAULT_AI_REGEX : value;
    }
    static void setRegex(Context c, String user, String ai) {
        c.getSharedPreferences(PREF, 0).edit()
                .putString("user_regex", user == null ? "" : user)
                .putString("ai_regex", ai == null ? "" : ai).apply();
        HtmlUiHook.onConfigChanged(c);
    }

    /**
     * 聊天页判定的关键词（用 | 分隔）：宿主的聊天输入框提示语里包含其中之一才算聊天页。
     * 不同版本提示语可能不同，做成可改的，避免判错时渲染层跑到别的页面上。
     */
    static String composerHints(Context c) {
        String value = c == null ? "" : c.getSharedPreferences(PREF, 0).getString("composer_hints", "");
        return value.length() == 0 ? "发送|消息|DeepSeek" : value;
    }
    static void setComposerHints(Context c, String value) {
        c.getSharedPreferences(PREF, 0).edit().putString("composer_hints", value == null ? "" : value).apply();
        HtmlUiHook.onConfigChanged(c);
    }

    /** 渲染层顶部留白（dp）：状态栏 + 宿主标题栏。 */
    static int topInset(Context c) {
        return c == null ? 80 : c.getSharedPreferences(PREF, 0).getInt("top_inset", 80);
    }
    /** 渲染层底部留白（dp）：宿主输入区整体高度，太小会把输入框盖住。 */
    static int bottomInset(Context c) {
        return c == null ? 132 : c.getSharedPreferences(PREF, 0).getInt("bottom_inset", 132);
    }
    static void setInsets(Context c, int top, int bottom) {
        c.getSharedPreferences(PREF, 0).edit()
                .putInt("top_inset", top < 0 ? 0 : top)
                .putInt("bottom_inset", bottom < 0 ? 0 : bottom).apply();
        HtmlUiHook.onConfigChanged(c);
    }

    /**
     * 铺满全屏（默认开）：渲染层铺满整屏，由页面自己用
     * {@code --dekk-top-inset} / {@code --dekk-composer-inset} 两个变量避开标题栏与输入区；
     * 底部输入区那一条在 Java 侧做「触摸透传」，所以输入框既看得见也点得到。
     * 关掉就是老行为：渲染层直接避开顶部/底部留白，不覆盖输入区。
     */
    static boolean fullscreen(Context c) {
        return c == null || c.getSharedPreferences(PREF, 0).getBoolean("fullscreen", true);
    }
    static void setFullscreen(Context c, boolean value) {
        c.getSharedPreferences(PREF, 0).edit().putBoolean("fullscreen", value).apply();
        HtmlUiHook.onConfigChanged(c);
    }

    static boolean useAvatar(Context c) {
        return c == null || c.getSharedPreferences(PREF, 0).getBoolean("use_avatar", true);
    }
    static void setUseAvatar(Context c, boolean value) {
        c.getSharedPreferences(PREF, 0).edit().putBoolean("use_avatar", value).apply();
        HtmlUiHook.onConfigChanged(c);
    }
    static boolean useWallpaper(Context c) {
        return c == null || c.getSharedPreferences(PREF, 0).getBoolean("use_wallpaper", true);
    }
    static void setUseWallpaper(Context c, boolean value) {
        c.getSharedPreferences(PREF, 0).edit().putBoolean("use_wallpaper", value).apply();
        HtmlUiHook.onConfigChanged(c);
    }
}