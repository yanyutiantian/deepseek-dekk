package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;


/**
 * 注入到 DeepSeek 主界面上的「DEKK设置页面」。
 *
 * <p>外观刻意对齐宿主设置页：
 * <ul>
 * <li>56dp 顶栏（自绘返回箭头 + 17sp 标题）</li>
 * <li>12sp 次级色分组标题</li>
 * <li>设置项行（16sp 标题 + 右侧开关，整行可点、原生水波反馈）</li>
 * <li>12~13sp 次级色说明文字</li>
 * </ul>
 *
 * <p>颜色按系统深浅色自适应（宿主深色是 Compose 做的，Android 主题属性在深色下仍是浅色值，
 * 因此这里不直接用 {@code android.R.attr.colorBackground}）。
 */
public final class DekkSettingsPage {

    private static final String PAGE_TITLE = "DEKK设置页面";

    private static final String SWITCH_SELECT99_SECTION = "功能";
    private static final String SWITCH_SELECT99_TITLE = "历史对话全选";
    private static final String SWITCH_SELECT99_DESC =
            "开启后，在「历史对话」里点「全选」会选中 99 条";
private static final String CLASS_MAIN_ACTIVITY = "com.deepseek.chat.MainActivity";
    private static final int PAGE_VIEW_ID = 0x00DE0001;

    private static final String PREF_NAME = "dekk_prefs";
    private static final String PREF_SELECT99 = "select_all_99_enabled";

    /** DeepSeek 品牌蓝（兜底强调色）。 */
    private static final int DEFAULT_ACCENT = 0xFF3482FF;

    private static WeakReference<Activity> sCurrentActivity = new WeakReference<Activity>(null);
    private static volatile boolean sSelect99Enabled = false;

    private DekkSettingsPage() {
    }

    public static boolean isSelectAll99Enabled() {
        return sSelect99Enabled;
    }

    /** 当前正在交互的 Activity（Compose 侧回调里要用）。 */
    static Activity currentActivity() {
        return sCurrentActivity.get();
    }

    public static void install(ClassLoader classLoader) {
        try {
            Class<?> mainActivity = HotHelpers.findClass(CLASS_MAIN_ACTIVITY, classLoader);

            HotHelpers.findAndHookMethod(mainActivity, "onCreate", Bundle.class, new HotHook() {
                @Override
                protected void afterHookedMethod(HotParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    sCurrentActivity = new WeakReference<Activity>(activity);
                    attachPage(activity);
                }
            });

            HotHelpers.findAndHookMethod(mainActivity, "onResume", new HotHook() {
                @Override
                protected void afterHookedMethod(HotParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    sCurrentActivity = new WeakReference<Activity>(activity);
                    attachPage(activity);
                }
            });

            hookBackPressed(mainActivity);

            HotBridge.log("[DEKK] 二级页面宿主已安装");
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 安装二级页面失败: " + t);
        }
    }

    private static void hookBackPressed(Class<?> mainActivity) {
        final Method onBackPressed = findDeclaredMethod(mainActivity, "onBackPressed");
        if (onBackPressed == null) {
            HotBridge.log("[DEKK] 未找到 onBackPressed，返回键由页面自身处理");
            return;
        }
        HotBridge.hookMethod(onBackPressed, new HotHook() {
            @Override
            protected void beforeHookedMethod(HotParam param) throws Throwable {
                Object thisObject = param.thisObject;
                if (!(thisObject instanceof Activity)) {
                    return;
                }
                // 特效 > 评论面板 > 页面，一层层关
                if (EffectPlayer.handleBack((Activity) thisObject)) {
                    param.setResult(null);
                    return;
                }
                if (CommentSheet.handleBack((Activity) thisObject)) {
                    param.setResult(null);
                    return;
                }
                if (CenterPage.handleBack((Activity) thisObject)) {
                    param.setResult(null);
                    return;
                }
                View page = ((Activity) thisObject).findViewById(PAGE_VIEW_ID);
                if (page != null && page.getVisibility() == View.VISIBLE) {
                    page.setVisibility(View.GONE);
                    param.setResult(null);
                }
            }
        });
    }

    private static Method findDeclaredMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    public static void show() {
        try {
            Activity activity = sCurrentActivity.get();
            if (activity == null) {
                HotBridge.log("[DEKK] 当前没有可用的 Activity，无法打开页面");
                return;
            }
            View page = activity.findViewById(PAGE_VIEW_ID);
            if (page == null) {
                attachPage(activity);
                page = activity.findViewById(PAGE_VIEW_ID);
            }
            if (page == null) {
                return;
            }
            // 登录 + KOOK 绑定门禁：未登录直接打开登录页；未绑定弹窗（之后点任何功能都会再弹）
            if (!BindGate.require(activity, null)) {
                return;
            }
            boolean opening=page.getVisibility()!=View.VISIBLE;
            CenterPage.clear(activity);
            // 每次打开都重建内容：宿主设置行的参数模板只有在设置页渲染过之后才存在
            rebuildContent(activity, (ViewGroup) page);
            page.setVisibility(View.VISIBLE);
            page.bringToFront();
            page.requestFocus();
            page.requestApplyInsets();
            // 赞助状态可能还没同步（本地缓存是旧的）：后台补拉一次资料，拿到 sponsor 后重建
            syncSponsor(activity, page);
            if(opening){
                // 首次打开先播开屏（DEKK 逐笔描线，只播一次），播完再走「公告 → 赞助提示」这条串行链。
                // 串行而不是同时 show：同时 show 时后弹的赞助会盖在公告上，看起来顺序就反了。
                final Activity host=activity;
                final Runnable prompts=new Runnable(){public void run(){
                    Announcement.prompt(host, new Runnable(){
                        public void run(){SponsorPage.prompt(host);}
                    });
                }};
                if(!SplashIntro.playOnce(activity,prompts))prompts.run();
            }
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 打开页面失败: " + t);
        }
    }

    private static void rebuildContent(Activity activity, ViewGroup page) {
        page.removeAllViews();
        page.setBackgroundColor(uiBackground(activity));
        page.addView(createContentView(activity));
    }

    /**
     * 赞助身份：本地结论过期就静默问一次服务器（sponsor_check），
     * 结论变了才重建内容 —— 灿金昵称等赞助装饰要跟着变。
     * 「登录」分类 2026-11-01 起不再看赞助，与这里无关。
     */
    private static void syncSponsor(final Activity activity, final View page) {
        final boolean before = SponsorApi.isSponsor(activity);
        SponsorApi.checkIfStale(activity, new SponsorApi.Callback() {
            public void done(boolean sponsor, String error) {
                if (error != null) return;
                if (SponsorApi.isSponsor(activity) == before) return;
                try {
                    if (page instanceof ViewGroup && page.getVisibility() == View.VISIBLE) {
                        rebuildContent(activity, (ViewGroup) page);
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    private static void hide(Activity activity) {
        View page = activity.findViewById(PAGE_VIEW_ID);
        if (page != null) {
            page.setVisibility(View.GONE);
        }
    }

    private static void attachPage(Activity activity) {
        try {
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) {
                return;
            }
            ViewGroup root = (ViewGroup) content;
            if (root.findViewById(PAGE_VIEW_ID) != null) {
                return;
            }

            sSelect99Enabled = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getBoolean(PREF_SELECT99, false);

            PageView page = new PageView(activity);
            page.setId(PAGE_VIEW_ID);
            page.setFocusable(true);
            page.setFocusableInTouchMode(true);
            page.setBackgroundColor(uiBackground(activity));
            page.addView(createContentView(activity));

            // 宿主是 edge-to-edge 布局，页面会顶到状态栏下面；把状态栏高度补成上内边距，
            // 这样顶栏的标题和返回箭头既看得见也点得到。
            page.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int[] position = new int[2];
                    v.getLocationInWindow(position);
                    int top = Math.max(0, insets.getSystemWindowInsetTop() - position[1]);
                    int windowHeight = v.getRootView().getHeight();
                    int bottom = Math.max(0, position[1] + v.getHeight()
                            - (windowHeight - insets.getSystemWindowInsetBottom()));
                    v.setPadding(insets.getSystemWindowInsetLeft(), top,
                            insets.getSystemWindowInsetRight(), bottom);
                    return insets;
                }
            });

            root.addView(page, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            page.setVisibility(View.GONE);
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 挂载页面失败: " + t);
        }
    }

    // ---------------------------------------------------------------- UI

    private static View createContentView(final Activity activity) {
        final boolean dark = isNight(activity);
        FrameLayout root = new FrameLayout(activity);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(ModuleTheme.background(dark));

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = MiuixUi.dp(activity, ModuleTheme.PAGE_PADDING);
        column.setPadding(pad, 0, pad, 0);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, MiuixUi.dp(activity, 132));
        scroll.addView(content);

        final LinearLayout homeItems = new LinearLayout(activity);
        homeItems.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout mineItems = new LinearLayout(activity);
        mineItems.setOrientation(LinearLayout.VERTICAL);
        mineItems.setVisibility(View.GONE);
        content.addView(homeItems);
        content.addView(mineItems);

        homeItems.addView(MiuixUi.header(activity, "模块设置", dark,
                new Runnable(){public void run(){hide(activity);}}));

        // ---- 搜索框
        final LinearLayout searchBox = new LinearLayout(activity);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(MiuixUi.shape(activity, dark ? 0xFF262626 : 0xFFEFEFEF, 14f));
        searchBox.setPadding(MiuixUi.dp(activity, 14), 0, MiuixUi.dp(activity, 14), 0);
        MiuixIcon searchIcon = MiuixUi.icon(activity, MiuixIcon.SEARCH, ModuleTheme.secondary(dark));
        searchBox.addView(searchIcon, new LinearLayout.LayoutParams(
                MiuixUi.dp(activity, 20), MiuixUi.dp(activity, 20)));
        final EditText searchInput = new EditText(activity);
        searchInput.setHint("搜索功能");
        searchInput.setSingleLine(true);
        searchInput.setTextSize(15);
        searchInput.setTextColor(ModuleTheme.text(dark));
        searchInput.setHintTextColor(ModuleTheme.secondary(dark));
        searchInput.setIncludeFontPadding(false);
        searchInput.setBackground(null);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, -1, 1f);
        inputParams.leftMargin = MiuixUi.dp(activity, 10);
        searchBox.addView(searchInput, inputParams);
        LinearLayout.LayoutParams searchParams = blockParams(activity, 6);
        searchParams.height = MiuixUi.dp(activity, 46);
        homeItems.addView(searchBox, searchParams);

        // ---- 状态卡与数据卡（搜索时整体隐藏）
        final LinearLayout hero = new LinearLayout(activity);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.addView(MiuixUi.statusCard(activity, dark, "模块已激活",
                "热更新正常", true), blockParams(activity, 12));
        int enabledCount = FeatureStats.enabled(activity);
        int totalCount = FeatureStats.total();
        LinearLayout stats = new LinearLayout(activity);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams statLeft = new LinearLayout.LayoutParams(0, -2, 1f);
        statLeft.rightMargin = MiuixUi.dp(activity, 10);
        stats.addView(MiuixUi.statCard(activity, dark, "已启用功能", String.valueOf(enabledCount)), statLeft);
        stats.addView(MiuixUi.statCard(activity, dark, "全部功能", String.valueOf(totalCount)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        hero.addView(stats, blockParams(activity, 2));
        homeItems.addView(hero);

        final HomeSearch search = new HomeSearch();

        // ---- 赞助：放在分类最上面
        TextView sponsorSection = MiuixUi.section(activity, dark, "赞助");
        homeItems.addView(sponsorSection, wrapParams());
        View sponsorCard = SponsorPage.entry(activity, dark, uiTextPrimary(activity));
        homeItems.addView(sponsorCard, blockParams(activity, 14));
        search.section(sponsorSection, sponsorCard);
        search.card(sponsorCard);

        // ---- 人设与内容（个人中心已移到「我的」标签页）
        TextView personaSection = MiuixUi.section(activity, dark, "人设与内容");
        homeItems.addView(personaSection, wrapParams());
        View personaCard = AccountCenterCard.create(activity, dark, uiTextPrimary(activity));
        homeItems.addView(personaCard, blockParams(activity, 14));
        search.section(personaSection, personaCard);
        search.card(personaCard);

        // ---- 内容与美化
        TextView beautifySection = MiuixUi.section(activity, dark, "内容与美化");
        homeItems.addView(beautifySection, wrapParams());
        View beautifyCard = createExtensionCard(activity, dark);
        homeItems.addView(beautifyCard, blockParams(activity, 14));
        search.section(beautifySection, beautifyCard);
        search.card(beautifyCard);

        // ---- 模块设置
        TextView settingsSection = MiuixUi.section(activity, dark, "模块设置");
        homeItems.addView(settingsSection, wrapParams());
        LinearLayout settings = MiuixUi.card(activity, dark);
        MiuixUi.row(activity, settings, dark, MiuixIcon.CHAT, "对话",
                "多次回答、专家模式、视觉模式、上下文补充", true,
                new View.OnClickListener(){public void onClick(View v){showFeatureGroup(activity,0);}});
        MiuixUi.row(activity, settings, dark, MiuixIcon.SHIELD, "消息",
                "主动消息、虚拟转账、防撤回、AI 禁言", true,
                new View.OnClickListener(){public void onClick(View v){showFeatureGroup(activity,1);}});
        MiuixUi.row(activity, settings, dark, MiuixIcon.PHONE, "设备与工具",
                "设备状态提示词、ADB 操控、文本选择", true,
                new View.OnClickListener(){public void onClick(View v){showFeatureGroup(activity,2);}});
        MiuixUi.row(activity, settings, dark, MiuixIcon.DOC, "备份与导入",
                "聊天记录加密备份、跨账号导入", true,
                new View.OnClickListener(){public void onClick(View v){BackupPage.show(activity);}});
        homeItems.addView(settings, blockParams(activity, 14));
        search.section(settingsSection, settings);
        search.card(settings);

        // ---- 登录（2026-11-01 起取消赞助限制：普通用户同样可见、可用）
        TextView loginSection = MiuixUi.section(activity, dark, "登录");
        homeItems.addView(loginSection, wrapParams());
        LinearLayout loginCard = MiuixUi.card(activity, dark);
        MiuixUi.row(activity, loginCard, dark, MiuixIcon.SHIELD, "强开登录",
                "强开邮箱账号登录", true,
                new View.OnClickListener(){public void onClick(View v){ForceLoginPage.show(activity);}});
        homeItems.addView(loginCard, blockParams(activity, 14));
        search.section(loginSection, loginCard);
        search.card(loginCard);

        // ---- 液态玻璃悬浮导航
final LiquidGlassNavigation bar = new LiquidGlassNavigation(activity);
         bar.dark(dark);
        final FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        barParams.bottomMargin = MiuixUi.dp(activity, 18);
        root.addView(bar, barParams);
        bar.backdrop(scroll);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener(){
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                // 键盘弹出时 getSystemWindowInsetBottom 会等于键盘高度，绝不能直接加上去，
                // 否则胶囊会被顶到页面中间；这里只认导航栏高度。
                int bottom = Math.min(insets.getSystemWindowInsetBottom(), MiuixUi.dp(activity, 48));
                barParams.bottomMargin = MiuixUi.dp(activity, 18) + bottom;
                bar.setLayoutParams(barParams);
                return insets;
            }
        });
        scroll.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener(){
            public void onScrollChanged() { bar.refresh(); }
        });

        bar.add(MiuixIcon.HOME, "首页", new Runnable(){public void run(){
            homeItems.setVisibility(View.VISIBLE);
            searchBox.setVisibility(View.VISIBLE);
            mineItems.setVisibility(View.GONE);
            bar.refresh();
        }});
        bar.add(MiuixIcon.PERSON, "我的", new Runnable(){public void run(){
            if (mineItems.getChildCount() == 0) {
                UserCenterPage.fillEmbedded(activity, mineItems);
            }
            homeItems.setVisibility(View.GONE);
            mineItems.setVisibility(View.VISIBLE);
            bar.refresh();
        }});
        bar.select(0);

        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(android.text.Editable text) {
                String query = text == null ? "" : text.toString();
                search.apply(query);
                hero.setVisibility(query.trim().length() == 0 ? View.VISIBLE : View.GONE);
            }
        });

        return root;
    }

    private static LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private static LinearLayout.LayoutParams blockParams(Activity activity, int bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = MiuixUi.dp(activity, bottomDp);
        return params;
    }

    /** 首页搜索：按行内文字过滤，空卡片与空小节一起隐藏。 */
    private static final class HomeSearch {
        private final java.util.List<View> cards = new java.util.ArrayList<View>();
        private final java.util.List<View> sections = new java.util.ArrayList<View>();
        private final java.util.List<View> rows = new java.util.ArrayList<View>();
        private final java.util.List<String> keys = new java.util.ArrayList<String>();

        void section(View section, View card) {
            sections.add(section);
            section.setTag(card);
        }

        void card(View card) {
            cards.add(card);
            if (!(card instanceof ViewGroup)) {
                return;
            }
            ViewGroup group = (ViewGroup) card;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!(child instanceof ViewGroup)) {
                    continue;
                }
                String text = textsOf((ViewGroup) child);
                if (text.length() == 0) {
                    continue;
                }
                rows.add(child);
                keys.add(text);
            }
        }

        void apply(String query) {
            String needle = query == null ? "" : query.trim().toLowerCase();
            for (int i = 0; i < rows.size(); i++) {
                boolean visible = needle.length() == 0 || keys.get(i).indexOf(needle) >= 0;
                rows.get(i).setVisibility(visible ? View.VISIBLE : View.GONE);
            }
            for (int i = 0; i < cards.size(); i++) {
                View card = cards.get(i);
                boolean hasRow = false;
                boolean any = false;
                for (int r = 0; r < rows.size(); r++) {
                    if (rows.get(r).getParent() != card) {
                        continue;
                    }
                    hasRow = true;
                    if (rows.get(r).getVisibility() == View.VISIBLE) {
                        any = true;
                        break;
                    }
                }
                card.setVisibility(!hasRow || any ? View.VISIBLE : View.GONE);
            }
            for (int i = 0; i < sections.size(); i++) {
                View section = sections.get(i);
                Object tag = section.getTag();
                boolean visible = !(tag instanceof View) || ((View) tag).getVisibility() == View.VISIBLE;
                section.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        }

        private static String textsOf(ViewGroup group) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView) {
                    CharSequence text = ((TextView) child).getText();
                    if (text != null) {
                        builder.append(text).append(' ');
                    }
                } else if (child instanceof ViewGroup) {
                    builder.append(textsOf((ViewGroup) child));
                }
            }
            return builder.toString().toLowerCase();
        }
    }

    private static void showFeatureGroup(final Activity activity,int group){
        CenterPage page=new CenterPage(activity,group==0?"对话" :group==1?"消息":"设备与工具",null);
        int textColor=page.text,subColor=page.secondary;
        if(group==0){
            page.body.addView(MultiReplyCard.create(activity,page.night,textColor,subColor));
            page.body.addView(ExpertModeCard.create(activity,page.night,textColor,subColor));
            page.body.addView(VisionModeCard.create(activity,page.night,textColor,subColor));
            page.body.addView(ContextSummaryCard.create(activity,page.night,textColor,subColor));
        }else if(group==1){
            MessageInteractionCard.add(page);
            page.body.addView(AntiRecallCard.create(activity,page.night,textColor,subColor));
            page.body.addView(FakeBanCard.create(activity,page.night,textColor,subColor));
            page.body.addView(AiBanCard.create(activity,page.night,textColor,subColor));
            // 语音通话已关闭（VoiceCallStore.FEATURE_ON=false）：设置页不显示入口
            if(VoiceCallStore.FEATURE_ON)page.body.addView(VoiceCallCard.create(activity,page.night,textColor,subColor));
        }else{
            page.body.addView(createSelect99Card(activity,textColor,subColor));
            page.body.addView(createDeviceCard(activity,textColor,subColor));
            page.body.addView(AdbControlCard.create(activity,page.night,textColor,subColor));
        }
        page.show();
    }

    /** DeepSeek 设置页风格的圆角分组卡片。 */
    private static View createSelect99Card(Activity activity, int textColor, int subColor) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
        card.setBackground(roundBackground(activity, isNight(activity)
                ? 0xFF202020 : 0xFFFFFFFF, dp(activity, ModuleTheme.CARD_RADIUS)));
        card.setClipToPadding(false);
        card.addView(createSelect99Item(activity, textColor, subColor),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private static View createExtensionCard(final Activity activity, boolean dark) {
        LinearLayout card = MiuixUi.card(activity, dark);
        MiuixUi.row(activity, card, dark, MiuixIcon.PLUG, "插件中心", "下载、管理与上传插件", true,
                new View.OnClickListener(){public void onClick(View v){PluginPages.show(activity,"market");}});
        MiuixUi.row(activity, card, dark, MiuixIcon.PALETTE, "聊天美化", "聊天背景与气泡样式", true,
                new View.OnClickListener(){public void onClick(View v){BeautifyPage.show(activity);}});
        MiuixUi.row(activity, card, dark, MiuixIcon.MUSIC, "一起听", "网易云或 QQ 音乐一起听", true,
                new View.OnClickListener(){public void onClick(View v){ListenTogetherPage.show(activity);}});
        return card;
    }

    private static View createDeviceCard(final Activity activity, int textColor, int subColor) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundBackground(activity, isNight(activity) ? 0xFF202020 : 0xFFFFFFFF, dp(activity, ModuleTheme.CARD_RADIUS)));
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
        spacing.topMargin = dp(activity, 16);
        card.setLayoutParams(spacing);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14));
        row.setBackground(selectableBackground(activity));
        TextView label = new TextView(activity);
        label.setText("设备状态提示词");
        label.setTextSize(16);
        label.setTextColor(textColor);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        final M3SwitchView toggle = new M3SwitchView(activity);
        toggle.setContentDescription("发送消息时附带设备状态");
        toggle.setTrackColors(DEFAULT_ACCENT, uiSwitchTrackOff(activity));
        toggle.setChecked(DeviceContextHook.enabled(activity));
        toggle.setOnCheckedChangeListener(new M3SwitchView.OnCheckedChangeListener() {
            public void onCheckedChanged(M3SwitchView view, boolean checked) {
                DeviceContextHook.setEnabled(activity, checked);
            }
        });
        row.addView(toggle);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { toggle.performClick(); }
        });
        card.addView(row);
        TextView desc = new TextView(activity);
        desc.setText("默认关闭。发送或编辑文字消息时，仅在请求中附加当前时间、时区、电量和充电状态，不修改本地消息原文。标注为系统提示词，但不是 system 角色。信息会发送给 DeepSeek，历史同步或模型回答可能显示附加内容。关闭后不再附加，已发送信息不会被撤回。");
        desc.setTextSize(13);
        desc.setTextColor(subColor);
        desc.setPadding(dp(activity, 20), 0, dp(activity, 20), dp(activity, 20));
        card.addView(desc);
        return card;
    }

    private static View createAppBar(final Activity activity, int textColor) {
        FrameLayout bar = new FrameLayout(activity);
        bar.setBackgroundColor(uiBackground(activity));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 64)));

        // DeepSeek 设置页是居中标题，左侧为圆形返回按钮
        TextView title = new TextView(activity);
        title.setText(PAGE_TITLE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTextColor(textColor);
        title.setGravity(android.view.Gravity.CENTER);
        bar.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        BackArrowView arrow = new BackArrowView(activity);
        arrow.setColor(textColor);
        arrow.setBackground(roundBackground(activity, isNight(activity)
                ? 0xFF303030 : 0xFFF0F0F0, dp(activity, 30)));
        arrow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide(activity);
            }
        });
        FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(dp(activity, 56), dp(activity, 56));
        arrowParams.gravity = android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL;
        bar.addView(arrow, arrowParams);
        return bar;
    }

    // 顶栏统一由 createAppBar 创建。

    private static View createSectionTitle(Activity activity, int subColor) {
        TextView section = new TextView(activity);
        section.setText(SWITCH_SELECT99_SECTION);
        section.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        section.setTextColor(subColor);
        section.setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 6));
        return section;
    }

    private static View createSelect99Item(Activity activity, int textColor, int subColor) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(activity, 56));
        row.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));
        row.setBackground(selectableBackground(activity));

        TextView label = new TextView(activity);
        label.setText(SWITCH_SELECT99_TITLE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        label.setTextColor(textColor);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        final M3SwitchView toggle = new M3SwitchView(activity);
        toggle.setChecked(sSelect99Enabled);
        toggle.setContentDescription(SWITCH_SELECT99_TITLE);
        toggle.setTrackColors(DEFAULT_ACCENT,
                uiSwitchTrackOff(activity));
        toggle.setOnCheckedChangeListener(new M3SwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(M3SwitchView view, boolean isChecked) {
                applySelect99(view.getContext(), isChecked);
            }
        });
        row.addView(toggle);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle.setChecked(!toggle.isChecked(), true);
                applySelect99(v.getContext(), toggle.isChecked());
            }
        });
        box.addView(row);

        TextView desc = new TextView(activity);
        desc.setText(SWITCH_SELECT99_DESC);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        desc.setTextColor(subColor);
        desc.setLineSpacing(dp(activity, 2), 1f);
        desc.setPadding(dp(activity, 20), 0, dp(activity, 20), dp(activity, 16));
        box.addView(desc);

        return box;
    }

    private static View createDivider(Activity activity) {
        View divider = new View(activity);
        divider.setBackgroundColor(uiDivider(activity));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(activity, 1) / 2)));
        return divider;
    }

    /** 自绘返回箭头（比用「←」字符干净得多）。 */
    static final class BackArrowView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float density;

        BackArrowView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(density * 2f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float arm = density * 7f;

            path.reset();
            path.moveTo(cx + arm, cy - arm);
            path.lineTo(cx - arm, cy);
            path.lineTo(cx + arm, cy + arm);
            canvas.drawPath(path, paint);
            canvas.drawLine(cx - arm, cy, cx + arm * 1.5f, cy, paint);
        }
    }

    /** 页面容器：自己兜底处理返回键。 */
    private static final class PageView extends FrameLayout {

        private PageView(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && getVisibility() == View.VISIBLE) {
                    setVisibility(View.GONE);
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }

    // ------------------------------------------------------------- 主题色

    private static boolean isNight(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int uiBackground(Context context) {
        return isNight(context) ? 0xFF101010 : 0xFFF5F5F5;
    }

    private static android.graphics.drawable.Drawable roundBackground(Context context, int color, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int uiTextPrimary(Context context) {
        return isNight(context) ? 0xFFE8E8E8 : 0xFF181818;
    }

    private static int uiTextSecondary(Context context) {
        return isNight(context) ? 0xFF999999 : 0xFF808080;
    }

    private static int uiDivider(Context context) {
        return isNight(context) ? 0x1FFFFFFF : 0x14000000;
    }

    private static int uiSwitchTrackOff(Context context) {
        return isNight(context) ? 0x33FFFFFF : 0x1F000000;
    }

    private static android.graphics.drawable.Drawable selectableBackground(Context context) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(isNight(context) ? 0x20FFFFFF : 0x10000000),
                null, roundBackground(context, 0xFFFFFFFF, dp(context, 18)));
    }

    private static int resolveThemeColor(Context context, int attr, int fallback) {
        try {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(attr, value, true)) {
                if (value.resourceId != 0) {
                    return context.getResources().getColor(value.resourceId);
                }
                return value.data;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return fallback;
    }

    private static void applySelect99(Context context, boolean value) {
        sSelect99Enabled = value;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SELECT99, value)
                .apply();
    }

    /** 供 Compose 同款页面调用。 */
    static void applySelect99Static(Context context, boolean value) {
        applySelect99(context, value);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 状态栏高度（拿不到系统资源时退化为 24dp）。 */
    private static int statusBarHeight(Context context) {
        try {
            int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                return context.getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return dp(context, 24);
    }
}