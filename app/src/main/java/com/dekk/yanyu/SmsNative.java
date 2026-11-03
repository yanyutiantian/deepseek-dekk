package com.dekk.yanyu;

import android.content.Context;
import android.os.Build;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * libdekkcore.so 的加载器 + native 方法门面（数美 libsmsdk.so 补丁用）。
 *
 * <p>为什么 native 必须自己带一个 .so：数美有一半检测在 native 里（直接 access() 探 su、
 * 读 /proc、枚举线程/fd），Java 层 hook 根本看不到。要么改它自己的代码段，要么就没辙。
 *
 * <p><b>加载顺序（2026-10-24 真机实测后定的）</b>：
 * <ol>
 *   <li><b>本地缓存</b> {@code <宿主私有目录>/dknative/libdekkcore.so}，sha256 对得上就直接 load
 *       —— 冷启动稳定态走这条，不联网、不碰 APK；</li>
 *   <li>{@code System.loadLibrary}（LSPosed 一般没给模块配 native 目录，但便宜，试一下不亏）；</li>
 *   <li>从<b>模块 APK</b> 里抠（ClassLoader.toString / 反射 dexElements / /proc/self/maps /
 *       PackageManager 四条路找 apk，再从 apk 同级 lib/ 或 zip 条目里取）——
 *       实测这四条路在本机全灭（LSPosed 的模块 ClassLoader 里没有 apk 路径），
 *       留着只是因为换个环境/换台机器可能就通了，失败也没成本；</li>
 *   <li><b>从服务器下载</b> {@link #SO_URL_HTTPS} —— 主路径。校验 sha256 后才落地，
 *       先写 .tmp 再 rename，不会出现半个 so 被下次冷启动捡到；</li>
 * </ol>
 *
 * <p><b>线程约束</b>：下载不能放主线程（宿主 targetSdk 高会抛 NetworkOnMainThreadException），
 * 所以 {@link #load(boolean)} 的 allowNetwork 只在后台轮询线程里为 true。
 *
 * <p>注意：偏移是 arm64 专用的，32 位直接跳过（{@link #isArm64()}）。
 */
final class SmsNative {

    static final String LIB = "dekkcore";
    private static final String SO_NAME = "libdekkcore.so";
    private static final String ASSET = "dekkcore_arm64.bin";
    private static final String MODULE_PKG = "com.dekk.yanyu";

    // ── 下载源 ───────────────────────────────────────────────────────────
    /** 主地址（与热更新 dex 同目录）。 */
    private static final String SO_URL_HTTPS = "https://dekk.example.com/dex/" + SO_NAME;
    /** TLS 出问题（设备时间错、证书链）时降级用。 */
    private static final String SO_URL_HTTP = "http://dekk.example.com/dex/" + SO_NAME;
    /** 下载上限，防止被灌大文件。 */
    private static final int MAX_BYTES = 1 << 20;

    /**
     * libdekkcore.so 的 sha256。下载和内嵌两条路都会核对，
     * 对不上就不落地、不加载 —— 半个 so 或旧 so 加载进宿主是会崩的。
     * 重新编译 so 之后必须同步这里，build.sh 会替你核对。
     */
    static final String SO_SHA256 =
            "aeb1066c2e7759d635f0b61fd00d4f01cfd39a3d2537f71dfa501127ebbcfbfa";

    /** 保留从模块 APK 里抠的路子（本机不通，留着不亏）。 */
    static final boolean USE_MODULE_APK = true;

    private static final String[] ZIP_ENTRIES = new String[] {
            "lib/arm64-v8a/" + SO_NAME,
            "lib/arm64/" + SO_NAME,
            "libs/arm64-v8a/" + SO_NAME,
            "assets/" + ASSET,
            ASSET,
    };

    /** 补丁状态码，和 dkcore.c 里的 #define 一一对应。 */
    static final int ST_PATCHED = 1;
    static final int ST_ALREADY = 0;
    static final int ST_NO_LIB = -1;
    static final int ST_VERSION = -2;
    static final int ST_MPROTECT = -3;
    static final int ST_UNSUPPORTED = -4;

    /** probe() 位掩码。 */
    static final int PB_MAPPED = 1;
    static final int PB_ROOT_OK = 1 << 1;
    static final int PB_CHOKE_OK = 1 << 2;
    static final int PB_ICACHE = 1 << 3;
    static final int PB_GUARD_OK = 1 << 4;

    /** 是否加载成功（ShumeiBypass 用它判断要不要打发）。 */
    static boolean loaded;
    private static String detail = "未加载";
    private static String fail = "";

    /**
     * 「文件在、但 dlopen 失败」：说明不是缺文件的问题（SELinux / 架构 / 依赖），
     * 再下载一万次也没用 —— 置位后不再联网，只看本地。
     */
    private static volatile boolean cacheUnusable;
    /** 本进程是否已经尝试过下载（一个进程最多一次，绝不连环打服务器）。 */
    private static volatile boolean downloadTried;
    /** ②③（System.loadLibrary / 翻 APK）本进程只做一次，轮询里不重复扫 /proc。 */
    private static volatile boolean localProbed;
    /** 两次下载尝试的最小间隔（跨进程/跨启动也生效）。 */
    private static final long DOWNLOAD_INTERVAL_MS = 30L * 60L * 1000L;
    private static final String PREF = "dekk_prefs";
    private static final String KEY_DL_AT = "sms_so_dl_at";
    private static final String KEY_DL_SHA = "sms_so_dl_sha";

    static native int patchRootDetector();

    static native int patchDetectorChokepoint();

    static native int patchAll();

    static native int probe();

    static native long smsdkBase();

    static native String nativeVersion();

    /** 只做本地（缓存 / APK / 内嵌可能涉及网络，这里不给网络）。 */
    static boolean load() {
        return load(false);
    }

    static synchronized boolean load(boolean allowNetwork) {
        if (loaded) return true;
        fail = "";
        if (!isArm64()) {
            detail = "非 arm64-v8a，跳过 native 补丁";
            return false;
        }

        File dir = writableDir();

        // ① 本地缓存：文件在、sha 对得上就直接 load。
        //    注意：load 失败只说明「加载不了」，不是「文件不对」—— 这时绝不回头再下载，
        //    否则每次轮询都会打一次服务器（之前每秒 2 次请求就是这里漏了闸）。
        if (dir != null) {
            File cached = new File(dir, SO_NAME);
            if (cached.isFile() && cached.length() > 0) {
                if (isGoodSo(cached)) {
                    if (loadAbsolute(cached)) {
                        detail = "本地缓存 → " + cached.getAbsolutePath();
                        return true;
                    }
                    cacheUnusable = true;
                    fail = fail + "|①本地缓存加载失败（不再下载）";
                    detail = "本地缓存存在但加载失败（" + fail + "）";
                    return false;
                }
                // 长度/校验不对 = 坏文件或换版本了：删掉，走一次下载
                if (!cached.delete()) fail = fail + "|①坏缓存删不掉";
            }
        }

        // ②③ 只在第一次尝试：这俩是本地兜底，结果不会变，轮询里没必要重复做
        if (!localProbed) {
            // ② 按库名（LSPosed 一般没配，但便宜）
            try {
                System.loadLibrary(LIB);
                loaded = true;
                detail = "System.loadLibrary(\"" + LIB + "\")";
                return true;
            } catch (Throwable t) {
                fail = fail + "|②" + t.getClass().getSimpleName();
            }

            // ③ 从模块 APK 里抠
            if (USE_MODULE_APK) {
                File apk = moduleApk();
                if (apk != null) {
                    File appDir = apk.getParentFile();
                    String[] abis = new String[] { "arm64", "arm64-v8a", "lib64", "lib" };
                    for (int i = 0; i < abis.length; i++) {
                        File cand = new File(new File(new File(appDir, "lib"), abis[i]), SO_NAME);
                        if (isGoodSo(cand) && loadAbsolute(cand)) {
                            detail = "模块 lib 目录 → " + cand.getAbsolutePath();
                            return true;
                        }
                    }
                    if (dir != null && extractFromApk(apk, dir)) return true;
                }
            }
            localProbed = true;
        }

        // ④ 服务器下载（主路径，只能在后台线程）
        if (allowNetwork && dir != null) {
            if (cacheUnusable) {
                fail = fail + "|④本地加载不了，跳过下载";
            } else if (!downloadAllowed(HotApp.currentApplication())) {
                fail = fail + "|④下载已尝试过，跳过";
            } else if (downloadTo(dir)) {
                return true;
            }
        } else if (allowNetwork && dir == null) {
            fail = fail + "|④无可写目录";
        } else if (!allowNetwork) {
            fail = fail + "|④只允许本地";
        }

        detail = "加载失败（" + fail + "）";
        return false;
    }

    // ── 下载 ────────────────────────────────────────────────────────────

    /**
     * 联网下载的闸门：本进程最多一次；跨启动 30 分钟内最多一次；
     * so 版本（{@link #SO_SHA256}）变了自动重新放行。
     */
    private static boolean downloadAllowed(Context ctx) {
        synchronized (SmsNative.class) {
            if (downloadTried) return false;
            if (ctx != null) {
                try {
                    android.content.SharedPreferences p =
                            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                    String sha = p.getString(KEY_DL_SHA, "");
                    long at = p.getLong(KEY_DL_AT, 0L);
                    if (SO_SHA256.equals(sha) && at > 0L
                            && System.currentTimeMillis() - at < DOWNLOAD_INTERVAL_MS) {
                        return false;
                    }
                    p.edit().putString(KEY_DL_SHA, SO_SHA256)
                            .putLong(KEY_DL_AT, System.currentTimeMillis()).apply();
                } catch (Throwable ignored) {
                    // 拿不到 prefs 就只靠进程内标记
                }
            }
            downloadTried = true;
            return true;
        }
    }

    private static boolean downloadTo(File dir) {
        String[] urls = new String[] { SO_URL_HTTPS, SO_URL_HTTP };
        for (int i = 0; i < urls.length; i++) {
            String url = urls[i];
            try {
                byte[] data = httpGet(url);
                if (data == null || data.length < 1024) {
                    fail = fail + "|④" + shortUrl(url) + "太小(" + (data == null ? 0 : data.length) + ")";
                    continue;
                }
                String got = sha256(data);
                if (!SO_SHA256.equalsIgnoreCase(got)) {
                    fail = fail + "|④" + shortUrl(url) + "sha不符(" + got + ")";
                    continue;
                }
                File tmp = new File(dir, SO_NAME + ".tmp");
                File target = new File(dir, SO_NAME);
                OutputStream out = null;
                try {
                    out = new FileOutputStream(tmp);
                    out.write(data);
                    out.flush();
                } finally {
                    closeQuietly(out);
                }
                if (tmp.length() != data.length) {
                    fail = fail + "|④写盘不完整";
                    tmp.delete();
                    continue;
                }
                // 先删旧再改名，renameTo 目标已存在时行为不保证
                if (target.exists() && !target.delete()) {
                    fail = fail + "|④旧文件删不掉";
                    tmp.delete();
                    continue;
                }
                if (!tmp.renameTo(target)) {
                    fail = fail + "|④改名失败";
                    tmp.delete();
                    continue;
                }
                target.setReadable(true, false);
                target.setExecutable(true, false);
                if (loadAbsolute(target)) {
                    detail = "服务器下载 " + shortUrl(url) + " → " + target.getAbsolutePath()
                            + "（" + data.length + " 字节，sha256 校验通过）";
                    return true;
                }
                cacheUnusable = true;
                fail = fail + "|④下载后加载失败（不再重试下载）";
            } catch (Throwable t) {
                fail = fail + "|④" + shortUrl(url) + brief(t);
            }
        }
        return false;
    }

    private static byte[] httpGet(String url) throws Throwable {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "dekk-native");
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(32768);
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new java.io.IOException("超过上限");
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            closeQuietly(in);
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ── 加载 / 校验 ─────────────────────────────────────────────────────

    private static boolean loadAbsolute(File f) {
        try {
            System.load(f.getAbsolutePath());
            loaded = true;
            return true;
        } catch (Throwable t) {
            fail = fail + "|load:" + brief(t);
            return false;
        }
    }

    private static boolean isGoodSo(File f) {
        if (f == null || !f.isFile()) return false;
        if (f.length() <= 0 || f.length() > MAX_BYTES) return false;
        return SO_SHA256.equalsIgnoreCase(sha256(readAll(f)));
    }

    private static byte[] readAll(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) f.length() + 16);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static String sha256(byte[] data) {
        if (data == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (int i = 0; i < d.length; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xf, 16));
                sb.append(Character.forDigit(d[i] & 0xf, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 找可写且允许 dlopen 的目录：优先宿主私有目录，退而求其次 java.io.tmpdir。 */
    private static File writableDir() {
        try {
            Context ctx = HotApp.currentApplication();
            if (ctx != null) {
                File d = ctx.getDir("dknative", 0);
                if (d != null && (d.isDirectory() || d.mkdirs())) return d;
            }
        } catch (Throwable ignored) {
        }
        try {
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp != null) {
                File d = new File(tmp, "dknative");
                if (d.isDirectory() || d.mkdirs()) {
                    File probe = new File(d, ".w");
                    if (probe.createNewFile() || probe.isFile()) {
                        probe.delete();
                        return d;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ── 模块 APK 发现（本机不通，留作兜底） ─────────────────────────────

    private static File moduleApk() {
        File f = apkFromLoaderString();
        if (f == null) f = apkFromDexElements();
        if (f == null) f = apkFromProcMaps();
        if (f == null) f = apkFromPackageManager();
        return f;
    }

    private static File apkFromLoaderString() {
        try {
            ClassLoader cl = SmsNative.class.getClassLoader();
            if (cl == null) return null;
            String s = String.valueOf(cl);
            int idx = s.indexOf("zip file \"");
            if (idx < 0) return null;
            int start = idx + "zip file \"".length();
            int end = s.indexOf('"', start);
            if (end <= start) return null;
            File f = new File(s.substring(start, end));
            return f.isFile() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File apkFromDexElements() {
        try {
            ClassLoader cl = SmsNative.class.getClassLoader();
            if (cl == null) return null;
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            Object pathList = readField(cl, base, "pathList");
            if (pathList == null) return null;
            Object elementsObj = readField(pathList, pathList.getClass(), "dexElements");
            if (!(elementsObj instanceof Object[])) return null;
            Object[] elements = (Object[]) elementsObj;
            String[] names = new String[] { "path", "zip", "file", "dir" };
            for (int i = 0; i < elements.length; i++) {
                Object el = elements[i];
                if (el == null) continue;
                for (int j = 0; j < names.length; j++) {
                    Object v = readField(el, el.getClass(), names[j]);
                    if (v instanceof File) {
                        File f = (File) v;
                        if (f.getPath().endsWith(".apk") && f.isFile()) return f;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File apkFromProcMaps() {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new FileInputStream("/proc/self/maps")));
            String line;
            while ((line = r.readLine()) != null) {
                int idx = line.indexOf(MODULE_PKG);
                if (idx < 0) continue;
                int end = line.indexOf(".apk");
                if (end < 0) continue;
                int start = line.lastIndexOf('/', end);
                if (start < 0 || start >= end) continue;
                File f = new File(line.substring(start, end + 4));
                if (f.isFile()) return f;
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(r);
        }
        return null;
    }

    private static File apkFromPackageManager() {
        try {
            Context ctx = HotApp.currentApplication();
            if (ctx == null) return null;
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(MODULE_PKG, 0);
            if (ai == null || ai.sourceDir == null) return null;
            File f = new File(ai.sourceDir);
            return f.isFile() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object readField(Object target, Class<?> start, String name) {
        Class<?> c = start;
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    private static boolean extractFromApk(File apk, File dir) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            ZipEntry entry = null;
            for (int i = 0; i < ZIP_ENTRIES.length; i++) {
                ZipEntry e = zip.getEntry(ZIP_ENTRIES[i]);
                if (e != null) {
                    entry = e;
                    break;
                }
            }
            if (entry == null) {
                fail = fail + "|③APK里没so";
                return false;
            }
            InputStream in = zip.getInputStream(entry);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(32768);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            } finally {
                closeQuietly(in);
            }
            byte[] data = bo.toByteArray();
            if (!SO_SHA256.equalsIgnoreCase(sha256(data))) {
                fail = fail + "|③APK里so的sha不符";
                return false;
            }
            File target = new File(dir, SO_NAME);
            OutputStream out = null;
            try {
                out = new FileOutputStream(target);
                out.write(data);
                out.flush();
            } finally {
                closeQuietly(out);
            }
            target.setReadable(true, false);
            target.setExecutable(true, false);
            if (loadAbsolute(target)) {
                detail = "APK 抽出 [" + entry.getName() + "] → " + target.getAbsolutePath();
                return true;
            }
            fail = fail + "|③抽出后加载失败";
        } catch (Throwable t) {
            fail = fail + "|③" + brief(t);
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    // ── 杂项 ────────────────────────────────────────────────────────────

    /** 是否 arm64（本补丁的偏移只对 arm64 的 libsmsdk.so 有效）。 */
    static boolean isArm64() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0) return "arm64-v8a".equals(abis[0]);
            return "arm64-v8a".equals(Build.CPU_ABI);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前是不是主线程（主线程不许联网）。 */
    static boolean onMainThread() {
        try {
            return Looper.myLooper() == Looper.getMainLooper();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String brief(Throwable t) {
        String m = null;
        try {
            m = t.getMessage();
        } catch (Throwable ignored) {
        }
        if (m == null) return t.getClass().getSimpleName();
        m = m.replace('\n', ' ');
        if (m.length() > 120) m = m.substring(0, 120) + "…";
        return m;
    }

    private static String shortUrl(String url) {
        if (url == null) return "?";
        int i = url.indexOf("://");
        return i > 0 ? url.substring(i + 3) : url;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    static String loadDetail() {
        return detail;
    }

    /** 状态码翻人话，日志里直接看结论。 */
    static String text(int code) {
        switch (code) {
            case ST_PATCHED: return "已打上";
            case ST_ALREADY: return "已是补丁态";
            case ST_NO_LIB: return "libsmsdk.so 尚未映射";
            case ST_VERSION: return "指令校验不符（库版本变了，拒绝下笔）";
            case ST_MPROTECT: return "mprotect 失败（SELinux/权限）";
            case ST_UNSUPPORTED: return "非 arm64，不支持";
            default: return "未知(" + code + ")";
        }
    }

    static String probeText(int bits) {
        if (bits == 0) return "libsmsdk.so 尚未映射";
        StringBuilder sb = new StringBuilder();
        sb.append("已映射");
        sb.append((bits & PB_GUARD_OK) != 0 ? "，偏移校验通过" : "，偏移校验不通过");
        sb.append((bits & PB_ROOT_OK) != 0 ? "，root补丁在" : "，root补丁未打");
        sb.append((bits & PB_CHOKE_OK) != 0 ? "，扫描器补丁在" : "，扫描器补丁未打");
        if ((bits & PB_ICACHE) == 0) sb.append("，__clear_cache 不可用");
        return sb.toString();
    }

    private SmsNative() { }
}
