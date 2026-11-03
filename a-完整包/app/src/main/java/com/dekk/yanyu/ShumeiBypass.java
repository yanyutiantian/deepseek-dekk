package com.dekk.yanyu;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;


import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;

/**
 * 数美风控（libsmsdk.so / com.ishumei.smantifraud）过检测 —— Java 层。
 *
 * <p><b>来源</b>：用户提供的 {@code com.dsmod.smsbypass.Main}（1426 行）整体移植，逻辑保持原样，
 * 只做三处适配：
 * <ol>
 *   <li>{@code NativePatch} → {@link SmsNative}（原包里没有这个类，只有 native 补丁的核心逻辑，
 *       JNI 桥是按 {@code smspatch_core.c} 的说明补齐的，另配了下载/缓存加载器）；</li>
 *   <li>{@code TraceLog} → {@link ShumeiTrace}（原版写文件，改成只走日志并限量）；</li>
 *   <li>{@code handleLoadPackage} → {@link #install(ClassLoader)}，接到 {@code DekkEntry} 的 step 上。</li>
 * </ol>
 *
 * <p><b>2026-10-28 关键修正</b>：原移植版直接用了 {@code de.robv.android.xposed.*}（27 处
 * {@code XC_MethodHook}、16 段 {@code XC_MethodReplacement}、24 处 {@code XposedBridge.hookMethod}）。
 * 完整包（LSPosed 会重写 dex）没问题，但**热更新包加载的是原始 dex**，这些类名在运行时不存在，
 * 全部抛 {@code NoClassDefFoundError} 被 catch 吞掉 —— 表现就是「数美过检测看着装了、实际没生效、照样封号」。
 * 现已整体换成门面：{@code HotBridge} / {@code HotHook} / {@code HotParam}，行为完全不变，
 * 但只依赖壳里那份会被 LSPosed 重写的门面类。改这块时别再写回真 Xposed API。
 *
 * <p><b>三块真正参与过检测的逻辑（全部照搬）</b>：
 * <ul>
 *   <li>{@link #installRiskSdkNeutralizer} —— {@code SmAntiFraud} 的 getDeviceId/getVData、
 *       smid 存储类 {@code l11l11I1IIIl}、检测类 {@code l1111l111111Il} 的反射循环桩化，
 *       外加 android_id / OAID 伪装；</li>
 *   <li>{@link #installSmsdkForge} —— native 补丁（root 检测 + 90 个标记扫描器）+
 *       {@code SMSDK.ma/ma2} 置否 + {@code v1/w1} 的明文风险 JSON 矫正 + 其余方法只观察；</li>
 *   <li>{@link #installAntiDetectionHooks} —— 调试器探针、su 路径探针、散落的代理/build.prop 探针。</li>
 * </ul>
 *
 * <p><b>另外六块是原作者的开发期诊断采集</b>（TLS 明文抓包、Activity、Toast、字符串资源、
 * 全包 trace、广播手动上报），跟过检测无关，而且 hook Conscrypt 有把 TLS 搞挂的风险，
 * 所以统一收进 {@link #DIAG_CAPTURE}，默认关闭。要复现原版的抓包能力把它改 true 即可。
 */
final class ShumeiBypass {

    private static final String TAG = "dekk-sms";

    // ── 开关 ─────────────────────────────────────────────────────────────
    /**
     * 诊断采集总开关（原版默认开，这里是默认关）。
     * 打开会 hook Conscrypt 的 SSL_read/SSL_write 抓明文、Activity 生命周期、Toast、
     * Resources.getString、以及数美 100+ 个类的每个方法——只为了看日志，不参与过检测。
     */
    static final boolean DIAG_CAPTURE = false;
    /** 挂 Runtime.loadLibrary0，libsmsdk.so 一加载就补。 */
    static final boolean NATIVE_HOOK_LOAD = true;

    /**
     * 原版的诊断开关，含义照旧：
     * <ul>
     *   <li>{@code DIAG_SKIP_DETECTOR_STUB=true} —— 跳过 {@code l1111l111111Il} 的整类桩化。
     *       原注释说这类共 ~26 个方法的返回值里有几个会破坏 SmAntiFraud 内部初始化状态机，
     *       导致登录一直转圈；作者当时的结论是跳过它、靠 native 补丁 + 风险 JSON 矫正兜底。</li>
     *   <li>{@code DIAG_FAKE_DEVICE_ID=false} —— 不伪造 smid。原作者的实测结论：
     *       本地编的 smid 服务端 22ms 就回"环境异常"，因为那是个从没被真实采集过的设备号。
     *       留真的、让补丁把"观察到的东西"变干净才对。</li>
     * </ul>
     */
    static final boolean DIAG_SKIP_DETECTOR_STUB = true;
    static final boolean DIAG_FAKE_DEVICE_ID = false;

    /** 跨 ClassLoader 防重复（和 AntiDetect 用同一套机制）。 */
    private static final String MARK = "dekk.sms.installed";

    private static boolean installed;
    private static volatile boolean nativeArmed;
    private static volatile boolean versionWarned;

    // ── 入口 ─────────────────────────────────────────────────────────────

    /**
     * 类加载信号：只要这个类进了正在跑的 dex，静态块就会打一行。
     * 用来区分「类不在运行中的 dex 里」和「类在、但 install 没被调用」。
     */
    static {
        try {
            HotBridge.log("[DEKK][数美] 类已加载（静态块）");
        } catch (Throwable ignored) {
        }
    }

    static synchronized void install(ClassLoader host) {
        log("install() 被调用，宿主编 ClassLoader=" + host);
        if (installed) return;
        if ("1".equals(System.getProperty(MARK))) {
            installed = true;
            log("壳已装过，跳过重复安装");
            return;
        }
        installed = true;

        // native 本地部分（缓存 / APK）；联网下载交给后台线程，主线程不许联网
        boolean nativeLocal = SmsNative.load(false);
        log("native 本地：" + (nativeLocal ? "命中 —— " : "未命中 —— ") + SmsNative.loadDetail());
        if (NATIVE_HOOK_LOAD) hookLibLoad();

        long t0 = System.nanoTime();

        try {
            installRiskSdkNeutralizer(host);
            log("installRiskSdkNeutralizer OK");
        } catch (Throwable t) {
            log("installRiskSdkNeutralizer 失败：" + safeMessage(t));
            ShumeiTrace.exception("installRiskSdkNeutralizer", t);
        }

        try {
            installAntiDetectionHooks(host);
            log("installAntiDetectionHooks OK");
        } catch (Throwable t) {
            log("installAntiDetectionHooks 失败：" + safeMessage(t));
            ShumeiTrace.exception("installAntiDetectionHooks", t);
        }

        if (DIAG_CAPTURE) {
            try { installUploadTrigger(host); }
            catch (Throwable t) { log("installUploadTrigger 失败：" + safeMessage(t)); }
            try { installNetworkCapture(host); }
            catch (Throwable t) { log("installNetworkCapture 失败：" + safeMessage(t)); }
            try { installActivityLifecycleCapture(host); }
            catch (Throwable t) { log("installActivityLifecycleCapture 失败：" + safeMessage(t)); }
            try { installToastCapture(host); }
            catch (Throwable t) { log("installToastCapture 失败：" + safeMessage(t)); }
            try { installStringResourceWatch(host); }
            catch (Throwable t) { log("installStringResourceWatch 失败：" + safeMessage(t)); }
            try { installShumeiGenericTrace(host); }
            catch (Throwable t) { log("installShumeiGenericTrace 失败：" + safeMessage(t)); }
        } else {
            log("诊断采集已关闭（DIAG_CAPTURE=false：TLS抓包/Activity/Toast/资源/全包trace）");
        }

        startRetry();

        System.setProperty(MARK, "1");
        log("安装完成，耗时 " + ((System.nanoTime() - t0) / 1000000L) + "ms"
                + "（native=" + (nativeArmed ? "已打上" : "等后台补") + "）");
    }

    // ── native 补丁的时机 ────────────────────────────────────────────────

    private static void hookLibLoad() {
        try {
            Class<?> runtime = Class.forName("java.lang.Runtime");
            HotBridge.hookAllMethods(runtime, "loadLibrary0", new HotHook() {
                @Override protected void afterHookedMethod(HotParam param) {
                    try {
                        Object[] args = param.args;
                        if (args == null) return;
                        for (int i = 0; i < args.length; i++) {
                            if (!(args[i] instanceof String)) continue;
                            if (((String) args[i]).indexOf("smsdk") >= 0) {
                                armNative("库加载");
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("已挂 Runtime.loadLibrary0（库一加载就打补丁）");
        } catch (Throwable t) {
            log("Runtime.loadLibrary0 挂钩失败：" + safeMessage(t));
        }
    }

    /** 库加载好之后才能改代码段；补丁幂等，重复调用只是返回"已是补丁态"。 */
    private static void armNative(String why) {
        if (nativeArmed) return;
        synchronized (ShumeiBypass.class) {
            if (nativeArmed) return;
            if (!SmsNative.loaded) return;   // 还没加载好，等后台线程
            int root;
            int choke;
            try {
                root = SmsNative.patchRootDetector();
                choke = SmsNative.patchDetectorChokepoint();
            } catch (Throwable t) {
                log("native 补丁异常（" + why + "）：" + safeMessage(t));
                return;
            }
            if (root == SmsNative.ST_VERSION || choke == SmsNative.ST_VERSION) {
                if (!versionWarned) {
                    versionWarned = true;
                    log("偏移指令校验不符：宿主 libsmsdk.so 换版本了，"
                            + "native 补丁拒绝执行（不会搞崩宿主），需要重新定位偏移");
                }
                return;
            }
            boolean rootDone = root == SmsNative.ST_PATCHED || root == SmsNative.ST_ALREADY;
            boolean chokeDone = choke == SmsNative.ST_PATCHED || choke == SmsNative.ST_ALREADY;
            if (!rootDone && !chokeDone && root == SmsNative.ST_NO_LIB) return;
            log("native 补丁[" + why + "] root=" + SmsNative.text(root)
                    + "，扫描器=" + SmsNative.text(choke));
            if (rootDone && chokeDone) {
                nativeArmed = true;
                try {
                    log("探针：" + SmsNative.probeText(SmsNative.probe())
                            + "，libsmsdk 基址 0x" + Long.toHexString(SmsNative.smsdkBase()));
                } catch (Throwable t) {
                    log("探针失败：" + safeMessage(t));
                }
            }
        }
    }

    /** 后台补 load（联网下载）和补打补丁。 */
    private static void startRetry() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                boolean netTried = false;
                for (int i = 0; i < 60 && !nativeArmed; i++) {
                    if (!SmsNative.loaded) {
                        // 只有第 1 次允许联网：下载一次就够，之后 SmsNative 自己会拦住联网，
                        // 这里再传 true 也不会反复打服务器（双保险）。
                        boolean allowNetwork = !netTried;
                        netTried = true;
                        if (SmsNative.load(allowNetwork) && !nativeArmed) {
                            log("native 加载成功：" + SmsNative.loadDetail()
                                    + (allowNetwork ? "（本次联网下载）" : "（本地）"));
                            armNative("加载成功后");
                        }
                    } else {
                        armNative("轮询#" + (i + 1));
                    }
                    if (nativeArmed) return;
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!nativeArmed) {
                    log("轮询结束仍未生效：nativeLoaded=" + SmsNative.loaded
                            + "，详情=" + SmsNative.loadDetail());
                }
            }
        }, "dekk-sms-arm");
        t.setDaemon(true);
        t.start();
    }

    // ── ① SmAntiFraud / SMSDK 中和 ───────────────────────────────────────

    private static void installRiskSdkNeutralizer(ClassLoader cl) {
        try {
            Class<?> sm = Class.forName("com.ishumei.smantifraud.SmAntiFraud", false, cl);
            log("SmAntiFraud 解析成功，声明方法数=" + sm.getDeclaredMethods().length);
            for (Method method : sm.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    log("SmAntiFraud." + method.getName() + " 跳过（非静态）");
                    continue;
                }
                String name = method.getName();
                Class<?>[] params = method.getParameterTypes();
                if ("getDeviceId".equals(name) && params.length == 0
                        && method.getReturnType() == String.class) {
                    if (DIAG_FAKE_DEVICE_ID) {
                        log("SmAntiFraud.getDeviceId 命中，安装替身");
                        replace(method, new HotHook() {
                            @Override protected void beforeHookedMethod(HotParam param) {
                                ShumeiTrace.enter("SmAntiFraud.getDeviceId", param.args);
                                String smid = riskNeutralizedSmid();
                                ShumeiTrace.exit("SmAntiFraud.getDeviceId", smid);
                                param.setResult(smid);
                            }
                        });
                    } else {
                        log("SmAntiFraud.getDeviceId 命中，只观察（DIAG_FAKE_DEVICE_ID=false）");
                        HotBridge.hookMethod(method, new HotHook() {
                            @Override protected void beforeHookedMethod(HotParam param) {
                                ShumeiTrace.enter("SmAntiFraud.getDeviceId(real)", param.args);
                            }
                            @Override protected void afterHookedMethod(HotParam param) {
                                ShumeiTrace.exit("SmAntiFraud.getDeviceId(real)", param.getResult());
                            }
                        });
                    }
                } else if ("getVData".equals(name) && params.length == 0
                        && method.getReturnType() == String.class) {
                    if (DIAG_FAKE_DEVICE_ID) {
                        log("SmAntiFraud.getVData 命中，安装替身");
                        replace(method, new HotHook() {
                            @Override protected void beforeHookedMethod(HotParam param) {
                                ShumeiTrace.enter("SmAntiFraud.getVData", param.args);
                                String v = riskNeutralizedSmid();
                                ShumeiTrace.exit("SmAntiFraud.getVData", v);
                                param.setResult(v);
                            }
                        });
                    } else {
                        log("SmAntiFraud.getVData 命中，只观察（DIAG_FAKE_DEVICE_ID=false）");
                        HotBridge.hookMethod(method, new HotHook() {
                            @Override protected void beforeHookedMethod(HotParam param) {
                                ShumeiTrace.enter("SmAntiFraud.getVData(real)", param.args);
                            }
                            @Override protected void afterHookedMethod(HotParam param) {
                                ShumeiTrace.exit("SmAntiFraud.getVData(real)", param.getResult());
                            }
                        });
                    }
                } else if ("startDetector".equals(name) || "stopDetector".equals(name)) {
                    // 故意不挂：它们守着真正的 采集→加密→上报 链路，
                    // 那条链路必须真跑，才能产出真实被云端登记过的 smid
                    log("SmAntiFraud." + name + " 故意不挂（真实链路的闸门）");
                } else {
                    log("SmAntiFraud." + name + " 未命中任何规则，保持原样");
                }
            }

            try {
                installSmsdkForge(cl);
            } catch (Throwable t) {
                log("SMSDK forge 失败：" + safeMessage(t));
                ShumeiTrace.exception("installSmsdkForge", t);
            }

            try {
                Class<?> store = Class.forName("com.ishumei.smantifraud.l11l11I1IIIl", false, cl);
                log("smid 存储类解析成功，方法数=" + store.getDeclaredMethods().length);
                for (Method method : store.getDeclaredMethods()) {
                    if (method.getReturnType() != String.class
                            || method.getParameterTypes().length != 0
                            || Modifier.isStatic(method.getModifiers())) continue;
                    String name = method.getName();
                    if (!"l111l11111Il".equals(name) && !"l1111l111111Il".equals(name)) {
                        log("smid 存储方法 " + name + " 未命中，保持原样");
                        continue;
                    }
                    final String siteName = "smidStore." + name;
                    if (DIAG_FAKE_DEVICE_ID) {
                        log("smid 存储方法 " + name + " 命中，安装替身");
                        replace(method, new HotHook() {
                            @Override protected void beforeHookedMethod(HotParam param) {
                                ShumeiTrace.enter(siteName, param.args);
                                String v = riskNeutralizedSmid();
                                ShumeiTrace.exit(siteName, v);
                                param.setResult(v);
                            }
                        });
                    } else {
                        log("smid 存储方法 " + name + " 命中，只观察");
                        HotBridge.hookMethod(method, new HotHook() {
                            @Override protected void beforeHookedMethod(HotParam param) {
                                ShumeiTrace.enter(siteName + "(real)", param.args);
                            }
                            @Override protected void afterHookedMethod(HotParam param) {
                                ShumeiTrace.exit(siteName + "(real)", param.getResult());
                            }
                        });
                    }
                }
            } catch (Throwable t) {
                log("smid 存储类不可用：" + safeMessage(t));
            }

            if (DIAG_SKIP_DETECTOR_STUB) {
                log("检测类整类桩化已跳过（DIAG_SKIP_DETECTOR_STUB=true）");
            } else {
                stubDetectorClass(cl);
            }

            log("SmAntiFraud 中和完成");

            try {
                hookAndroidIdSpoof(cl);
            } catch (Throwable t) {
                log("Android ID 伪装失败：" + safeMessage(t));
            }
        } catch (Throwable t) {
            log("SmAntiFraud 中和不可用：" + safeMessage(t));
        }
    }

    /** {@code com.ishumei.smantifraud.l1111l111111Il}：所有静态方法按返回类型桩化。 */
    private static void stubDetectorClass(ClassLoader cl) {
        try {
            Class<?> detector = Class.forName("com.ishumei.smantifraud.l1111l111111Il", false, cl);
            log("检测类解析成功，方法数=" + detector.getDeclaredMethods().length);
            for (Method method : detector.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    log("detector." + method.getName() + " 跳过（非静态）");
                    continue;
                }
                Class<?> rt = method.getReturnType();
                final String siteName = "detector." + method.getName();
                if (rt == boolean.class) {
                    log(siteName + " (boolean) -> false");
                    replace(method, new HotHook() {
                        @Override protected void beforeHookedMethod(HotParam param) {
                            ShumeiTrace.enter(siteName, param.args);
                            ShumeiTrace.exit(siteName, Boolean.FALSE);
                            param.setResult(Boolean.FALSE);
                        }
                    });
                } else if (rt == String.class) {
                    log(siteName + " (String) -> \"\"");
                    replace(method, new HotHook() {
                        @Override protected void beforeHookedMethod(HotParam param) {
                            ShumeiTrace.enter(siteName, param.args);
                            ShumeiTrace.exit(siteName, "");
                            param.setResult("");
                        }
                    });
                } else if (rt == int.class) {
                    log(siteName + " (int) -> 0");
                    replace(method, new HotHook() {
                        @Override protected void beforeHookedMethod(HotParam param) {
                            ShumeiTrace.enter(siteName, param.args);
                            ShumeiTrace.exit(siteName, Integer.valueOf(0));
                            param.setResult(Integer.valueOf(0));
                        }
                    });
                } else if (rt == long.class) {
                    log(siteName + " (long) -> 0");
                    replace(method, new HotHook() {
                        @Override protected void beforeHookedMethod(HotParam param) {
                            ShumeiTrace.enter(siteName, param.args);
                            ShumeiTrace.exit(siteName, Long.valueOf(0L));
                            param.setResult(Long.valueOf(0L));
                        }
                    });
                } else if (java.util.Map.class.isAssignableFrom(rt)) {
                    log(siteName + " (Map) -> empty");
                    replace(method, new HotHook() {
                        @Override protected void beforeHookedMethod(HotParam param) {
                            ShumeiTrace.enter(siteName, param.args);
                            ShumeiTrace.exit(siteName, Collections.emptyMap());
                            param.setResult(Collections.emptyMap());
                        }
                    });
                } else if (java.util.List.class.isAssignableFrom(rt)) {
                    log(siteName + " (List) -> empty");
                    replace(method, new HotHook() {
                        @Override protected void beforeHookedMethod(HotParam param) {
                            ShumeiTrace.enter(siteName, param.args);
                            ShumeiTrace.exit(siteName, Collections.emptyList());
                            param.setResult(Collections.emptyList());
                        }
                    });
                } else if (java.util.Set.class.isAssignableFrom(rt)) {
                    log(siteName + " (Set) -> empty");
                    replace(method, new HotHook() {
                        @Override protected void beforeHookedMethod(HotParam param) {
                            ShumeiTrace.enter(siteName, param.args);
                            ShumeiTrace.exit(siteName, Collections.emptySet());
                            param.setResult(Collections.emptySet());
                        }
                    });
                } else {
                    log(siteName + " 未匹配，返回类型=" + rt + "，保持原样");
                }
            }
        } catch (Throwable t) {
            log("检测类不可用：" + safeMessage(t));
        }
    }

    // ── ② SMSDK 生成/上报面 ──────────────────────────────────────────────

    /**
     * SMSDK 的上报面：native 补丁 + ma/ma2 置否 + v1/w1 明文风险 JSON 矫正。
     *
     * <p>风险 JSON 是关键的一处：{@code v1/w1}（11 参）的第 4 个参数是加密前就已经算好的
     * {@code {"all_atamper":true,"hook_java_switch":true,"risk_apps":[...]}}，
     * 在它进加密/压缩管线之前改掉，比 hook 它几十个方法干净得多。
     */
    private static void installSmsdkForge(ClassLoader cl) {
        try {
            // initialize=true：跑 SMSDK 的静态块，逼出 System.loadLibrary("smsdk")，
            // 否则目标库还没映射，补丁无处可打
            log("解析 SMSDK 类（初始化=true，触发 loadLibrary）");
            long tLoad0 = System.nanoTime();
            Class<?> smsdk = Class.forName("com.ishumei.smantifraud.dfp.SMSDK", true, cl);
            log("SMSDK 解析完成，耗时 " + ((System.nanoTime() - tLoad0) / 1000000L)
                    + "ms，方法数=" + smsdk.getDeclaredMethods().length);

            // 库已映射，打 native 补丁（原版这里调 NativePatch.initialize/patchXxx）
            armNative("SMSDK初始化后");

            for (Method method : smsdk.getDeclaredMethods()) {
                String name = method.getName();
                if ("ma".equals(name) && method.getParameterTypes().length == 0
                        && method.getReturnType() == boolean.class) {
                    log("SMSDK.ma 命中，安装替身");
                    replace(method, new HotHook() {
                        @Override protected void beforeHookedMethod(HotParam param) {
                            ShumeiTrace.enter("SMSDK.ma", param.args);
                            ShumeiTrace.exit("SMSDK.ma", Boolean.FALSE);
                            param.setResult(Boolean.FALSE);
                        }
                    });
                } else if ("ma2".equals(name) && method.getParameterTypes().length == 0
                        && method.getReturnType() == int.class) {
                    log("SMSDK.ma2 命中，安装替身");
                    replace(method, new HotHook() {
                        @Override protected void beforeHookedMethod(HotParam param) {
                            ShumeiTrace.enter("SMSDK.ma2", param.args);
                            ShumeiTrace.exit("SMSDK.ma2", Integer.valueOf(0));
                            param.setResult(Integer.valueOf(0));
                        }
                    });
                } else if ("v3".equals(name)) {
                    // 交给下面 5 参 v3 的专门 hook，这里不重复挂
                    log("SMSDK.v3 交给专门 hook 处理");
                } else if (("v1".equals(name) || "w1".equals(name))
                        && method.getParameterTypes().length == 11) {
                    final String forgeName = name;
                    log("SMSDK." + forgeName + " 命中（11 参真实收敛点），安装矫正 hook");
                    HotBridge.hookMethod(method, new HotHook() {
                        @Override protected void beforeHookedMethod(HotParam param) {
                            armNative("v1/w1 触发");
                            ShumeiTrace.write(">>> SMSDK." + forgeName + " args="
                                    + ShumeiTrace.describeArgs(param.args));
                            Object raw = param.args.length > 3 ? param.args[3] : null;
                            String forged = forgeRiskFlagsJson(raw);
                            if (forged != null) {
                                param.args[3] = forged;
                                log("SMSDK." + forgeName + " 风险 JSON 已矫正");
                                ShumeiTrace.write(">>> 矫正后 = " + ShumeiTrace.describeOne(forged));
                            } else {
                                log("SMSDK." + forgeName + " 风险 JSON 未矫正（形状不符）");
                            }
                        }
                        @Override protected void afterHookedMethod(HotParam param) {
                            if (param.hasThrowable()) {
                                ShumeiTrace.exception("SMSDK." + forgeName, param.getThrowable());
                            } else {
                                ShumeiTrace.write("<<< SMSDK." + forgeName + " result="
                                        + ShumeiTrace.describeOne(param.getResult()));
                            }
                        }
                    });
                } else {
                    final String obsName = name;
                    log("SMSDK." + obsName + "（参数=" + method.getParameterTypes().length
                            + "，返回=" + method.getReturnType().getSimpleName() + "）只观察");
                    try {
                        HotBridge.hookMethod(method, new HotHook() {
                            @Override protected void beforeHookedMethod(HotParam param) {
                                ShumeiTrace.write(">>> SMSDK." + obsName + " args="
                                        + ShumeiTrace.describeArgs(param.args));
                            }
                            @Override protected void afterHookedMethod(HotParam param) {
                                if (param.hasThrowable()) {
                                    ShumeiTrace.exception("SMSDK." + obsName, param.getThrowable());
                                } else {
                                    ShumeiTrace.write("<<< SMSDK." + obsName + " result="
                                            + ShumeiTrace.describeOne(param.getResult()));
                                }
                            }
                        });
                    } catch (Throwable t) {
                        log("SMSDK." + obsName + " 观察 hook 安装失败：" + safeMessage(t));
                    }
                }
            }

            // v3(Context, String json, String publicKey, String org, String appId)
            // 在 native w3 加密之前改写 box JSON（smid / root 标记）
            int v3Matches = 0;
            for (Method method : smsdk.getDeclaredMethods()) {
                if (!"v3".equals(method.getName())
                        || !Modifier.isStatic(method.getModifiers())
                        || method.getParameterTypes().length != 5) continue;
                v3Matches++;
                log("SMSDK.v3 命中，参数=" + java.util.Arrays.toString(method.getParameterTypes()));
                HotBridge.hookMethod(method, new HotHook() {
                    @Override protected void beforeHookedMethod(HotParam param) {
                        ShumeiTrace.write(">>> SMSDK.v3 context=" + ShumeiTrace.describeOne(param.args[0])
                                + " org=" + ShumeiTrace.describeOne(param.args[3])
                                + " appId=" + ShumeiTrace.describeOne(param.args[4]));
                        ShumeiTrace.write(">>> SMSDK.v3 原始 json = " + ShumeiTrace.describeOne(param.args[1]));
                        captureV3Invocation((Method) param.method, param.args[0], param.args[1],
                                param.args[2], param.args[3], param.args[4]);
                        String forged = forgeSmsdkBoxJson(param.args[1]);
                        if (forged != null) {
                            param.args[1] = forged;
                            log("SMSDK.v3 box JSON 已矫正");
                        } else {
                            log("SMSDK.v3 box JSON 未矫正（形状不符）");
                        }
                    }
                    @Override protected void afterHookedMethod(HotParam param) {
                        if (param.hasThrowable()) {
                            ShumeiTrace.exception("SMSDK.v3", param.getThrowable());
                        } else {
                            ShumeiTrace.write("<<< SMSDK.v3 result=" + ShumeiTrace.describeOne(param.getResult()));
                        }
                    }
                });
            }
            log("SMSDK.v3 hook 安装完成，命中=" + v3Matches);
            log("SMSDK forge 安装完成");
        } catch (Throwable t) {
            log("SMSDK forge 不可用：" + safeMessage(t));
            ShumeiTrace.exception("installSmsdkForge outer", t);
        }
    }

    /** 改写 box JSON：smid / 已知风险键，未知字段保留。 */
    private static String forgeSmsdkBoxJson(Object raw) {
        if (!(raw instanceof String)) return null;
        String json = ((String) raw).trim();
        if (json.length() == 0 || json.charAt(0) != '{') return null;
        try {
            JSONObject root = new JSONObject(json);
            String synthetic = riskNeutralizedSmid();
            if (root.has("smid")) root.put("smid", synthetic);
            String[] nulls = new String[] { "hookJava", "xpApp", "root", "isRoot", "magisk" };
            for (int i = 0; i < nulls.length; i++) {
                if (root.has(nulls[i])) root.put(nulls[i], JSONObject.NULL);
            }
            return root.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 改写 v1/w1 的明文风险 JSON：篡改/挂钩结论全置 false，risk_apps 清空。 */
    private static String forgeRiskFlagsJson(Object raw) {
        if (!(raw instanceof String)) return null;
        String json = ((String) raw).trim();
        if (json.length() == 0 || json.charAt(0) != '{') return null;
        try {
            JSONObject root = new JSONObject(json);
            String[] keys = new String[] { "all_atamper", "core_atamper", "hook_java_switch",
                    "hook_switch", "xposed", "root", "isRoot", "magisk", "frida", "emulator",
                    "debug", "proxy", "vpn" };
            for (int i = 0; i < keys.length; i++) {
                if (root.has(keys[i])) root.put(keys[i], Boolean.FALSE);
            }
            if (root.has("risk_apps")) root.put("risk_apps", new org.json.JSONArray());
            return root.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile String cachedSmid;

    /**
     * 造一个 smid 并在进程内复用。真机的 smid 整个会话不变（常常跨重装也不变），
     * 每次调用都换一个值对风控来说反而是更强的篡改信号。
     * 注意：这份只在 DIAG_FAKE_DEVICE_ID=true 时才会被用上（默认 false）。
     */
    private static String riskNeutralizedSmid() {
        String smid = cachedSmid;
        if (smid != null) return smid;
        synchronized (ShumeiBypass.class) {
            if (cachedSmid != null) return cachedSmid;
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            StringBuilder hex = new StringBuilder(33);
            hex.append('D');
            for (int i = 0; i < random.length; i++) {
                hex.append(Character.forDigit((random[i] >> 4) & 0xf, 16))
                        .append(Character.forDigit(random[i] & 0xf, 16));
            }
            cachedSmid = hex.toString();
            return cachedSmid;
        }
    }

    private static volatile String cachedAndroidId;

    private static String syntheticAndroidId() {
        String id = cachedAndroidId;
        if (id != null) return id;
        synchronized (ShumeiBypass.class) {
            if (cachedAndroidId != null) return cachedAndroidId;
            byte[] random = new byte[8];
            new SecureRandom().nextBytes(random);
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < random.length; i++) {
                hex.append(Character.forDigit((random[i] >> 4) & 0xf, 16))
                        .append(Character.forDigit(random[i] & 0xf, 16));
            }
            cachedAndroidId = hex.toString();
            return cachedAndroidId;
        }
    }

    private static void hookAndroidIdSpoof(ClassLoader cl) {
        try {
            Method getString = android.provider.Settings.Secure.class.getDeclaredMethod(
                    "getString", android.content.ContentResolver.class, String.class);
            HotBridge.hookMethod(getString, new HotHook() {
                @Override protected void beforeHookedMethod(HotParam param) {
                    Object key = param.args[1];
                    if ("android_id".equals(key)) {
                        String spoofed = syntheticAndroidId();
                        ShumeiTrace.write("Settings.Secure.getString(android_id) -> " + spoofed);
                        param.setResult(spoofed);
                    }
                }
            });
            log("Android ID 伪装已安装");
            try {
                hookOaidSpoof(cl);
            } catch (Throwable t) {
                log("OAID 伪装失败：" + safeMessage(t));
            }
        } catch (Throwable t) {
            log("Android ID 伪装不可用：" + safeMessage(t));
        }
    }

    private static final String[] OAID_KEYS = new String[] {
            "openudid", "clientudid", "deviceid", "android_id"
    };

    /** 只挂具体实现类，不挂接口（接口上的 hook 撤不掉）。 */
    private static void hookOaidSpoof(ClassLoader cl) {
        int count = 0;
        String[] owners = new String[] {
                "com.tencent.mmkv.MMKV",
                "android.app.SharedPreferencesImpl"
        };
        for (int oi = 0; oi < owners.length; oi++) {
            final String ownerName = owners[oi];
            try {
                Class<?> owner = "android.app.SharedPreferencesImpl".equals(ownerName)
                        ? Class.forName(ownerName)
                        : Class.forName(ownerName, false, cl);
                Method getString = owner.getDeclaredMethod("getString", String.class, String.class);
                getString.setAccessible(true);
                HotBridge.hookMethod(getString, new HotHook() {
                    @Override protected void beforeHookedMethod(HotParam param) {
                        Object key = param.args[0];
                        for (int i = 0; i < OAID_KEYS.length; i++) {
                            if (OAID_KEYS[i].equals(key)) {
                                String spoofed = syntheticAndroidId();
                                ShumeiTrace.write(ownerName + ".getString(" + OAID_KEYS[i]
                                        + ") -> " + spoofed);
                                param.setResult(spoofed);
                                return;
                            }
                        }
                    }
                });
                count++;
            } catch (Throwable error) {
                log("OAID 宿主类不可用 " + ownerName + "：" + safeMessage(error));
            }
        }
        log("OAID 具体实现 hook 数=" + count);
    }

    // ── ③ 其余检测面 ─────────────────────────────────────────────────────

    private static void installAntiDetectionHooks(ClassLoader cl) {
        try {
            hookDebuggerProbe();
        } catch (Throwable t) {
            log("调试器探针 hook 失败：" + safeMessage(t));
        }
        try {
            hookSuBinaryProbe();
        } catch (Throwable t) {
            log("su 路径探针 hook 失败：" + safeMessage(t));
        }
        // hookHookFrameworkClassBlock() 故意不调用（原作者的结论）：
        // 用 legacy Xposed API（现在是 HotBridge 门面）挂 Class.forName / ClassLoader.loadClass 会污染整个进程的
        // 类解析，把 androidx.startup 的类在 Application.onCreate 之前搞崩。
        // 走数美自己代码的框架探测已经被检测类桩化覆盖。
        try {
            hookScatteredShumeiDetectors(cl);
        } catch (Throwable t) {
            log("散落检测类 hook 失败：" + safeMessage(t));
        }
        log("anti-detection hooks 已安装");
    }

    /** 检测类桩化够不到的两个兄弟类：系统代理探针 + build.prop 泄漏（含 ro.debuggable）。 */
    private static void hookScatteredShumeiDetectors(ClassLoader cl) {
        try {
            Class<?> proxyProbe = Class.forName("com.ishumei.smantifraud.l1l11l1Illl", false, cl);
            for (Method method : proxyProbe.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        || method.getParameterTypes().length != 0
                        || method.getReturnType() != String.class) continue;
                final String siteName = "proxyProbe." + method.getName();
                replace(method, new HotHook() {
                    @Override protected void beforeHookedMethod(HotParam param) {
                        ShumeiTrace.enter(siteName, param.args);
                        ShumeiTrace.exit(siteName, "");
                        param.setResult("");
                    }
                });
                log("数美代理探针已中和：" + method.getName());
            }
        } catch (Throwable t) {
            log("数美代理探针不可用：" + safeMessage(t));
        }
        try {
            Class<?> propProbe = Class.forName("com.ishumei.smantifraud.l11l11Il11ll", false, cl);
            for (Method method : propProbe.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers())
                        || params.length != 1 || params[0] != boolean.class
                        || !java.util.Map.class.isAssignableFrom(method.getReturnType())) continue;
                final String siteName = "propProbe." + method.getName();
                replace(method, new HotHook() {
                    @Override protected void beforeHookedMethod(HotParam param) {
                        ShumeiTrace.enter(siteName, param.args);
                        java.util.Map<String, String> empty = new HashMap<String, String>();
                        ShumeiTrace.exit(siteName, empty);
                        param.setResult(empty);
                    }
                });
                log("数美 build.prop 探针已中和：" + method.getName());
            }
        } catch (Throwable t) {
            log("数美 build.prop 探针不可用：" + safeMessage(t));
        }
    }

    private static void hookDebuggerProbe() throws Throwable {
        Method isDebuggerConnected = android.os.Debug.class.getDeclaredMethod("isDebuggerConnected");
        replace(isDebuggerConnected, new HotHook() {
            @Override protected void beforeHookedMethod(HotParam param) {
                ShumeiTrace.write("Debug.isDebuggerConnected() -> false");
                param.setResult(Boolean.FALSE);
            }
        });
        Method waitingForDebugger = android.os.Debug.class.getDeclaredMethod("waitingForDebugger");
        replace(waitingForDebugger, new HotHook() {
            @Override protected void beforeHookedMethod(HotParam param) {
                ShumeiTrace.write("Debug.waitingForDebugger() -> false");
                param.setResult(Boolean.FALSE);
            }
        });
        log("调试器探针伪装已安装");
    }

    private static final String[] SU_PROBE_PATHS = new String[] {
            "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
            "/system/xbin/su", "/system/bin/su", "/system/bin/.ext/su",
            "/system/bin/failsafe/su", "/system/sd/xbin/su", "/system/usr/we-need-root/su",
            "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk", "/system/xbin/daemonsu",
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules"
    };

    /** File.exists() 只对这些已知 su / root 路径报"不存在"，其它一律放行。 */
    private static void hookSuBinaryProbe() throws Throwable {
        Method exists = java.io.File.class.getDeclaredMethod("exists");
        HotBridge.hookMethod(exists, new HotHook() {
            @Override protected void beforeHookedMethod(HotParam param) {
                Object self = param.thisObject;
                if (!(self instanceof java.io.File)) return;
                String path = ((java.io.File) self).getAbsolutePath();
                for (int i = 0; i < SU_PROBE_PATHS.length; i++) {
                    if (SU_PROBE_PATHS[i].equals(path)) {
                        ShumeiTrace.write("File.exists(" + path + ") -> false（su/root 探针路径）");
                        param.setResult(Boolean.FALSE);
                        return;
                    }
                }
            }
        });
        log("su 路径探针伪装已安装");
    }

    private static final String[] HOOK_FRAMEWORK_PREFIXES = new String[] {
            "de.robv.android.xposed.",
            "de.robvf.android.xposed.",
            "org.lsposed.",
            "io.github.lsposed.",
            "com.elderdrivers.riru.edxp.",
            "top.canyie.pine.",
            "com.swift.sandhook.",
            "com.lody.whale.",
            "me.weishu.exposed.",
            "com.rk700.riru."
    };

    private static boolean isHookFrameworkClass(Object nameArg) {
        if (!(nameArg instanceof String)) return false;
        String name = (String) nameArg;
        for (int i = 0; i < HOOK_FRAMEWORK_PREFIXES.length; i++) {
            if (name.startsWith(HOOK_FRAMEWORK_PREFIXES[i])) return true;
        }
        return false;
    }

    /**
     * 让 Class.forName / ClassLoader.loadClass 对已知框架类抛真正的 ClassNotFoundException。
     * <b>默认不调用</b>（见 {@link #installAntiDetectionHooks} 的说明），代码保留以便对照测试。
     */
    private static void hookHookFrameworkClassBlock() throws Throwable {
        int installed = 0;
        try {
            Method forName1 = Class.class.getDeclaredMethod("forName", String.class);
            HotBridge.hookMethod(forName1, new HotHook() {
                @Override protected void beforeHookedMethod(HotParam param) {
                    if (isHookFrameworkClass(param.args[0])) {
                        param.setThrowable(new ClassNotFoundException(String.valueOf(param.args[0])));
                    }
                }
            });
            installed++;
        } catch (Throwable t) {
            log("Class.forName(String) hook 失败：" + safeMessage(t));
        }
        try {
            Method forName3 = Class.class.getDeclaredMethod(
                    "forName", String.class, boolean.class, ClassLoader.class);
            HotBridge.hookMethod(forName3, new HotHook() {
                @Override protected void beforeHookedMethod(HotParam param) {
                    if (isHookFrameworkClass(param.args[0])) {
                        param.setThrowable(new ClassNotFoundException(String.valueOf(param.args[0])));
                    }
                }
            });
            installed++;
        } catch (Throwable t) {
            log("Class.forName(String,boolean,ClassLoader) hook 失败：" + safeMessage(t));
        }
        try {
            Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
            loadClass.setAccessible(true);
            HotBridge.hookMethod(loadClass, new HotHook() {
                @Override protected void beforeHookedMethod(HotParam param) {
                    if (isHookFrameworkClass(param.args[0])) {
                        param.setThrowable(new ClassNotFoundException(String.valueOf(param.args[0])));
                    }
                }
            });
            installed++;
        } catch (Throwable t) {
            log("ClassLoader.loadClass hook 失败：" + safeMessage(t));
        }
        log("hook 框架类拦截已安装=" + installed + "（注意：默认不启用）");
    }

    private static void replace(Method method, HotHook replacement) {
        try {
            HotBridge.hookMethod(method, replacement);
        } catch (Throwable t) {
            log("挂 " + method + " 失败：" + safeMessage(t));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 以下全部是原版的开发期诊断采集，默认不启用（见 DIAG_CAPTURE）
    // ═════════════════════════════════════════════════════════════════════

    private static final String ACTION_TRIGGER_UPLOAD = "com.dsmod.smsbypass.TRIGGER_UPLOAD";

    private static volatile Method capturedV3Method;
    private static volatile Object capturedV3Context;
    private static volatile String capturedV3RawJson;
    private static volatile Object capturedV3PublicKey;
    private static volatile Object capturedV3Org;
    private static volatile Object capturedV3AppId;

    /** 记住最近一次 v3 调用，供广播手动重放用（诊断采集的一部分）。 */
    private static void captureV3Invocation(Method method, Object context, Object rawJson,
            Object publicKey, Object org, Object appId) {
        if (!DIAG_CAPTURE) return;
        capturedV3Method = method;
        capturedV3Context = context;
        if (rawJson instanceof String) capturedV3RawJson = (String) rawJson;
        capturedV3PublicKey = publicKey;
        capturedV3Org = org;
        capturedV3AppId = appId;
    }

    /** 手动重放 SMSDK.v3()（box JSON 加密 + 上报），免去每次抓包都要重启 App。 */
    private static void installUploadTrigger(final ClassLoader cl) {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            HotBridge.hookMethod(attach, new HotHook() {
                @Override protected void beforeHookedMethod(HotParam param) {
                    try {
                        ShumeiTrace.init((Context) param.args[0]);
                    } catch (Throwable t) {
                        Log.e(TAG, "TraceLog.init from attach failed: " + t);
                    }
                }
                @Override protected void afterHookedMethod(HotParam param) {
                    if (param.hasThrowable()) {
                        ShumeiTrace.exception("Application.attach", param.getThrowable());
                    }
                    try {
                        registerUploadTriggerReceiver((Context) param.thisObject);
                    } catch (Throwable t) {
                        log("上报广播注册失败：" + safeMessage(t));
                    }
                }
            });
            log("Application.attach hook 已安装");
        } catch (Throwable t) {
            log("Application.attach hook 失败：" + safeMessage(t));
        }
    }

    private static void registerUploadTriggerReceiver(final Context ctx) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                triggerManualUpload();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_TRIGGER_UPLOAD);
        boolean registered = false;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Method register = Context.class.getMethod(
                        "registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class);
                register.invoke(ctx, receiver, filter, Context.RECEIVER_EXPORTED);
                registered = true;
            } catch (Throwable ignored) {
            }
        }
        if (!registered) {
            try {
                ctx.registerReceiver(receiver, filter);
                registered = true;
            } catch (Throwable t) {
                log("registerReceiver 失败：" + safeMessage(t));
            }
        }
        if (registered) {
            log("手动上报就绪：am broadcast -a " + ACTION_TRIGGER_UPLOAD);
        }
    }

    private static void triggerManualUpload() {
        ShumeiTrace.write("triggerManualUpload 收到广播");
        new Thread(new Runnable() {
            public void run() {
                Method method = capturedV3Method;
                Object context = capturedV3Context;
                String rawJson = capturedV3RawJson;
                Object publicKey = capturedV3PublicKey;
                Object org = capturedV3Org;
                Object appId = capturedV3AppId;
                if (method == null || context == null || rawJson == null) {
                    log("手动上报：还没有捕获到 SMSDK.v3() 调用");
                    return;
                }
                try {
                    String forged = forgeSmsdkBoxJson(rawJson);
                    String json = forged != null ? forged : rawJson;
                    method.setAccessible(true);
                    method.invoke(null, context, json, publicKey, org, appId);
                    log("手动上报已触发");
                } catch (Throwable t) {
                    log("手动上报失败：" + safeMessage(t));
                    ShumeiTrace.exception("triggerManualUpload", t);
                }
            }
        }).start();
    }

    private static final int NET_BODY_CAP = 8000;

    /**
     * TLS 明文抓包。挂的是 {@code com.android.org.conscrypt.NativeCrypto.SSL_write/SSL_read}。
     * 选它的理由（原作者的结论）：DeepSeek 的 dex 连第三方库都 R8 混淆了，
     * {@code Class.forName("okhttp3.OkHttpClient$Builder")} 运行时直接 ClassNotFoundException，
     * 按名字反射注入 Interceptor 根本不可能成功；而 Conscrypt 是 AOSP Mainline 模块，
     * 它的类名不在 App 的 dex 里，不会被混淆。
     */
    private static void installNetworkCapture(final ClassLoader cl) throws Throwable {
        Class<?> nativeCrypto = Class.forName("com.android.org.conscrypt.NativeCrypto", false, cl);
        int hooked = 0;
        for (Method m : nativeCrypto.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 8 || params[0] != long.class || params[4] != byte[].class
                    || params[5] != int.class || params[6] != int.class) continue;
            if ("SSL_write".equals(m.getName())) {
                HotBridge.hookMethod(m, new HotHook() {
                    @Override protected void beforeHookedMethod(HotParam param) {
                        try {
                            byte[] buf = (byte[]) param.args[4];
                            int off = (Integer) param.args[5];
                            int len = (Integer) param.args[6];
                            ShumeiTrace.write("TLS >>> SSL_write len=" + len
                                    + " data=" + decodeBytes(buf, off, len));
                        } catch (Throwable t) {
                            ShumeiTrace.write("TLS >>> SSL_write 记录失败：" + safeMessage(t));
                        }
                    }
                });
                hooked++;
            } else if ("SSL_read".equals(m.getName())) {
                HotBridge.hookMethod(m, new HotHook() {
                    @Override protected void afterHookedMethod(HotParam param) {
                        try {
                            if (param.hasThrowable()) return;
                            Object result = param.getResult();
                            int n = (result instanceof Integer) ? (Integer) result : -1;
                            if (n <= 0) return;
                            byte[] buf = (byte[]) param.args[4];
                            int off = (Integer) param.args[5];
                            ShumeiTrace.write("TLS <<< SSL_read len=" + n
                                    + " data=" + decodeBytes(buf, off, n));
                        } catch (Throwable t) {
                            ShumeiTrace.write("TLS <<< SSL_read 记录失败：" + safeMessage(t));
                        }
                    }
                });
                hooked++;
            }
        }
        log("TLS 抓包已安装（NativeCrypto），hook 数=" + hooked);

        // 兜底：上面两个是 native 方法，legacy API 挂 native 方法在部分注入方式下可能不触发，
        // 再挂 Conscrypt 两个具体 socket 实现的 Java 层 SSLInputStream/SSLOutputStream。
        String[] outerClasses = new String[] {
                "com.android.org.conscrypt.ConscryptFileDescriptorSocket",
                "com.android.org.conscrypt.ConscryptEngineSocket",
        };
        for (int i = 0; i < outerClasses.length; i++) {
            final String outer = outerClasses[i];
            try {
                Class<?> inClass = Class.forName(outer + "$SSLInputStream", false, cl);
                Class<?> outClass = Class.forName(outer + "$SSLOutputStream", false, cl);
                Method readMethod = inClass.getDeclaredMethod("read", byte[].class, int.class, int.class);
                readMethod.setAccessible(true);
                Method writeMethod = outClass.getDeclaredMethod("write", byte[].class, int.class, int.class);
                writeMethod.setAccessible(true);

                HotBridge.hookMethod(writeMethod, new HotHook() {
                    @Override protected void beforeHookedMethod(HotParam param) {
                        try {
                            byte[] buf = (byte[]) param.args[0];
                            int off = (Integer) param.args[1];
                            int len = (Integer) param.args[2];
                            ShumeiTrace.write("TLS-J[" + outer + "] >>> write len=" + len
                                    + " data=" + decodeBytes(buf, off, len));
                        } catch (Throwable ignored) {
                        }
                    }
                });
                HotBridge.hookMethod(readMethod, new HotHook() {
                    @Override protected void afterHookedMethod(HotParam param) {
                        try {
                            if (param.hasThrowable()) return;
                            Object result = param.getResult();
                            int n = (result instanceof Integer) ? (Integer) result : -1;
                            if (n <= 0) return;
                            byte[] buf = (byte[]) param.args[0];
                            int off = (Integer) param.args[1];
                            ShumeiTrace.write("TLS-J[" + outer + "] <<< read len=" + n
                                    + " data=" + decodeBytes(buf, off, n));
                        } catch (Throwable ignored) {
                        }
                    }
                });
                log("TLS 抓包（Java 层 " + outer + "）已安装");
            } catch (Throwable t) {
                log("Java 层 TLS 流 hook 失败 " + outer + "：" + safeMessage(t));
            }
        }
    }

    private static String decodeBytes(byte[] buf, int off, int len) {
        if (buf == null || len <= 0) return "";
        int cap = Math.min(len, NET_BODY_CAP);
        String s;
        try {
            s = new String(buf, off, cap, "UTF-8");
        } catch (Throwable t) {
            return "<解码失败: " + safeMessage(t) + ">";
        }
        return len > cap ? s + "...(" + len + "bytes total)" : s;
    }

    private static void installActivityLifecycleCapture(ClassLoader cl) throws Throwable {
        Class<?> activityClass = android.app.Activity.class;
        String[] names = new String[] { "onCreate", "onResume", "onPause", "onStop", "onDestroy" };
        for (int i = 0; i < names.length; i++) {
            final String name = names[i];
            Method m;
            try {
                m = "onCreate".equals(name)
                        ? activityClass.getDeclaredMethod(name, android.os.Bundle.class)
                        : activityClass.getDeclaredMethod(name);
            } catch (Throwable t) {
                log("Activity." + name + " 查找失败：" + safeMessage(t));
                continue;
            }
            HotBridge.hookMethod(m, new HotHook() {
                @Override protected void beforeHookedMethod(HotParam param) {
                    Object self = param.thisObject;
                    ShumeiTrace.write("ACT >>> " + (self != null ? self.getClass().getName() : "?")
                            + "." + name + "()");
                }
            });
        }
        log("Activity 生命周期采集已安装");
    }

    /** "环境异常" 是两个真实字符串资源的子串（0x7f0f00d1 / 0x7f0f00b5），这里连调用栈一起打出来。 */
    private static final int RES_ID_EXECUTION_ENV_EXCEPTION = 0x7f0f00d1;
    private static final int RES_ID_CREATE_PASSCODE_RISK_DEVICE = 0x7f0f00b5;

    private static void installStringResourceWatch(ClassLoader cl) throws Throwable {
        Class<?> resClass = android.content.res.Resources.class;
        int installed = 0;
        for (Method m : resClass.getDeclaredMethods()) {
            boolean isTarget = "getString".equals(m.getName()) || "getText".equals(m.getName());
            if (!isTarget) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 0 || params[0] != int.class) continue;
            HotBridge.hookMethod(m, new HotHook() {
                @Override protected void afterHookedMethod(HotParam param) {
                    try {
                        int id = (Integer) param.args[0];
                        if (id != RES_ID_EXECUTION_ENV_EXCEPTION
                                && id != RES_ID_CREATE_PASSCODE_RISK_DEVICE) return;
                        ShumeiTrace.write("RES !!!!! Resources." + param.method.getName()
                                + "(0x" + Integer.toHexString(id) + ") -> "
                                + ShumeiTrace.describeOne(param.getResult()));
                        StringBuilder st = new StringBuilder();
                        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                        for (int i = 0; i < stack.length; i++) {
                            st.append("\n    at ").append(stack[i]);
                        }
                        ShumeiTrace.write("RES !!!!! 调用栈:" + st);
                    } catch (Throwable ignored) {
                    }
                }
            });
            installed++;
        }
        log("字符串资源监视已安装，hook 数=" + installed);
    }

    private static void installToastCapture(ClassLoader cl) throws Throwable {
        Class<?> toastClass = android.widget.Toast.class;
        for (Method m : toastClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !"makeText".equals(m.getName())) continue;
            HotBridge.hookMethod(m, new HotHook() {
                @Override protected void afterHookedMethod(HotParam param) {
                    ShumeiTrace.write("TOAST: makeText(" + ShumeiTrace.describeArgs(param.args) + ")");
                }
            });
        }
        Method show = toastClass.getDeclaredMethod("show");
        HotBridge.hookMethod(show, new HotHook() {
            @Override protected void beforeHookedMethod(HotParam param) {
                Object self = param.thisObject;
                String text = null;
                try {
                    if (self instanceof android.widget.Toast) {
                        android.view.View v = ((android.widget.Toast) self).getView();
                        if (v instanceof android.widget.TextView) {
                            text = ((android.widget.TextView) v).getText().toString();
                        } else if (v != null) {
                            text = describeToastViewGroup(v);
                        }
                    }
                } catch (Throwable ignored) {
                }
                ShumeiTrace.write("TOAST >>> show() text=" + (text != null ? "\"" + text + "\"" : "?"));
            }
        });
        log("Toast 采集已安装");
    }

    private static String describeToastViewGroup(android.view.View v) {
        try {
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) v;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < vg.getChildCount(); i++) {
                    android.view.View child = vg.getChildAt(i);
                    if (child instanceof android.widget.TextView) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(((android.widget.TextView) child).getText());
                    }
                }
                return sb.length() > 0 ? sb.toString() : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 数美 100+ 个类逐个方法/构造器的只观察 trace。 */
    private static final String[] SHUMEI_TRACE_CLASSES = {
            "AbsDetector", "IServiceImp", "SubCollector", "VDataListener",
            "l1111l111111Il", "l111l11111I1l", "l111l11111Il", "l111l11111lIl", "l111l1111l1Il",
            "l111l1111lI1l", "l111l1111lIl", "l111l1111llIl", "l111l111I1l", "l111l111III1l",
            "l111l111Il1l", "l111l111lIlll", "l111l111llIl", "l111l11I1IIIl", "l111l11IlIlIl",
            "l111l11l11Ill", "l11IIIlIll", "l11l1111I11l", "l11l1111I1l", "l11l1111I1ll",
            "l11l1111Il", "l11l1111Il1l", "l11l1111Ill", "l11l1111lIIl", "l11l111I111l",
            "l11l111I11l", "l11l111Il", "l11l111l11Il", "l11l111l1I1l", "l11l111l1Il",
            "l11l111l1lll", "l11l111lI1l", "l11l111lIll", "l11l111ll11l", "l11l111ll1Il",
            "l11l111llI1l", "l11l111lll", "l11l111lllIl", "l11l11I1111l", "l11l11IIII1l",
            "l11l11IIl11l", "l11l11Il", "l11l11Il111l", "l11l11Il11l", "l11l11Il11ll",
            "l11l11Il1l", "l11l11Il1l1l", "l11l11IlIIll", "l11l11l111Il", "l11l11l11I1l",
            "l11l11l11Il", "l11l11l11lIl", "l11l11l1I11l", "l11l11l1I1l", "l11l11l1l1Il",
            "l11l11l1lI1l", "l11l11l1lIl", "l11l11l1llIl", "l11l11lI1lll", "l11l11lII1l",
            "l11l11lIl1ll", "l11l11ll1ll", "l11l1l1I1l", "l11l1l1l1Il", "l1IIIIIIIl",
            "l1l11I111l", "l1l11I111ll", "l1l11I11l", "l1l11I11l1l", "l1l11I11ll",
            "l1l11I11lll", "l1l11I1l", "l1l11I1l11l", "l1l11I1l1l", "l1l11II111l",
            "l1l11l1I1Il", "l1l11l1IIl", "l1l11l1Il", "l1l11l1Il1l", "l1l11l1Illl",
            "l1l11lI11Il", "l1l11lI11l", "l1l11lI1I1l", "l1l11lI1l", "l1l11lI1lIl",
            "l1l11lII11l", "l1l11lIIlll", "l1l11lIl", "l1l11lIl1Il", "l1l11lIll1l",
            "l1l11ll1Il", "l1l11llI1l", "l1l11llIl", "l1l11lllIl", "l1l1l111Il",
            "l1l1l11I1l", "l1l1l11Il", "l1l1l11Ill", "l1l1l1I11l", "l1l1l1I1ll",
            "l1l1l1ll", "l1l1l1ll1l", "l1l1l1lll", "l1l1l1llll",
    };

    private static String describeMember(Member m) {
        try {
            return m.getDeclaringClass().getSimpleName() + "." + m.getName();
        } catch (Throwable t) {
            return String.valueOf(m);
        }
    }

    private static final HotHook GENERIC_SHUMEI_TRACE_HOOK = new HotHook() {
        @Override protected void beforeHookedMethod(HotParam param) {
            try {
                ShumeiTrace.write("SM-TRACE >>> " + describeMember(param.method)
                        + " this=0x" + Integer.toHexString(System.identityHashCode(param.thisObject))
                        + " args=" + ShumeiTrace.describeArgs(param.args));
            } catch (Throwable ignored) {
            }
        }
        @Override protected void afterHookedMethod(HotParam param) {
            try {
                if (param.hasThrowable()) {
                    ShumeiTrace.write("SM-TRACE <<< " + describeMember(param.method)
                            + " 抛 " + safeMessage(param.getThrowable()));
                } else {
                    ShumeiTrace.write("SM-TRACE <<< " + describeMember(param.method)
                            + " -> " + ShumeiTrace.describeOne(param.getResult()));
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static final HotHook GENERIC_SHUMEI_TRACE_CTOR_HOOK = new HotHook() {
        @Override protected void afterHookedMethod(HotParam param) {
            try {
                ShumeiTrace.write("SM-TRACE <<< new " + describeMember(param.method)
                        + " this=0x" + Integer.toHexString(System.identityHashCode(param.thisObject))
                        + " args=" + ShumeiTrace.describeArgs(param.args));
            } catch (Throwable ignored) {
            }
        }
    };

    private static void installShumeiGenericTrace(ClassLoader cl) {
        int classesHooked = 0;
        int methodsHooked = 0;
        int ctorsHooked = 0;
        int failures = 0;
        for (int ci = 0; ci < SHUMEI_TRACE_CLASSES.length; ci++) {
            String fqcn = "com.ishumei.smantifraud." + SHUMEI_TRACE_CLASSES[ci];
            Class<?> clazz;
            try {
                clazz = Class.forName(fqcn, false, cl);
            } catch (Throwable t) {
                continue;
            }
            classesHooked++;
            for (Method m : clazz.getDeclaredMethods()) {
                try {
                    HotBridge.hookMethod(m, GENERIC_SHUMEI_TRACE_HOOK);
                    methodsHooked++;
                } catch (Throwable t) {
                    failures++;
                }
            }
            for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                try {
                    HotBridge.hookMethod(c, GENERIC_SHUMEI_TRACE_CTOR_HOOK);
                    ctorsHooked++;
                } catch (Throwable t) {
                    failures++;
                }
            }
        }
        log("数美全包 trace 已安装：类=" + classesHooked + "/" + SHUMEI_TRACE_CLASSES.length
                + " 方法=" + methodsHooked + " 构造=" + ctorsHooked + " 失败=" + failures);
    }

    // ── 杂项 ─────────────────────────────────────────────────────────────

    private static void log(String line) {
        HotBridge.log("[DEKK][数美] " + line);
    }

    private static String safeMessage(Throwable t) {
        try {
            String m = t.getMessage();
            return t.getClass().getSimpleName() + (m != null ? ": " + m : "");
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private ShumeiBypass() { }
}
