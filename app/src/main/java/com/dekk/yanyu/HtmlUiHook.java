package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 自定义 HTML 聊天 UI 的数据与渲染：
 * 挂钩宿主会话，读出完整聊天记录（含历史），再用模板渲染成 WebView。
 *
 * <p>一切失败都只记日志，不影响宿主原本的渲染。
 */
final class HtmlUiHook {

    /** 会话类上会随消息变化被调用的方法名。 */
    /**
     * 覆盖层渲染模式是否启用。
     *
     * <p>渲染层是「整页自定义覆盖层」（见 {@link HtmlUiFull}）：纯 View，不拦截宿主方法。
     * {@code PageMainContent}，把消息区换成我们的 WebView，输入框/输入法/触摸/抽屉全归宿主。
     * 覆盖层的代码保留在这里备查，但不再启用，避免两套同时渲染。
     */
    static final boolean USE_OVERLAY = false;
    /**
     * 需要挂钩的会话方法（对着 2.5.1 的 smali 逐个确认过，都是直接改消息表 wr.f 的那几个）：
     * <ul>
     * <li>{@code a(Lvq;Z)} 写入/更新一条消息（发送、流式回复）</li>
     * <li>{@code c(Lvq;Z)} 按分组对象 bw 更新时间/状态</li>
     * <li>{@code u(Lvq;String)} 移除 pending（中止、失败）</li>
     * <li>{@code v(Lvq)} 按 id 查到并更新</li>
     * <li>{@code A(List;Integer;Z)} 装载消息 + 设置选中</li>
     * <li>{@code z(wr;List)}（静态）批量装载；thisObject 为空，会话在 args[0]</li>
     * </ul>
     */
    private static final String[] SESSION_METHODS = {"a", "c", "u", "v", "z", "A"};

    /**
     * 历史 / 会话列表（侧边抽屉）的行 lambda。它们引用了会话实体 {@code t88}
     * 且是 Compose lambda，只要在跑就说明当前在历史列表而不是聊天页。
     */
    private static final String[] HISTORY_ROW_CLASSES = {"i90", "lr1", "m41", "o51"};
    /** 采集节流：流式输出时避免每帧都遍历消息表。 */
    private static final long COLLECT_MS = 400;

    private static boolean installed;
    private static volatile List<HtmlUiMessages.Msg> last = new ArrayList<HtmlUiMessages.Msg>();
    private static volatile long lastObserve;
    private static volatile boolean collecting;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<WebView> overlay = new WeakReference<WebView>(null);
    private static WeakReference<Activity> overlayHost = new WeakReference<Activity>(null);
    private static volatile String loadedJson = "";
    /** 是否已经在跑「只在聊天页显示」的轮询。 */
    private static volatile boolean ticking;
    /** 判页间隔：宿主是单 Activity + Compose 内部导航，只能靠聊天页特征来判断。 */
    private static final long PAGE_CHECK_MS = 1000;

    /**
     * 聊天页信号：由钩子点亮（聊天气泡组件 / 气泡行 lambda / 会话消息变化），
     * 离开聊天页（模块页弹出、宿主设置页、侧边栏滑出）时熄灭。
     * 这样渲染层是跟着钩子走，不再靠扫输入框提示语猜页面。
     */
    private static volatile boolean onChatPageFlag;

    /** 聊天内容正在渲染 = 现在就在聊天页（由气泡组件钩子调用）。 */
    static void markChatPage() {
        onChatPageFlag = true;
    }

    /** 明确离开聊天页（模块页/宿主设置页/侧边栏滑出）。 */
    static void leaveChatPage() {
        onChatPageFlag = false;
    }

    static boolean isOnChatPage() {
        return onChatPageFlag;
    }

    /**
     * 渲染层只允许出现在聊天页：宿主切到历史/我的等内部页面时要立刻藏起来，
     * 否则会像悬浮窗一样挂在所有页面上（这就是之前「其它页面也被渲染」的原因）。
     */
    private static final Runnable PAGE_TICK = new Runnable() {
        public void run() {
            try {
                Context context = HotApp.currentApplication();
                if (context == null || !HtmlUiStore.enabled(context) || !HtmlUiStore.takeover(context)) {
                    removeOverlay();
                    return;
                }
                Activity activity = DekkSettingsPage.currentActivity();
                WebView web = overlay.get();
                if (activity != null && !activity.isFinishing()) {
                    boolean chatPage = onChatPage(activity);
                    if (!chatPage) {
                        // 模块页 / 宿主设置页 / 侧边栏滑出：熄灭信号；回到聊天页时由气泡钩子重新点亮
                        leaveChatPage();
                    }
                    if (web != null) {
                        int want = chatPage ? View.VISIBLE : View.GONE;
                        if (web.getVisibility() != want) {
                            web.setVisibility(want);
                        }
                    } else if (chatPage) {
                        render();
                        web = overlay.get();
                    }
                }
            } catch (Throwable ignored) { }
            MAIN.postDelayed(this, PAGE_CHECK_MS);
        }
    };

    private static void startTicker() {
        if (ticking) {
            return;
        }
        ticking = true;
        MAIN.postDelayed(PAGE_TICK, PAGE_CHECK_MS);
    }
    /** 上一次打过日志的消息条数，避免刷屏。 */
    private static volatile int loggedCount = -1;

    private HtmlUiHook() { }

    static synchronized void install(final ClassLoader loader) {
        if (installed) {
            return;
        }
        final List<HotUnhook> hooks = new ArrayList<HotUnhook>();
        try {
            Class<?> session = HotHelpers.findClass("wr", loader);
            Method[] methods = session.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                final Method method = methods[i];
                if (!isSessionMethod(method.getName())) {
                    continue;
                }
                hooks.add(HotBridge.hookMethod(method, new HotHook() {
                    protected void afterHookedMethod(HotParam param) {
                        try {
                            // 静态 z(wr;List) 的 thisObject 是 null，会话在第 1 个参数里
                            Object target = param.thisObject;
                            if (target == null && param.args != null && param.args.length > 0) {
                                target = param.args[0];
                            }
                            // 打开会话 / 批量装载 / 新增消息也算进聊天页：
                            // 空会话没有气泡可渲染，只能靠这个信号点亮
                            String hookName = method.getName();
                            if ("A".equals(hookName) || "z".equals(hookName) || "a".equals(hookName)) {
                                markChatPage();
                            }
                            observe(target);
                        } catch (Throwable ignored) { }
                    }
                }));
            }
            // 聊天页信号：对着 smali 核实过的两处 Composable ——
            // ru0.c(...)（14 个参数的聊天气泡行组件）与 p50.q（该行对应的 lambda）。
            // 只要它们在跑，就说明聊天内容正在渲染 = 现在就在聊天页。
            hookComposable(loader, "ru0", "c", 14);
            hookComposable(loader, "p50", "q", 2);
            // 输入框组件 zc.h(Z,Lsv3;,I,Lc76;,Lmd8;,Lqv;,Lkt6;,Lca6;,Lt62;,Ley3;,I)V ——
            // 它只在聊天页被组合，而且「空会话也有」，所以进页面就能立刻接管，
            // 不用再等第一条消息（这是「开启后要先发消息才渲染」的根因）。
            hookComposable(loader, "zc", "h", 11);
            hookInputArea(loader);
            // 历史 / 会话列表（侧边抽屉）的行组件在跑 = 当前不是聊天页，熄灭信号。
            // 宿主是单 Activity + Compose，抽屉滑出的位移在 View 层看不出来，只能靠这个事件。
            for (int i = 0; i < HISTORY_ROW_CLASSES.length; i++) {
                hookHistoryRow(loader, HISTORY_ROW_CLASSES[i]);
            }
            installed = true;
            HotBridge.log("[DEKK][HTML UI] 会话挂钩已安装（" + hooks.size() + " 处）");
        } catch (Throwable t) {
            for (int i = 0; i < hooks.size(); i++) {
                hooks.get(i).unhook();
            }
            HotBridge.log("[DEKK][HTML UI] 安装失败: " + t);
        }
    }

    private static boolean isSessionMethod(String name) {
        for (int i = 0; i < SESSION_METHODS.length; i++) {
            if (SESSION_METHODS[i].equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 输入区状态对象（zc.h 的入参：md8 / qv / kt6 / ca6 / t62 …），供网页按钮反射调用。 */
    private static final Object[] INPUT_ARGS = new Object[12];
    private static volatile boolean inputDumped;

    static Object[] inputArgs() {
        return INPUT_ARGS;
    }

    /**
     * 抓住输入区组件的入参：那排功能按钮（深度思考 / 联网搜索 / 添加文件 / 语音）的开关状态
     * 就在这些对象里。抓到的对象原样留着，网页按钮通过反射读/写（字段名混淆，靠一次性 dump 定位）。
     */
    private static void hookInputArea(ClassLoader loader) {
        try {
            Class<?> zc = HotHelpers.findClass("zc", loader);
            Method[] methods = zc.getDeclaredMethods();
            int hit = 0;
            for (int i = 0; i < methods.length; i++) {
                final Method method = methods[i];
                if (!"h".equals(method.getName()) || method.getParameterTypes().length != 11) {
                    continue;
                }
                HotBridge.hookMethod(method, new HotHook() {
                    protected void afterHookedMethod(HotParam param) {
                        captureInputArea(param);
                    }
                });
                hit++;
            }
            HotBridge.log("[DEKK][HTML UI] 输入区状态捕获已挂钩（" + hit + " 处）");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][HTML UI] 输入区状态挂钩失败: " + t.getClass().getSimpleName());
        }
    }

    private static void captureInputArea(HotParam param) {
        try {
            Object[] args = param.args;
            if (args == null) {
                return;
            }
            int count = Math.min(args.length, INPUT_ARGS.length);
            for (int i = 0; i < count; i++) {
                INPUT_ARGS[i] = args[i];
            }
            if (!inputDumped) {
                inputDumped = true;
                HotBridge.log("[DEKK][HTML UI] 输入区状态: " + describeInputArea(args));
            }
        } catch (Throwable ignored) { }
    }

    /** 一次性 dump：每个入参的类型 + 前 8 个字段值，用来把网页按钮对到宿主的真实开关。 */
    private static String describeInputArea(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            sb.append(i).append(':');
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double || value instanceof String) {
                sb.append(clip(String.valueOf(value), 28));
            } else {
                Class<?> clazz = value.getClass();
                sb.append(clazz.getSimpleName());
                Field[] fields = clazz.getDeclaredFields();
                int shown = 0;
                for (int f = 0; f < fields.length && shown < 8; f++) {
                    try {
                        fields[f].setAccessible(true);
                        Object fieldValue = fields[f].get(value);
                        sb.append(' ').append(fields[f].getName()).append('=')
                                .append(fieldValue == null ? "null" : clip(String.valueOf(fieldValue), 16));
                        shown++;
                    } catch (Throwable ignored) { }
                }
            }
            sb.append(" | ");
        }
        return sb.toString();
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "null";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 当前输入区状态（给网页按钮的日志用，方便把按钮对到宿主真实开关）。 */
    static String describeInputState() {
        try {
            return describeInputArea(INPUT_ARGS);
        } catch (Throwable t) {
            return "(读取失败)";
        }
    }

    /**
     * 网页按钮 → 宿主开关（深度思考 / 联网搜索）。
     *
     * <p>开关状态藏在 {@link #INPUT_ARGS} 那些 state 对象里，字段名被混淆过，
     * 所以要先用一次日志里的「输入区状态」把它们认出来，再在这里按「下标 + 字段名」写动作。
     * 认出来之前一律返回 false（= 提示"尚未接上"），绝不乱翻宿主的开关。
     */
    static boolean setFlag(String key, boolean on) {
        try {
            HotBridge.log(TAG_LOG + "setFlag " + key + "=" + on + " 待绑定（见 tools/notes_html_bridge.md）");
        } catch (Throwable ignored) { }
        return false;
    }

    private static final String TAG_LOG = "[DEKK][HTML UI] ";

    /**
     * 挂钩某处 Composable：命中「类名 + 方法名 + 参数个数」就点亮聊天页信号。
     * 宿主是单 Activity + Compose，View 层读不到路由，所以用「聊天内容正在渲染」当判页依据。
     */
    private static void hookComposable(ClassLoader loader, String className, String methodName, int paramCount) {
        try {
            Class<?> clazz = HotHelpers.findClass(className, loader);
            Method[] methods = clazz.getDeclaredMethods();
            int hit = 0;
            for (int i = 0; i < methods.length; i++) {
                final Method method = methods[i];
                if (!methodName.equals(method.getName())
                        || method.getParameterTypes().length != paramCount) {
                    continue;
                }
                HotBridge.hookMethod(method, new HotHook() {
                    protected void afterHookedMethod(HotParam param) {
                        markChatPage();
                    }
                });
                hit++;
            }
            HotBridge.log("[DEKK][HTML UI] 聊天页信号 " + className + "." + methodName
                    + " 已挂钩（" + hit + " 处）");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][HTML UI] 挂钩 " + className + "." + methodName
                    + " 失败: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 挂钩「历史 / 会话列表」的行 lambda：命中就熄灭聊天页信号。
     * 宿主没有可读的路由，抽屉滑出的位移在 View 层也看不出来，所以用「列表行正在渲染」当依据。
     */
    private static void hookHistoryRow(ClassLoader loader, String className) {
        try {
            Class<?> clazz = HotHelpers.findClass(className, loader);
            Method[] methods = clazz.getDeclaredMethods();
            int hit = 0;
            for (int i = 0; i < methods.length; i++) {
                final Method method = methods[i];
                // Compose lambda 的入口：q(Composer, int)
                if (!"q".equals(method.getName()) || method.getParameterTypes().length != 2) {
                    continue;
                }
                HotBridge.hookMethod(method, new HotHook() {
                    protected void afterHookedMethod(HotParam param) {
                        leaveChatPage();
                    }
                });
                hit++;
            }
            HotBridge.log("[DEKK][HTML UI] 历史列表信号 " + className + " 已挂钩（" + hit + " 处）");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][HTML UI] 挂钩历史页 " + className
                    + " 失败: " + t.getClass().getSimpleName());
        }
    }

    /** 会话有变化：节流后在后台线程读出消息，再回主线程渲染。 */
    static void observe(final Object session) {
        final Context context = HotApp.currentApplication();
        if (context == null || session == null || !HtmlUiStore.enabled(context)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (collecting || now - lastObserve < COLLECT_MS) {
            return;
        }
        lastObserve = now;
        collecting = true;
        new Thread(new Runnable() {
            public void run() {
                List<HtmlUiMessages.Msg> collected;
                try {
                    collected = HtmlUiMessages.fromSession(session);
                } catch (Throwable t) {
                    collected = new ArrayList<HtmlUiMessages.Msg>();
                }
                collecting = false;
                // 空会话也要渲染（没有消息时 HTML 也要出现），所以这里不提前 return
                List<HtmlUiMessages.Msg> messages = collected;
                try {
                    // 正则优先：用户给的正则能从全文里挑出消息时按正则的顺序来
                    List<HtmlUiMessages.Msg> byRegex = HtmlUiMessages.classify(
                            HtmlUiMessages.transcript(collected),
                            HtmlUiStore.userRegex(context), HtmlUiStore.aiRegex(context));
                    if (!byRegex.isEmpty()) {
                        messages = byRegex;
                    }
                } catch (Throwable ignored) { }
                last = messages;
                if (messages.size() != loggedCount) {
                    loggedCount = messages.size();
                    int user = 0;
                    for (int i = 0; i < messages.size(); i++) {
                        if ("user".equals(messages.get(i).role)) user++;
                    }
                    HotBridge.log("[DEKK][HTML UI] 采集到 " + messages.size()
                            + " 条消息（用户 " + user + " / AI " + (messages.size() - user) + "）："
                            + HtmlUiMessages.summary(messages, 6));
                }
                MAIN.post(new Runnable() {
                    public void run() { render(); }
                });
            }
        }, "DEKK-html-ui").start();
    }

    /** 配置变化（开关、模板、正则、头像/壁纸）后重新渲染。 */
    static void onConfigChanged(Context context) {
        if (context == null) {
            return;
        }
        if (!HtmlUiStore.enabled(context) || !HtmlUiStore.takeover(context)) {
            removeOverlay();
            return;
        }
        // 留白/模板/开关变化都要重建覆盖层（尺寸变了不能只换消息）
        removeOverlay();
        if (!last.isEmpty()) {
            loadedJson = "";
            render();
        }
    }

    /** 当前采集到的消息（预览页也用这个）。 */
    static List<HtmlUiMessages.Msg> messages() {
        return last;
    }

    /** 预览页把当前模板渲染到指定 WebView。 */
    static void previewInto(WebView web, Context context, boolean sample) {
        try {
            List<HtmlUiMessages.Msg> messages = sample || last.isEmpty()
                    ? HtmlUiMessages.sample() : last;
            web.loadDataWithBaseURL(null, HtmlUiRenderer.build(context, messages),
                    "text/html", "utf-8", null);
        } catch (Throwable t) {
            HotBridge.log("[DEKK][HTML UI] 预览渲染失败: " + t);
        }
    }

    static WebView createWebView(Context activity) {
        WebView web = new WebView(activity);
WebSettings settings = web.getSettings();
        try {
            settings.setJavaScriptEnabled(true);
            // 需要它才能加载我们写到宿主 cache 目录里的本地 HTML
            // （data: URL 在部分机型上不绘制内容，且 javascript: URL 会被拦）
            settings.setAllowFileAccess(true);
            settings.setDomStorageEnabled(false);
        } catch (Throwable ignored) { }
        web.setVerticalScrollBarEnabled(false);
        // 背景必须透明：铺满全屏时，页面没画到的地方（标题栏、输入区）要能透出宿主的界面，
        // 否则一块不透明底色会把输入框整个盖住（之前就是这个原因）。
        web.setBackgroundColor(0);
        try {
            web.setBackgroundDrawable(null);
        } catch (Throwable ignored) { }
        return web;
    }

    private static void render() {
        Context context = HotApp.currentApplication();
        if (context == null || !HtmlUiStore.enabled(context) || !HtmlUiStore.takeover(context)) {
            return;
        }
        Activity activity = DekkSettingsPage.currentActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (!USE_OVERLAY) {
            // 渲染已交给整页自定义层（HtmlUiFull），这里的覆盖层不再渲染
            return;
        }
        WebView web = ensureOverlay(activity);
        if (web == null) {
            return;
        }
        startTicker();
        String json = HtmlUiMessages.toJson(last);
        if (json.equals(loadedJson)) {
            return;
        }
        boolean first = loadedJson.length() == 0;
        loadedJson = json;
        try {
            if (first) {
                web.loadDataWithBaseURL(null, HtmlUiRenderer.build(context, last),
                        "text/html", "utf-8", null);
            } else {
                // 已经加载过模板：只把新消息交给页面重绘，避免重复塞 base64 壁纸
                web.evaluateJavascript("if(window.render)window.render(" + json + ");", null);
            }
        } catch (Throwable t) {
            HotBridge.log("[DEKK][HTML UI] 渲染失败: " + t);
        }
    }

    // 判页说明见下面 onChatPage：宿主是单 Activity + Compose 内部导航，
    // 现在完全由钩子信号（气泡组件 / 会话变化）点亮，不再扫输入框提示语。
    /**
     * 判断「现在是不是聊天页」—— 完全跟着钩子信号走，不再扫输入框提示语：
     * <ol>
     * <li>{@link #onChatPageFlag}：由聊天气泡组件 / 气泡行 lambda / 会话消息变化点亮；</li>
     * <li>我们自己的模块页面（CenterPage、评论面板等）可见时不算聊天页；</li>
     * <li>侧边栏（历史会话抽屉）滑出时视图被整体位移，这时也不算聊天页。</li>
     * </ol>
     */
    private static boolean onChatPage(Activity activity) {
        if (activity == null || !onChatPageFlag) {
            return false;
        }
        try {
            View root = activity.findViewById(android.R.id.content);
            if (root == null) {
                return false;
            }
            if (hasVisibleModulePage(root)) {
                return false;
            }
            WebView web = overlay.get();
            if (web != null && isShifted(web)) {
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // 原「输入框提示语」判定已移除：历史页/设置页也有输入框，判定必然到处为真。
/** 我们的模块页面（设置页 / 预览页）是否正盖在宿主上 —— 整页层要让开。 */
    static boolean modulePageVisible(Activity activity) {
        try {
            if (activity == null) {
                return false;
            }
            return hasVisibleModulePage(activity.getWindow().getDecorView());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 我们自己的模块页面正盖在上面时，不算聊天页。 */
    private static boolean hasVisibleModulePage(View view) {
        if (view instanceof CenterPage) {
            return view.getVisibility() == View.VISIBLE;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasVisibleModulePage(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 视图（或它的祖先）被位移了：侧边栏/抽屉滑出时会出现。 */
    private static boolean isShifted(View view) {
        View current = view;
        int guard = 0;
        while (current != null && guard++ < 24) {
            if (Math.abs(current.getTranslationX()) > 0.5f) {
                return true;
            }
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static WebView ensureOverlay(Activity activity) {
        WebView existing = overlay.get();
        if (existing != null && overlayHost.get() == activity && existing.getParent() != null) {
            return existing;
        }
        removeOverlay();
        try {
            ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
            if (root == null) {
                return null;
            }
            WebView web = createWebView(activity);
            Context app = HotApp.currentApplication();
            boolean full = HtmlUiStore.fullscreen(app);
            // 宿主的视图 = content 下唯一一个不是我们自己的子视图（通常就是宿主的 ComposeView）
            View host = null;
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (child != web && !(child instanceof HtmlUiLayer)) {
                    host = child;
                    break;
                }
            }
            int passThrough = full ? MiuixUi.dp(activity, HtmlUiStore.bottomInset(app)) : 0;
            HtmlUiLayer layer = new HtmlUiLayer(activity, web, host, passThrough);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
            if (!full) {
                // 老行为：渲染层直接避开顶部标题栏与底部输入区（不覆盖输入框）
                params.topMargin = MiuixUi.dp(activity, HtmlUiStore.topInset(app));
                params.bottomMargin = MiuixUi.dp(activity, HtmlUiStore.bottomInset(app));
            }
            // 全屏模式：铺满整屏，底部输入区那一条靠 HtmlUiLayer 透传触摸
            root.addView(layer, params);
            // 建好就按「当前是不是聊天页」决定显示，避免在别的页面闪一下
            web.setVisibility(onChatPage(activity) ? View.VISIBLE : View.GONE);
            overlay = new WeakReference<WebView>(web);
            overlayHost = new WeakReference<Activity>(activity);
            loadedJson = "";
            HotBridge.log("[DEKK][HTML UI] 已在聊天页挂上 HTML 渲染层");
            return web;
        } catch (Throwable t) {
            HotBridge.log("[DEKK][HTML UI] 覆盖层创建失败: " + t);
            return null;
        }
    }

    static void removeOverlay() {
        try {
            WebView web = overlay.get();
            if (web != null && web.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) web.getParent();
                if (parent instanceof HtmlUiLayer && parent.getParent() instanceof ViewGroup) {
                    // 连容器一起摘掉，避免每次重建都在 content 里留一个空壳
                    ((ViewGroup) parent.getParent()).removeView(parent);
                } else {
                    parent.removeView(web);
                }
            }
        } catch (Throwable ignored) { }
        overlay = new WeakReference<WebView>(null);
        overlayHost = new WeakReference<Activity>(null);
        loadedJson = "";
    }
}