package com.dekk.yanyu;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;


/**
 * 在 DeepSeek 设置页（Jetpack Compose 的 SettingsPage）的「检查更新」行后面，
 * 追加一个名为「DEKK模块」的入口行。
 *
 * <p>实现思路：
 * 设置页所有条目都由 {@code ob8->b(...)}（SettingItem.kt 里的 SettingItem 组件）渲染。
 * 「检查更新」行的标题内容 lambda 是合成类 {@code k72} 中 {@code a == 15} 的单例实例，
 * 因此只要在 {@code ob8->b} 的参数里发现该 lambda，就说明当前正在渲染「检查更新」行。
 * 此时先让原行正常渲染，再以同样的参数（复用同一份图标/箭头等）重新调用一次
 * {@code ob8->b}，只把标题 lambda 换成「DEKK模块」、把点击回调换成我们自己的实现，
 * 从而在其正下方插入一个新条目。
 */
public final class DeepSeekSettingsHook {

    /** 目标应用包名。 */
    public static final String DEEPSEEK_PACKAGE = "com.deepseek.chat";

    /** SettingItem 组件所在类（SettingItem.kt）。 */
    private static final String CLASS_SETTING_ITEM = "ob8";
    private static final String METHOD_SETTING_ITEM = "b";

    /** Composable 参数类型（混淆名）。 */
    private static final String CLASS_MODIFIER = "c76";   // Modifier
    private static final String CLASS_FUNCTION0 = "hv3";  // () -> Object（点击回调）
    private static final String CLASS_FUNCTION2 = "wv3";  // (Composer, Integer) -> Object（内容 lambda）
    private static final String CLASS_COMPOSER = "ey3";   // Composer
    private static final String CLASS_TEXT = "z89";       // 文本 Composable

    /** 「检查更新」行标题 lambda 的混淆类名与索引。 */
    private static final String CLASS_UPDATE_ROW_LAMBDA = "k72";
    private static final int UPDATE_ROW_LAMBDA_INDEX = 15;

    /** Compose 的 ComposableLambda 包装类（其字段 c 持有真正的 lambda）。 */
    private static final String CLASS_COMPOSABLE_LAMBDA = "t62";

    private static final String FIELD_COMPOSABLE_LAMBDA_TARGET = "c";

    /** ob8->b 参数下标：可能的“内容 lambda”位置与点击回调位置。 */
    private static final int[] CONTENT_ARG_INDEXES = new int[]{5, 6, 7, 9};
    private static final int ARG_ON_CLICK = 1;

    /** 新入口的文案。 */
    private static final String ENTRY_TITLE = "DEKK模块";

    /** 新入口右侧显示的版本号。 */
    private static final String MODULE_VERSION = "1.5.4";

    /** z89->b 文本 Composable 的参数个数。 */
    private static final int TEXT_METHOD_PARAM_COUNT = 18;

    /** 防止追加行再次触发本 hook 造成递归。 */
    private static final ThreadLocal<Boolean> RENDERING = new ThreadLocal<Boolean>();

    private static Method sTextMethod;
    private static Class<?> sFunction2;
    private static Class<?> sComposableLambda;
    private static Constructor<?> sComposableLambdaCtor;

    private DeepSeekSettingsHook() {
    }

    public static void install(final ClassLoader classLoader) {
        try {
            Class<?> settingItem = HotHelpers.findClass(CLASS_SETTING_ITEM, classLoader);
            Class<?> modifier = HotHelpers.findClass(CLASS_MODIFIER, classLoader);
            Class<?> function0 = HotHelpers.findClass(CLASS_FUNCTION0, classLoader);
            sFunction2 = HotHelpers.findClass(CLASS_FUNCTION2, classLoader);
            Class<?> composer = HotHelpers.findClass(CLASS_COMPOSER, classLoader);

            sComposableLambda = HotHelpers.findClass(CLASS_COMPOSABLE_LAMBDA, classLoader);
            sComposableLambdaCtor = sComposableLambda.getDeclaredConstructor(
                    Object.class, boolean.class, int.class);
            sComposableLambdaCtor.setAccessible(true);

            Class<?> textClass = HotHelpers.findClass(CLASS_TEXT, classLoader);
            sTextMethod = findTextMethod(textClass);
            if (sTextMethod == null) {
                HotBridge.log("[DEKK] 未找到文本 Composable，放弃注入");
                return;
            }

            HotBridge.hookMethod(sTextMethod, new HotHook() {
                @Override
                protected void beforeHookedMethod(HotParam param) throws Throwable {
                    if (!Boolean.TRUE.equals(RENDERING.get())) return;
                    Object value = param.args[0];
                    if (value instanceof String && ((String) value).matches("(?i).*\\d+\\.\\d+\\.\\d+.*")) {
                        param.args[0] = MODULE_VERSION;
                    }
                }
            });

            HotHelpers.findAndHookMethod(
                    settingItem,
                    METHOD_SETTING_ITEM,
                    modifier, function0,
                    boolean.class, boolean.class, long.class,
                    sFunction2, sFunction2, sFunction2, float.class, sFunction2,
                    composer, int.class, int.class,
                    new HotHook() {
                        @Override
                        protected void afterHookedMethod(HotParam param) throws Throwable {
                            appendEntry(param, classLoader);
                        }
                    });
            HotBridge.log("[DEKK] DeepSeek 设置页 Hook 已安装");
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 安装 Hook 失败: " + t);
        }
    }

    /**
     * 若当前渲染的是「检查更新」行，则在其后追加「DEKK模块」入口。
     */
    private static void appendEntry(HotParam param, ClassLoader classLoader) {
        if (Boolean.TRUE.equals(RENDERING.get())) {
            return;
        }
        Object[] args = param.args;
        if (args == null || args.length < 13) {
            return;
        }
        int titleIndex = findUpdateRowTitleIndex(args);
        if (titleIndex < 0) {
            return;
        }
        // 兜底：Compose 的槽位里 int/long/float/boolean 是原始类型，任何一个为 null 都会
        // 让宿主在拆箱时抛异常、并破坏 Compose 的合成状态（表现为随后的 Start/end imbalance 崩溃）。
        // 类型/取值不对就放弃这次注入，宁可没有入口也不要崩宿主。
        if (!slotsUsable(args)) {
            HotBridge.log("[DEKK] 设置页入参不完整，跳过注入以保证宿主稳定");
            return;
        }
        RENDERING.set(Boolean.TRUE);
        try {
            Object[] newArgs = new Object[args.length];
            System.arraycopy(args, 0, newArgs, 0, args.length);
            newArgs[titleIndex] = createTitleLambda(classLoader);
            newArgs[ARG_ON_CLICK] = createClickLambda(classLoader);

            // 保留宿主槽位结构；只在追加行渲染期间改写版本文本。
            int versionIndex = -1;

            HotBridge.log("[DEKK] 检测到「检查更新」行，正在注入「DEKK模块」入口 (版本槽位="
                    + versionIndex + ")");
            HotHelpers.callStaticMethod(
                    HotHelpers.findClass(CLASS_SETTING_ITEM, classLoader),
                    METHOD_SETTING_ITEM,
                    newArgs);
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 追加入口失败: " + t);
        } finally {
            RENDERING.set(Boolean.FALSE);
        }
    }

    /**
     * ob8->b 的原始类型槽位是否都可用：int 下标 11/12、boolean 2/3、long 4、float 8。
     */
    private static boolean slotsUsable(Object[] args) {
        if (args.length < 13) {
            return false;
        }
        if (!(args[2] instanceof Boolean) || !(args[3] instanceof Boolean)) {
            return false;
        }
        if (!(args[4] instanceof Long) || !(args[8] instanceof Float)) {
            return false;
        }
        if (!(args[11] instanceof Integer) || !(args[12] instanceof Integer)) {
            return false;
        }
        return true;
    }

    /**
     * 找「检查更新」行的尾部内容槽位（显示版本号的那个），返回下标；找不到返回 -1。
     */
    private static int findVersionSlot(Object[] args, int titleIndex) {
        int found = -1;
        for (int i = 0; i < CONTENT_ARG_INDEXES.length; i++) {
            int index = CONTENT_ARG_INDEXES[i];
            if (index != titleIndex && index < args.length && args[index] != null) {
                found = index;
            }
        }
        return found;
    }

    /**
     * 在 SettingItem 的入参里定位「检查更新」行的标题 lambda，返回其下标；找不到返回 -1。
     */
    private static int findUpdateRowTitleIndex(Object[] args) {
        for (int i = 0; i < CONTENT_ARG_INDEXES.length; i++) {
            int index = CONTENT_ARG_INDEXES[i];
            if (index >= args.length) {
                continue;
            }
            if (isUpdateRowLambda(args[index])) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 判断某个参数是否为「检查更新」行的标题 lambda。
     *
     * <p>Compose 会先把真正的 lambda 包进 ComposableLambda（t62）里，因此需要拆一层。
     */
    private static boolean isUpdateRowLambda(Object value) {
        if (value == null) {
            return false;
        }
        Object lambda = value;
        if (CLASS_COMPOSABLE_LAMBDA.equals(value.getClass().getName())) {
            try {
                lambda = HotHelpers.getObjectField(value, FIELD_COMPOSABLE_LAMBDA_TARGET);
            } catch (Throwable t) {
                return false;
            }
        }
        if (lambda == null || !CLASS_UPDATE_ROW_LAMBDA.equals(lambda.getClass().getName())) {
            return false;
        }
        try {
            return HotHelpers.getIntField(lambda, "a") == UPDATE_ROW_LAMBDA_INDEX;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把内容 lambda 包装成 DeepSeek 使用的 ComposableLambda（t62），保证 Compose 正常处理分组。
     */
    private static Object wrapComposableLambda(Object lambda) {
        if (sComposableLambdaCtor == null) {
            return lambda;
        }
        try {
            return sComposableLambdaCtor.newInstance(
                    lambda, Boolean.FALSE, Integer.valueOf(0x3F3F3F3F));
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 包装 ComposableLambda 失败: " + t);
            return lambda;
        }
    }

    /**
     * 生成标题 lambda：内部调用 DeepSeek 自己的文本 Composable 渲染「DEKK模块」。
     */
private static Object createTitleLambda(ClassLoader classLoader) {
        return createTextLambda(classLoader, ENTRY_TITLE);
    }
    /**
     * 生成文本 lambda：内部调用 DeepSeek 自己的文本 Composable。
     */
    private static Object createTextLambda(ClassLoader classLoader, final String text) {
        if (classLoader == null || sFunction2 == null || sTextMethod == null) {
            return null;
        }
        Object lambda = Proxy.newProxyInstance(classLoader, new Class<?>[]{sFunction2}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                // Proxy 会把 equals/hashCode/toString 也转到这里；返回 null 会让
                // 宿主的 boolean/int 拆箱炸掉（"Expected to unbox a 'boolean' ..."），
                // 进而把 Compose 的合成状态搞乱。这三个必须给出合法值。
                if ("equals".equals(name)) {
                    return Boolean.valueOf(args != null && args.length > 0 && proxy == args[0]);
                }
                if ("hashCode".equals(name)) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("toString".equals(name)) {
                    return text;
                }
                if (!"q".equals(name)) {
                    return null;
                }
                try {
                    Object composer = (args != null && args.length > 0) ? args[0] : null;
                    sTextMethod.invoke(null, new Object[]{
                            text,
                            null, Long.valueOf(0L),                 // Modifier / Color
                            null, Long.valueOf(0L),                 // Color / FontWeight
                            null, Long.valueOf(0L),                 // FontWeight / FontSize
                            null, Long.valueOf(0L),                 // FontFamily / ...
                            Integer.valueOf(0), Boolean.FALSE,      // fontSize / textAlign
                            Integer.valueOf(0), Integer.valueOf(0),
                            null, composer,                         // style / Composer
                            Integer.valueOf(0), Integer.valueOf(0), Integer.valueOf(0x3FFFE)});
                } catch (Throwable t) {
                    HotBridge.log("[DEKK] 渲染文案「" + text + "」失败: " + t);
                }
                return null;
            }
        });
        return wrapComposableLambda(lambda);
    }

    /**
     * 生成点击回调 lambda：打开注入的「DEKK设置页面」。
     */
    private static Object createClickLambda(ClassLoader classLoader) {
        Class<?> function0;
        try {
            function0 = HotHelpers.findClass(CLASS_FUNCTION0, classLoader);
        } catch (Throwable t) {
            return null;
        }
        return Proxy.newProxyInstance(classLoader, new Class<?>[]{function0}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                // 同 createTextLambda：Object 的三个方法必须给合法值，否则宿主拆箱崩。
                if ("equals".equals(name)) {
                    return Boolean.valueOf(args != null && args.length > 0 && proxy == args[0]);
                }
                if ("hashCode".equals(name)) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("toString".equals(name)) {
                    return "dekk-entry-click";
                }
                if ("v".equals(name)) {
                    DekkSettingsPage.show();
                }
                return null;
            }
        });
    }

    /**
     * 找到 z89->b(文本 Composable) 方法。
     */
    private static Method findTextMethod(Class<?> textClass) {
        Method[] methods = textClass.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            if ("b".equals(method.getName())
                    && method.getParameterTypes().length == TEXT_METHOD_PARAM_COUNT) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }
}
