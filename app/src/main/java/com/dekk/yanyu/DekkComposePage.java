package com.dekk.yanyu;

import android.app.Activity;
import android.view.View;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;


/**
 * 用宿主（DeepSeek）自己的 Compose 组件渲染「DEKK设置页面」的设置项，
 * 从而与宿主设置页完全同款（同组件、同主题、同配色）。
 *
 * <p>做法：设置页真实渲染「检查更新」那一行时，把 {@code ob8->b(...)} 的完整参数克隆一份当模板；
 * 我们的页面再用这份模板重放一次，只替换：
 * <ul>
 * <li>标题槽位 →「历史聊天全选 99 条」</li>
 * <li>尾部内容槽位（宿主那行用来显示版本号的位置）→ 右侧 Material3 开关</li>
 * <li>整行点击回调 → 切换开关</li>
 * </ul>
 *
 * <p>所有调用都包在 try/catch 里：任何一步对不上（版本差异/参数位不对）只会记日志并回退到原生实现。
 */
public final class DekkComposePage {

    private static final String CLASS_COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView";
    private static final String CLASS_SETTING_ITEM = "ob8";
    private static final String CLASS_TEXT = "z89";
    private static final String CLASS_SWITCH_KT = "lj0";

    /** ob8->b 的部分参数下标。 */
    private static final int ARG_ON_CLICK = 1;
    private static final int ARG_COMPOSER = 10;

    /** 可能是「内容 lambda」的槽位（不含标题槽位）。 */
    private static final int[] CONTENT_SLOTS = new int[]{5, 6, 7};

    private static final String PAGE_SWITCH_TITLE = "历史聊天全选 99 条";

    /** DEKK模块 入口右侧显示的版本号。 */
    public static final String MODULE_VERSION = "0.0.1";

    /** Switch 默认位掩码（与宿主 ju.smali 的用法一致：enabled / colors 走默认值）。 */
    private static final int SWITCH_DEFAULT_MASK = 0x18;

    private static ClassLoader sLoader;
    private static Class<?> sSettingItemClass;
    private static Class<?> sFunction0Class;
    private static Class<?> sFunction1Class;
    private static Class<?> sFunction2Class;
    private static Method sTextMethod;
    private static Method sSwitchMethod;
    private static Method sSetContentMethod;
    private static Class<?> sContentLambdaInterface;
    private static Object sEmptyModifier;

    private static volatile boolean sReady;
    private static volatile boolean sLoggedRender;

    private static volatile Object[] sRowTemplate;
    private static volatile int sTitleIndex = -1;
    private static volatile int sTrailingIndex = -1;
    private static volatile Class<?>[] sTrailingInterfaces;

    private DekkComposePage() {
    }

    /** 设置页渲染真实设置行时调用，记录模板。 */
    public static void captureRow(Object[] args, int titleIndex) {
        if (args == null || titleIndex < 0 || titleIndex >= args.length) {
            return;
        }
        sRowTemplate = args.clone();
        sTitleIndex = titleIndex;

        // 尾部内容 = 非标题槽位里下标最大的那个非空项（宿主那行用它显示版本号）
        int trailing = -1;
        StringBuilder dump = new StringBuilder();
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = CONTENT_SLOTS[i];
            Object value = args[index];
            dump.append(index).append('=').append(value == null ? "null"
                    : value.getClass().getName());
            dump.append(i == CONTENT_SLOTS.length - 1 ? "" : ", ");
            if (value != null) {
                trailing = index;
            }
        }
        sTrailingIndex = trailing;
        sTrailingInterfaces = interfacesOf(trailing >= 0 ? args[trailing] : null);
        HotBridge.log("[DEKK] 设置行模板: 标题槽位=" + titleIndex + ", 尾部槽位=" + trailing
                + ", 内容槽位[" + dump + "]");
    }

    /** 尾部槽位下标（-1 表示宿主那行没有尾部内容）。 */
    public static int getTrailingIndex() {
        return sTrailingIndex;
    }

    public static boolean isReady() {
        return sReady && sRowTemplate != null;
    }

    public static void install(ClassLoader classLoader) {
        sLoader = classLoader;
        try {
            Class<?> composeView = HotHelpers.findClass(CLASS_COMPOSE_VIEW, classLoader);
            sSettingItemClass = HotHelpers.findClass(CLASS_SETTING_ITEM, classLoader);
            Class<?> textClass = HotHelpers.findClass(CLASS_TEXT, classLoader);
            Class<?> switchClass = HotHelpers.findClass(CLASS_SWITCH_KT, classLoader);

            Method[] setContentCandidates = composeView.getDeclaredMethods();
            for (int i = 0; i < setContentCandidates.length; i++) {
                Method method = setContentCandidates[i];
                if ("setContent".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    sSetContentMethod = method;
                    sContentLambdaInterface = method.getParameterTypes()[0];
                    break;
                }
            }
            if (sSetContentMethod == null) {
                HotBridge.log("[DEKK] 未找到 ComposeView.setContent，改用原生页面");
                return;
            }

            sTextMethod = findMethod(textClass, "b", 18);
            sSwitchMethod = findMethod(switchClass, "w", 9);
            if (findMethod(sSettingItemClass, "b", 13) == null
                    || sTextMethod == null || sSwitchMethod == null) {
                HotBridge.log("[DEKK] 宿主 Compose 组件不完整，改用原生页面 (text="
                        + (sTextMethod != null) + ", switch=" + (sSwitchMethod != null) + ")");
                return;
            }
            sTextMethod.setAccessible(true);
            sSwitchMethod.setAccessible(true);

            sFunction2Class = HotHelpers.findClass("wv3", classLoader);
            sFunction0Class = HotHelpers.findClass("hv3", classLoader);
            // onCheckedChange 的类型直接取 Switch 的第 2 个参数，避免猜类名
            sFunction1Class = sSwitchMethod.getParameterTypes()[1];
            sEmptyModifier = findEmptyModifier(classLoader);

            sReady = true;
            HotBridge.log("[DEKK] Compose 同款页面已就绪 (onCheckedChange="
                    + sFunction1Class.getName() + ")");
        } catch (Throwable t) {
            HotBridge.log("[DEKK] Compose 页面初始化失败，改用原生页面: " + t);
            sReady = false;
        }
    }

    /** 创建与宿主设置页同款的页面视图；不可用时返回 null 由调用方回退。 */
    public static View createView(final Activity activity) {
        if (!isReady()) {
            HotBridge.log("[DEKK] Compose 页面暂不可用(ready=" + sReady
                    + ", template=" + (sRowTemplate != null)
                    + ", titleIndex=" + sTitleIndex + ")");
            return null;
        }
        try {
            Object composeView = HotHelpers.newInstance(
                    HotHelpers.findClass(CLASS_COMPOSE_VIEW, activity.getClassLoader()),
                    activity);
            Object contentLambda = Proxy.newProxyInstance(
                    activity.getClassLoader(),
                    new Class<?>[]{sContentLambdaInterface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            render(args);
                            return null;
                        }
                    });
            sSetContentMethod.invoke(composeView, contentLambda);
            return (View) composeView;
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 创建 Compose 页面失败，改用原生页面: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------ 生成 lambda

    /** 给设置页的「DEKK模块」入口用的版本号文本 lambda。 */
    public static Object createVersionLambda(ClassLoader classLoader) {
        Class<?>[] ifaces = sTrailingInterfaces;
        if (ifaces == null || ifaces.length == 0) {
            return null;
        }
        return newTextLambda(classLoader, ifaces, MODULE_VERSION);
    }

    private static void render(Object[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        final Object composer = args[0];
        try {
            if (!sLoggedRender) {
                sLoggedRender = true;
                HotBridge.log("[DEKK] 用宿主组件渲染同款设置项: 标题槽位=" + sTitleIndex
                        + ", 开关槽位=" + sTrailingIndex);
            }
            Object[] row = sRowTemplate.clone();
            row[ARG_COMPOSER] = composer;
            row[sTitleIndex] = newTextLambda(sLoader,
                    new Class<?>[]{sFunction2Class}, PAGE_SWITCH_TITLE);
            row[ARG_ON_CLICK] = newClickLambda(sLoader);
            if (sTrailingIndex >= 0) {
                row[sTrailingIndex] = newSwitchLambda(sLoader);
            }
            HotHelpers.callStaticMethod(sSettingItemClass, "b", row);
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 渲染同款设置项失败: " + t);
        }
    }

    /** 文本 lambda：调用宿主自己的文本 Composable。 */
    private static Object newTextLambda(final ClassLoader classLoader,
                                        Class<?>[] interfaces, final String text) {
        return Proxy.newProxyInstance(classLoader, interfaces,
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        try {
                            Object composer = (args != null && args.length > 0) ? args[0] : null;
                            sTextMethod.invoke(null, new Object[]{
                                    text,
                                    null, Long.valueOf(0L),
                                    null, Long.valueOf(0L),
                                    null, Long.valueOf(0L),
                                    null, Long.valueOf(0L),
                                    Integer.valueOf(0), Boolean.FALSE,
                                    Integer.valueOf(0), Integer.valueOf(0),
                                    null, composer,
                                    Integer.valueOf(0), Integer.valueOf(0), Integer.valueOf(0x3FFFE)});
                        } catch (Throwable t) {
                            HotBridge.log("[DEKK] 渲染文本「" + text + "」失败: " + t);
                        }
                        return null;
                    }
                });
    }

    /** 开关 lambda：调用宿主的 Material3 Switch。 */
    private static Object newSwitchLambda(final ClassLoader classLoader) {
        final Class<?>[] ifaces = (sTrailingInterfaces != null && sTrailingInterfaces.length > 0)
                ? sTrailingInterfaces
                : new Class<?>[]{sFunction2Class};
        return Proxy.newProxyInstance(classLoader, ifaces,
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        try {
                            Object composer = (args != null && args.length > 0) ? args[0] : null;
                            Object onCheckedChange = Proxy.newProxyInstance(classLoader,
                                    new Class<?>[]{sFunction1Class},
                                    new InvocationHandler() {
                                        @Override
                                        public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                                            boolean checked = a != null && a.length > 0
                                                    && Boolean.TRUE.equals(a[0]);
                                            Activity activity = DekkSettingsPage.currentActivity();
                                            if (activity != null) {
                                                DekkSettingsPage.applySelect99Static(activity, checked);
                                            }
                                            return null;
                                        }
                                    });
                            sSwitchMethod.invoke(null,
                                    Boolean.valueOf(DekkSettingsPage.isSelectAll99Enabled()),
                                    onCheckedChange,
                                    sEmptyModifier,
                                    Boolean.TRUE,
                                    null,
                                    null,
                                    composer,
                                    Integer.valueOf(0),
                                    Integer.valueOf(SWITCH_DEFAULT_MASK));
                        } catch (Throwable t) {
                            HotBridge.log("[DEKK] 渲染开关失败: " + t);
                        }
                        return null;
                    }
                });
    }

    /** 整行点击：切换开关状态。 */
    private static Object newClickLambda(final ClassLoader classLoader) {
        return Proxy.newProxyInstance(classLoader, new Class<?>[]{sFunction0Class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        Activity activity = DekkSettingsPage.currentActivity();
                        if (activity != null) {
                            DekkSettingsPage.applySelect99Static(
                                    activity, !DekkSettingsPage.isSelectAll99Enabled());
                        }
                        return null;
                    }
                });
    }

    // ------------------------------------------------------------- 反射工具

    private static Method findMethod(Class<?> clazz, String name, int paramCount) {
        Method[] methods = clazz.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            if (name.equals(methods[i].getName())
                    && methods[i].getParameterTypes().length == paramCount) {
                return methods[i];
            }
        }
        return null;
    }

    private static Class<?>[] interfacesOf(Object value) {
        if (value == null) {
            return null;
        }
        Class<?>[] all = value.getClass().getInterfaces();
        List<Class<?>> kept = new ArrayList<Class<?>>();
        for (int i = 0; i < all.length; i++) {
            if (all[i].isInterface()) {
                kept.add(all[i]);
            }
        }
        return kept.isEmpty() ? null : kept.toArray(new Class<?>[kept.size()]);
    }

    private static Object findEmptyModifier(ClassLoader classLoader) {
        try {
            Class<?> companion = HotHelpers.findClass(
                    "androidx.compose.ui.Modifier$Companion", classLoader);
            return HotHelpers.getStaticObjectField(companion, "INSTANCE");
        } catch (Throwable t) {
            return null;
        }
    }
}