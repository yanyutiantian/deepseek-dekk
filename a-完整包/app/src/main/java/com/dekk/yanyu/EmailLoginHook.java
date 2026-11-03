package com.dekk.yanyu;

import android.content.Context;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 强开邮箱账号登录（宿主 2.5.1）。
 *
 * <p>逆向依据（deepseek/classes_smali 2.5.1）：
 * <ul>
 *   <li>登录页由 {@code k95.c(Lmw6;Lau9;Lld6;Lc76;Ley3;I)V} 绘制，第 2 个参数是登录方式能力接口
 *       {@code au9}（{@code a()String / b()Z / c()Z / d()Z}）；</li>
 *   <li>方法体里 {@code invoke-interface Lau9;->d()Z} 决定走哪一支：国内实现 {@code xt9}
 *       （{@code a()="CN"}）返回 {@code true}，于是只渲染手机号登录；</li>
 *   <li>所以在调用前把这个对象换成动态代理，<b>只把 {@code d()} 改成 false</b>，其余方法原样透传，
 *       登录页就出现邮箱 + 密码登录；</li>
 *   <li>但提交时的校验器 {@code xy9.a(String,boolean,boolean)} 第 2 参是<b>另一处</b>
 *       {@code au9.d()}（调用点在 mw6 / fx6，共 6 处，直接从 Compose 状态里取对象），
 *       代理 {@code k95#c} 覆盖不到 → 必须再钩校验器，否则点登录会提示
 *       「手机号码格式不正确」。见 {@link #installValidatorUnlock(ClassLoader)}。</li>
 * </ul>
 *
 * <p>开关由 {@link ForceLoginSettings#emailActive} 控制：只要「强开邮箱账号登录」打开就生效，
 * 不再看赞助身份（2026-11-01 起普通用户也能用）；开关关闭时钩子原样放行。
 */
final class EmailLoginHook {

    private static final String GATE_CLASS = "k95";
    private static final String GATE_METHOD = "c";
    private static final String FLAG_METHOD = "d";
    /** 登录标识校验：第 2 参是「手机号模式」，非纯数字走 c() 邮箱校验。 */
    private static final String VALIDATOR_CLASS = "xy9";
    private static final String VALIDATOR_METHOD = "a";

    /** 旧的代理对象缓存：Compose 每次重组都会重调，别每次都造一个 Proxy。 */
    private static final Map<Object, Object> PROXIES = new WeakHashMap<Object, Object>();
    /** 预取开关值，避免在绘制/重组风暴里反复读 SharedPreferences。 */
    private static long activeAt;
    private static boolean activeCache;

    private static volatile boolean installed;

    private EmailLoginHook() { }

    static void install(ClassLoader loader) {
        if (installed) return;
        final ClassLoader hostLoader = loader;
        try {
            Method gate = findGate(loader);
            if (gate == null) {
                HotBridge.log("[DEKK][登录] 未找到 k95#c（宿主版本不匹配，强开邮箱登录不可用）");
                return;
            }
            final Class<?> flagType = gate.getParameterTypes()[1];
            HotBridge.hookMethod(gate, new HotHook() {
                @Override
                protected void beforeHookedMethod(HotParam param) throws Throwable {
                    // 先把参数数组抓在本地：后面的 active() / Proxy 创建可能触发嵌套 hook，
                    // 壳里复用的 HotParam 会把 param.args 换掉（同 DeviceContextHook 的坑）
                    final Object[] args = param.args;
                    try {
                        if (args == null || args.length < 2) return;
                        Context context = HotApp.currentApplication();
                        if (context == null || !active(context)) return;
                        Object original = args[1];
                        if (original == null) return;
                        if (Proxy.isProxyClass(original.getClass())) return;
                        Object proxy = proxyFor(hostLoader, flagType, original);
                        if (proxy != null) args[1] = proxy;
                    } catch (Throwable t) {
                        HotBridge.log("[DEKK][登录] 地区能力替换失败，保持原样: " + t);
                    }
                }
            });
            installed = true;
            HotBridge.log("[DEKK][登录] 强开邮箱登录已安装（k95#c / 地区能力 d()=false）");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][登录] 强开邮箱登录安装失败: " + t);
        }
        installValidatorUnlock(loader);
    }

    /** 开关：只看「强开邮箱账号登录」是否开启。带 1.5 秒缓存，重组时不会狂读磁盘。 */
    private static boolean active(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - activeAt < 1500L) return activeCache;
        activeAt = now;
        activeCache = ForceLoginSettings.emailActive(context);
        return activeCache;
    }

    private static Object proxyFor(ClassLoader loader, Class<?> flagType, Object original) {
        synchronized (PROXIES) {
            Object cached = PROXIES.get(original);
            if (cached != null) return cached;
            ClassLoader target = flagType.getClassLoader() != null ? flagType.getClassLoader() : loader;
            Object proxy = Proxy.newProxyInstance(target, new Class<?>[]{flagType},
                    new RegionProxy(original));
            PROXIES.put(original, proxy);
            return proxy;
        }
    }

    /** 只改 d()，其余（a/b/c/equals/hashCode/toString）原样转发给宿主对象。 */
    private static final class RegionProxy implements InvocationHandler {
        private final WeakReference<Object> target;

        RegionProxy(Object target) {
            this.target = new WeakReference<Object>(target);
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object real = target.get();
            if (real == null) return defaultValue(method);
            if (FLAG_METHOD.equals(method.getName())
                    && (args == null || args.length == 0)
                    && method.getReturnType() == boolean.class) {
                return Boolean.FALSE;            // 邮箱登录分支
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }

        private static Object defaultValue(Method method) {
            Class<?> type = method.getReturnType();
            if (type == boolean.class) return Boolean.FALSE;
            if (type == int.class) return Integer.valueOf(0);
            if (type == long.class) return Long.valueOf(0L);
            if (type == float.class) return Float.valueOf(0f);
            if (type == double.class) return Double.valueOf(0d);
            return null;
        }
    }

    /** 按签名找目标方法：静态、名为 c、6 个参数、第 2 个是带 d()Z 的接口。 */
    private static Method findGate(ClassLoader loader) {
        Class<?> clazz;
        try {
            clazz = HotHelpers.findClass(GATE_CLASS, loader);
        } catch (Throwable missing) {
            return null;
        }
        Method[] methods = clazz.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            if (!GATE_METHOD.equals(method.getName())) continue;
            if (!Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 6) continue;
            if (!types[1].isInterface()) continue;
            if (hasFlagMethod(types[1])) return method;
        }
        return null;
    }

    private static boolean hasFlagMethod(Class<?> type) {
        try {
            Method method = type.getMethod(FLAG_METHOD);
            return method.getParameterTypes().length == 0 && method.getReturnType() == boolean.class;
        } catch (Throwable missing) {
            return false;
        }
    }

    /**
     * 提交校验：{@code xy9.a(String,boolean,boolean)V/ym4}。
     *
     * <p>第 2 个参数是「是否手机号模式」，调用点（mw6 / fx6 共 6 处）全都传
     * {@code au9.d()} —— 也就是**另一处**读地区对象的地方，跟登录页绘制那次不是同一个引用，
     * 所以只代理 {@code k95#c} 的话：界面出来了，但点「登录」仍按手机号校验 →
     * 邮箱被判「手机号码格式不正确」。
     *
     * <p>这里把第 2 参强制 false（等价国际版）：
     * <ul>
     *   <li>纯数字 → 内部仍走 {@code b()} 手机号校验，短信/手机号登录不受影响；</li>
     *   <li>非纯数字（邮箱）→ 走 {@code c()} 邮箱校验，正常通过。</li>
     * </ul>
     */
    private static void installValidatorUnlock(ClassLoader loader) {
        try {
            Class<?> clazz = HotHelpers.findClass(VALIDATOR_CLASS, loader);
            Method[] methods = clazz.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                Method method = methods[i];
                if (!VALIDATOR_METHOD.equals(method.getName())) continue;
                if (!Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 3 || types[0] != String.class || types[1] != boolean.class) continue;
                HotBridge.hookMethod(method, new HotHook() {
                    @Override
                    protected void beforeHookedMethod(HotParam param) throws Throwable {
                        final Object[] args = param.args;
                        try {
                            if (args == null || args.length < 2) return;
                            Context context = HotApp.currentApplication();
                            if (context == null || !active(context)) return;
                            args[1] = Boolean.FALSE;
                        } catch (Throwable t) {
                            HotBridge.log("[DEKK][登录] 校验参数改写失败（放行原逻辑）: " + t);
                        }
                    }
                });
                HotBridge.log("[DEKK][登录] 邮箱校验已放行（xy9#a 第 2 参强制 false）");
                return;
            }
            HotBridge.log("[DEKK][登录] 未找到 xy9#a 校验器（邮箱可能仍被判手机号格式）");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][登录] 校验器解锁安装失败: " + t);
        }
    }
}
