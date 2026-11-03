package com.dekk.yanyu;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 热更新壳的 Xposed 入口（assets/xposed_init 指向这里）。
 *
 * <p>壳里不放任何功能代码，顺序永远是：
 * <ol>
 *   <li>本地有 dex 缓存 → 主线程直接加载（纯文件操作，不联网）；</li>
 *   <li>本地没有 → {@link HotDex} 在工作线程里下载、校验、落盘，再回调回来加载
 *       （主线程联网会 NetworkOnMainThreadException，宿主 targetSdk 很高）；</li>
 *   <li>dex 装好后反射调用 {@code com.dekk.yanyu.DekkEntry.install(宿主的 ClassLoader)} 去装 hook。</li>
 * </ol>
 *
 * <p>因此以后改功能只要重新上传 dex，用户不用再下载模块；换了 dex 之后宿主要重启一次才生效。
 */
public class HotEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        // 模块自身：把界面上的「已激活」点亮
        if (BuildConfig.APPLICATION_ID.equals(lpparam.packageName)) {
            XposedHelpers.findAndHookMethod("com.dekk.yanyu.MainActivity", lpparam.classLoader,
                    "isModuleActivated", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!HotConfig.HOST.equals(lpparam.packageName)) return;
        // 先装「更新提示 + 自动重启」的钩子，再加载 payload
        HotUpdate.install(lpparam.classLoader);

        // ── 防检测必须和完整包一样「在入口同步装好」──
        // 之前这段只存在于 payload（DekkEntry）里：dex 首次下载/更新期间是裸奔的，
        // 数美在启动阶段就能看到 Xposed/Magisk/包名痕迹。现在壳自己先装一遍，
        // payload 里那份靠 System.property 标记自动跳过，不会重复挂钩。
        try { AntiDetect.install(lpparam.classLoader); } catch (Throwable ignored) { }
        try { SignatureMask.install(lpparam.classLoader); } catch (Throwable ignored) { }

        start(lpparam);
    }

    /** 先拿 dex（缓存命中走主线程，需要下载走工作线程），拿到后再装 hook。 */
    private static void start(final LoadPackageParam lpparam) {
        String dataDir = null;
        try {
            if (lpparam.appInfo != null) dataDir = lpparam.appInfo.dataDir;
        } catch (Throwable ignored) { }

        final String process = lpparam.processName;
        final ClassLoader host = lpparam.classLoader;
        HotDex.start(dataDir, host, new HotDex.Ready() {
            @Override
            public void onReady(ClassLoader payload) {
                installPayload(payload, host, process);
            }
        });
    }

    /** 反射让 dex 里的 DekkEntry 去装全部 hook。 */
    private static void installPayload(ClassLoader payload, ClassLoader host, String process) {
        diagnose(payload, host);
        try {
            Class<?> entry = Class.forName(HotConfig.ENTRY_CLASS, true, payload);
            entry.getMethod(HotConfig.ENTRY_METHOD, ClassLoader.class).invoke(null, host);
            HotLog.i("payload 已接管（进程 " + process + "）");
        } catch (ClassNotFoundException e) {
            HotLog.i("dex 里没有 " + HotConfig.ENTRY_CLASS);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 反射调用会把真正的异常包一层，必须拆开打，否则只能看到 InvocationTargetException
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            HotLog.i("payload 启动失败：" + cause);
            HotLog.i(cause);
        } catch (Throwable t) {
            HotLog.i("payload 启动失败：" + t);
            HotLog.i(t);
        }
    }

    /**
     * 诊断：确认 payload 用的是**壳里的门面实现**。
     *
     * <p>payload 自己不能直接引用 de.robv.android.xposed.*（LSPosed 会改名，原始 dex 解析不到），
     * 所以 payload 统一走 com.dekk.yanyu.Hot* 门面；热更新时必须让 payload 解析到**壳的**那几份
     * （父加载器优先），由壳去调真 API —— 这是整套热更新能成立的关键。
     */
    private static void diagnose(ClassLoader payload, ClassLoader host) {
        HotLog.i("诊断：payload=" + payload + " 父=" + payload.getParent());
        try {
            Class<?> bridge = Class.forName("com.dekk.yanyu.HotBridge", false, payload);
            boolean fromShell = bridge.getClassLoader() == HotBridge.class.getClassLoader();
            HotLog.i("诊断：门面 HotBridge 来自 " + bridge.getClassLoader()
                    + (fromShell ? "（壳，正确）" : "（payload 自带那份，API 调用会失败）"));
        } catch (Throwable t) {
            HotLog.i("诊断：payload 找不到 HotBridge 门面：" + t);
        }
    }
}
