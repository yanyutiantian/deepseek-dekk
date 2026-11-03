package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.List;

/**
 * 整页自定义：把聊天页整屏交给我们的 WebView（顶栏 / 消息区 / 输入框都由 HTML 画）。
 *
 * <p>为什么不用 Compose 注入（两条都实测走不通）：
 * <ul>
 * <li>hook {@code PageMainContent} 跳过原方法 → Composer 的组结构不平衡，
 * 跑一会儿必崩 {@code n82: Compose Runtime internal error}（栈 ey3.q / ey3.x）；</li>
 * <li>hook 消息行再注入 AndroidView → 位置被列表项框死，且 Compose 重建 holder 时
 * 同一个 View 二次 addView → {@code The specified child already has a parent} 闪退。</li>
 * </ul>
 *
 * <p>覆盖层是纯 View：<b>不拦截任何宿主方法</b>，所以不会崩、不会吞消息；
 * 整屏盖住后原生的顶栏/输入框都看不见了，因此我们给页面自动补一套
 * 顶栏 + 输入框（JS 注入，任何模板都适用），按钮通过 {@code DekkNative} 桥回调宿主。
 */
final class HtmlUiFull {

    private static final String TAG = "[DEKK][HTML UI] ";
    private static final long TICK_MS = 600;

    private static boolean started;
    /** 暂停接管：点 ☰ 时切回原生界面（离开聊天页后自动恢复接管）。 */
    private static boolean suspended;
    private static FrameLayout host;
    private static WebView web;
    private static String loadedJson = "";

    private HtmlUiFull() { }

    static void install() {
        if (started) {
            return;
        }
        // 用户反馈：自定义 HTML UI 暂不可用 → 强制关闭（入口也已在「聊天美化」里隐藏）。
        // 代码保留备查，恢复时把这里改回去即可。
        HotBridge.log(TAG + "自定义 HTML UI 已强制关闭（不接管聊天页）");
    }

    /** 真正的启用入口：确认要重新上线时把它接回 {@link #install()}。 */
    private static void installImpl() {
        if (started) {
            return;
        }
        started = true;
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            public void run() {
                try {
                    tick();
                } catch (Throwable t) {
                    HotBridge.log(TAG + "整页层异常: " + t);
                }
                new Handler(Looper.getMainLooper()).postDelayed(this, TICK_MS);
            }
        }, TICK_MS);
        HotBridge.log(TAG + "整页自定义层已启动（不拦截宿主方法）");
    }

    private static void tick() {
        Context context = HotApp.currentApplication();
        if (context == null || !HtmlUiStore.enabled(context) || !HtmlUiStore.takeover(context)) {
            hide();
            return;
        }
        Activity activity = DekkSettingsPage.currentActivity();
        // 模块自己的页面（设置页 / 预览页，都是注入在宿主里的 CenterPage）正盖在上面 →
        // 整页层必须让开，否则设置页会被整屏盖住、点都点不开。
        if (activity != null && HtmlUiHook.modulePageVisible(activity)) {
            hide();
            return;
        }
        if (!HtmlUiHook.isOnChatPage()) {
            hide();
            suspended = false;   // 离开聊天页 → 下次进来自动恢复接管
            return;
        }
        if (suspended) {
            hide();              // 用户手动切回了原生界面
            return;
        }
        if (activity == null || activity.isFinishing()) {
            hide();
            return;
        }
        show(activity, context);
    }

    private static void show(Activity activity, Context context) {
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            if (host == null) {
                host = new FrameLayout(context);
                host.setBackgroundColor(0xFFF2F2F7);
                WebView view = HtmlUiHook.createWebView(context);
                if (view == null) {
                    return;
                }
                web = view;
                web.setBackgroundColor(0xFFF2F2F7);
                // 输入框 / 顶栏按钮 → 宿主
                web.addJavascriptInterface(new Bridge(activity), "DekkNative");
                host.addView(web, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                loadedJson = "";
                HotBridge.log(TAG + "整页层已创建（顶栏/输入框由 HTML 画）");
            }
            if (host.getParent() != decor) {
                if (host.getParent() instanceof ViewGroup) {
                    ((ViewGroup) host.getParent()).removeView(host);
                }
                decor.addView(host, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                HotBridge.log(TAG + "整页层已铺满屏幕");
            }
            render(context);
        } catch (Throwable t) {
            HotBridge.log(TAG + "整页层显示失败: " + t);
        }
    }

    private static void hide() {
        try {
            if (host != null && host.getParent() instanceof ViewGroup) {
                ((ViewGroup) host.getParent()).removeView(host);
            }
        } catch (Throwable ignored) { }
    }

    private static void render(Context context) {
        if (web == null) {
            return;
        }
        List<HtmlUiMessages.Msg> messages = HtmlUiHook.messages();
        String json = HtmlUiMessages.toJson(messages);
        if (json.equals(loadedJson)) {
            return;
        }
        boolean first = loadedJson.length() == 0;
        loadedJson = json;
        try {
            if (first) {
                String html = HtmlUiRenderer.build(context, messages);
                java.io.File file = new java.io.File(context.getCacheDir(), "dekk_html_ui.html");
                java.io.FileOutputStream out = new java.io.FileOutputStream(file);
                try {
                    out.write(html == null ? new byte[0] : html.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
                web.loadUrl("file://" + file.getAbsolutePath());
                web.postDelayed(new Runnable() {
                    public void run() {
                        injectShell();
                        renderMessages(loadedJson);
                    }
                }, 350);
            } else {
                renderMessages(json);
            }
        } catch (Throwable t) {
            HotBridge.log(TAG + "加载失败: " + t);
        }
    }

    private static void renderMessages(String json) {
        try {
            web.evaluateJavascript("if(window.render)window.render(" + json + ");", null);
        } catch (Throwable ignored) { }
    }

    /**
     * 给任意模板自动补一套「顶栏 + 输入框」，整页自定义就完整了。
     * 已存在（带 data-dekk-shell）时不再重复注入。
     */
    private static void injectShell() {
        String js = "javascript:(function(){"
                + "if(document.querySelector('[data-dekk-shell]'))return;"
                + "var bar=document.createElement('div');bar.setAttribute('data-dekk-shell','1');"
                + "bar.style.cssText='position:fixed;left:0;right:0;top:0;height:52px;z-index:9999;"
                + "display:flex;align-items:center;gap:10px;padding:0 14px;box-sizing:border-box;"
                + "background:rgba(249,249,249,.94);backdrop-filter:blur(14px);"
                + "border-bottom:.5px solid rgba(0,0,0,.12);font:600 16px/1.2 -apple-system,system-ui,sans-serif;color:#111';"
                + "bar.innerHTML='<div id=\"dekk-menu\" style=\"font-size:20px;width:32px;text-align:center\">☰</div>"
                + "<div id=\"dekk-title\" style=\"flex:1;text-align:center\">DeepSeek</div>"
                + "<div id=\"dekk-native\" style=\"font-size:13px;padding:5px 10px;border-radius:14px;"
                + "border:.5px solid rgba(0,0,0,.14);color:#3482FF\">原生</div>';"
                + "var box=document.createElement('div');box.setAttribute('data-dekk-shell','1');"
                + "box.style.cssText='position:fixed;left:0;right:0;bottom:0;z-index:9999;padding:10px 12px 14px;"
                + "box-sizing:border-box;background:rgba(249,249,249,.96);border-top:.5px solid rgba(0,0,0,.12)';"
                + "box.innerHTML='<div id=\"dekk-tools\" style=\"display:flex;gap:8px;padding:0 2px 8px;overflow-x:auto\">"
                + "<button data-k=\"think\" class=\"dekk-tool\" style=\"flex:0 0 auto;padding:6px 12px;border-radius:16px;"
                + "border:.5px solid rgba(0,0,0,.14);background:#fff;font:13px/1.2 inherit;color:#333\">深度思考</button>"
                + "<button data-k=\"search\" class=\"dekk-tool\" style=\"flex:0 0 auto;padding:6px 12px;border-radius:16px;"
                + "border:.5px solid rgba(0,0,0,.14);background:#fff;font:13px/1.2 inherit;color:#333\">联网搜索</button>"
                + "<button data-k=\"file\" class=\"dekk-tool\" style=\"flex:0 0 auto;padding:6px 12px;border-radius:16px;"
                + "border:.5px solid rgba(0,0,0,.14);background:#fff;font:13px/1.2 inherit;color:#333\">添加文件</button>"
                + "<button data-k=\"voice\" class=\"dekk-tool\" style=\"flex:0 0 auto;padding:6px 12px;border-radius:16px;"
                + "border:.5px solid rgba(0,0,0,.14);background:#fff;font:13px/1.2 inherit;color:#333\">语音输入</button>"
                + "</div>"
                + "<div style=\"display:flex;align-items:flex-end;gap:8px\">"
                + "<textarea id=\"dekk-input\" rows=\"1\" placeholder=\"发消息或按住说话…\" "
                + "style=\"flex:1;resize:none;max-height:120px;padding:10px 12px;border-radius:20px;border:none;"
                + "background:#fff;font:16px/1.4 inherit;outline:none;box-shadow:0 1px 3px rgba(0,0,0,.08)\"></textarea>"
                + "<button id=\"dekk-send\" style=\"width:44px;height:44px;border:none;border-radius:50%;"
                + "background:#3482FF;color:#fff;font-size:16px\">&#10148;</button></div>';"
                + "document.body.appendChild(bar);document.body.appendChild(box);"
                + "document.body.style.paddingTop='52px';document.body.style.boxSizing='border-box';"
                + "var menu=document.getElementById('dekk-menu');"
                + "if(menu)menu.onclick=function(){DekkNative.back();};"
                + "var nat=document.getElementById('dekk-native');"
                + "if(nat)nat.onclick=function(){DekkNative.back();};"
                + "var send=document.getElementById('dekk-send');var input=document.getElementById('dekk-input');"
                + "function doSend(){var t=input.value;if(!t||!t.trim())return;"
                + "DekkNative.send(t.trim());input.value='';}"
                + "if(send)send.onclick=doSend;"
                + "if(input)input.addEventListener('keydown',function(e){"
                + "if(e.keyCode===13&&!e.shiftKey){e.preventDefault();doSend();}});"
                + "var tools=document.querySelectorAll('.dekk-tool');"
                + "for(var ti=0;ti<tools.length;ti++){(function(btn){"
                + "btn.onclick=function(){var k=btn.getAttribute('data-k');"
                + "var on=btn.getAttribute('data-on')==='1';"
                + "btn.setAttribute('data-on',on?'0':'1');"
                + "btn.style.background=on?'#fff':'#E8F0FF';"
                + "btn.style.color=on?'#333':'#3482FF';"
                + "btn.style.borderColor=on?'rgba(0,0,0,.14)':'#3482FF';"
                + "DekkNative.toggle(k,on?'0':'1');};})(tools[ti]);}"
                + "})();";
        try {
            web.loadUrl(js);
        } catch (Throwable t) {
            HotBridge.log(TAG + "顶栏/输入框注入失败: " + t);
        }
    }

    /** 给网页调用的桥：顶栏 / 输入框的按钮 → 宿主动作。 */
    private static final class Bridge {

        private final Activity activity;

        Bridge(Activity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void back() {
            post(new Runnable() {
                public void run() {
                    // 切回原生界面：整页自定义时原生输入框被盖住，这里给一个出口，
                    // 离开聊天页后会自动恢复接管。
                    suspended = true;
                    hide();
                    try {
                        Toast.makeText(activity, "已切回原生（可打开抽屉 / 设置）", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void toast(final String text) {
            post(new Runnable() {
                public void run() {
                    try {
                        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) { }
                }
            });
        }

        /** 发送：先落到剪贴板并提示（宿主发送链路正在接，见 tools/notes_html_send.md）。 */
        @JavascriptInterface
        public void send(final String text) {
            post(new Runnable() {
                public void run() {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("dekk", text));
                        Toast.makeText(activity, "已复制，发送接口下一步接上", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) { }
                }
            });
        }

        /** 顶栏/输入区那排功能按钮：深度思考 / 联网搜索 / 添加文件 / 语音输入。 */
        @JavascriptInterface
        public void toggle(final String key, final String on) {
            post(new Runnable() {
                public void run() {
                    try {
                        HotBridge.log("[DEKK][HTML UI] 网页按钮 " + key + "=" + on
                                + "，输入区状态: " + HtmlUiHook.describeInputState());
                        if ("think".equals(key) || "search".equals(key)) {
                            boolean ok = HtmlUiHook.setFlag(key, "1".equals(on));
                            String label = "think".equals(key) ? "深度思考" : "联网搜索";
                            Toast.makeText(activity, ok
                                    ? (label + ("1".equals(on) ? " 已开启" : " 已关闭"))
                                    : (label + " 开关待绑定（状态已记录）"),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(activity, ("file".equals(key) ? "添加文件" : "语音输入")
                                    + " 入口尚未接上（已记录）", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable t) {
                        HotBridge.log("[DEKK][HTML UI] 网页按钮失败: " + t);
                    }
                }
            });
        }

        private void post(Runnable runnable) {
            new Handler(Looper.getMainLooper()).post(runnable);
        }
    }
}