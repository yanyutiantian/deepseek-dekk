package com.dekk.yanyu;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dalvik.system.DexClassLoader;

/**
 * dex 的下载 / 校验 / 缓存 / 加载。
 *
 * <p><b>线程约束（很重要）</b>：Xposed 的 handleLoadPackage 跑在宿主主线程上，
 * 而宿主的 targetSdk 很高，主线程联网会直接抛 NetworkOnMainThreadException。
 * 所以这里只有两种情况能碰网络，且都在工作线程上：
 * <ol>
 *   <li>首次运行（本地没缓存）：{@link #fresh} 在工作线程里下载、落盘，再回调 {@link Ready}；</li>
 *   <li>已经跑起来之后的版本检查：{@link #checkAsync} 同样在工作线程里比对。</li>
 * </ol>
 * 主线程路径只做文件系统判断（{@link #start}），命中了缓存就直接加载。
 *
 * <p>缓存策略：文件按内容 md5 命名（classes-&lt;md5&gt;.dex），版本对不上就换新文件，
 * 这样 DexClassLoader 不会读到旧的 oat 缓存。缓存目录用的是宿主自己的私有目录，
 * 模块代码就跑在宿主进程里、用的是宿主的 uid，所以不需要 root，也不需要存储权限。
 */
final class HotDex {

    /** payload 备好之后的回调；如果这次是现下载的，回调在工作线程上执行。 */
    interface Ready {
        void onReady(ClassLoader payload);
    }

    private static ClassLoader payload;
    private static final AtomicBoolean booting = new AtomicBoolean(false);
    private static final AtomicBoolean checking = new AtomicBoolean(false);

    private HotDex() { }

    /**
     * 入口：有缓存就当场加载并回调，没有缓存则交给工作线程去下载再回调。
     * 这个方法自身不联网，可以安全地在主线程调用。
     */
    static void start(String dataDir, ClassLoader host, Ready ready) {
        if (payload != null) {
            ready.onReady(payload);
            return;
        }
        File dir = baseDir(dataDir);
        if (dir == null) {
            HotLog.i("拿不到宿主私有目录，无法缓存 dex");
            return;
        }

        String version = read(new File(dir, HotConfig.FILE_VERSION));
        File dex = version.length() == 0 ? null : dexFile(dir, version);
        if (dex != null && dex.isFile()) {
            ClassLoader loader = load(dex, host);
            if (loader != null) {
                payload = loader;
                HotLog.i("dex 已加载（本地缓存）：" + dex.getName() + "（" + dex.length() + " 字节）");
                ready.onReady(loader);
                checkAsync(dir, version);
                return;
            }
        }
        fresh(dir, host, ready);
    }

    /** 首次运行（或缓存被清掉）：在工作线程里下载 → 落盘 → 加载 → 回调。 */
    private static void fresh(final File dir, final ClassLoader host, final Ready ready) {
        if (!booting.compareAndSet(false, true)) return;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HotLog.i("本地没有 dex，开始下载 " + HotConfig.DEX_URL);
                    File dex = download(dir, remoteVersion());
                    if (dex == null) {
                        HotLog.i("首次下载失败，本次不加载模块（下次启动会重试）");
                        return;
                    }
                    write(new File(dir, HotConfig.FILE_VERSION), md5OfFile(dex));
                    write(new File(dir, HotConfig.FILE_LAST_CHECK), String.valueOf(System.currentTimeMillis()));

                    ClassLoader loader = load(dex, host);
                    if (loader == null) return;
                    payload = loader;
                    HotLog.i("dex 已加载（首次下载）：" + dex.getName() + "（" + dex.length() + " 字节）");
                    ready.onReady(loader);
                } catch (Throwable t) {
                    HotLog.i("首次加载异常：" + t);
                } finally {
                    booting.set(false);
                }
            }
        }, "dekk-hot-boot");
        thread.setDaemon(true);
        thread.start();
    }

    /** 后台检查更新：下载成功后写入新的 md5，宿主「下次冷启动」生效。 */
    private static void checkAsync(final File dir, final String current) {
        if (!needCheck(dir)) return;
        if (!checking.compareAndSet(false, true)) return;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    write(new File(dir, HotConfig.FILE_LAST_CHECK), String.valueOf(System.currentTimeMillis()));
                    String remote = remoteVersion();
                    if (remote.length() == 0) {
                        HotLog.i("版本检查跳过：服务器没返回 md5");
                        return;
                    }
                    if (remote.equalsIgnoreCase(current)) {
                        HotLog.i("已是最新 dex（" + shortId(remote) + "）");
                        return;
                    }
                    File dex = download(dir, remote);
                    if (dex == null) return;
                    write(new File(dir, HotConfig.FILE_VERSION), remote);
                    HotLog.i("发现新 dex " + shortId(remote) + "，已下载，准备提示并自动重启宿主");
                    HotUpdate.ready(remote);
                } catch (Throwable t) {
                    HotLog.i("版本检查异常：" + t);
                } finally {
                    checking.set(false);
                }
            }
        }, "dekk-hot-check");
        thread.setDaemon(true);
        thread.start();
    }

    private static File download(File dir, String expected) {
        File tmp = new File(dir, "classes.tmp");
        try {
            byte[] data = httpGet(HotConfig.DEX_URL, HotConfig.MAX_DEX_BYTES);
            if (data == null) return null;
            if (!isDex(data)) {
                HotLog.i("下载内容不是 dex（" + data.length + " 字节）");
                return null;
            }
            String md5 = md5(data);
            if (expected != null && expected.length() > 0 && !expected.equalsIgnoreCase(md5)) {
                HotLog.i("md5 不一致，丢弃：期望 " + shortId(expected) + "，实际 " + shortId(md5));
                return null;
            }
            write(tmp, data);

            File dex = dexFile(dir, md5);
            if (dex.isFile() && dex.length() == data.length) {
                tmp.delete();
            } else {
                dex.delete();
                if (!tmp.renameTo(dex)) {
                    HotLog.i("写入 dex 失败：" + dex.getAbsolutePath());
                    return null;
                }
            }
            cleanup(dir, dex);
            lockDex(dex);
            HotLog.i("dex 已就绪：" + dex.getName());
            return dex;
        } catch (Throwable t) {
            HotLog.i("下载 dex 异常：" + t);
            return null;
        } finally {
            if (tmp.isFile()) tmp.delete();
        }
    }

    /** 只读服务器版本（version.txt 里的 md5），供界面与版本检查使用（必须在工作线程调用）。 */
    static String remoteVersion() {
        try {
            byte[] data = httpGet(HotConfig.VERSION_URL, 4096);
            if (data == null) return "";
            String text = new String(data, "UTF-8").trim();
            Matcher matcher = Pattern.compile("[0-9a-fA-F]{32}").matcher(text);
            if (matcher.find()) return matcher.group().toLowerCase();
            HotLog.i("版本文件里没有 md5：" + shortId(text));
            return "";
        } catch (Throwable t) {
            HotLog.i("读取服务器版本失败：" + t);
            return "";
        }
    }

    private static ClassLoader load(File dex, ClassLoader host) {
        File opt = new File(dex.getParentFile(), "opt");
        // Android 14 起禁止加载「可写」的 dex 文件（防注入，报 SecurityException: Writable dex file）。
        // 缓存文件都置成只读；万一个别机型还拒，就退回内存加载。
        lockDex(dex);
        try {
            if (!opt.isDirectory() && !opt.mkdirs()) {
                HotLog.i("创建编译缓存目录失败");
                return null;
            }
            // 父加载器 = 壳自己的加载器：payload 里的 Hot* 门面类会优先用壳里的那份
            // （壳的 dex 被 LSPosed 重写过，只有它才能真的调用 Xposed API）。
            return new DexClassLoader(dex.getAbsolutePath(), opt.getAbsolutePath(), null,
                    HotDex.class.getClassLoader());
        } catch (SecurityException e) {
            HotLog.i("文件方式加载被系统拒绝（" + e.getMessage() + "），改用内存方式");
            return loadInMemory(dex);
        } catch (Throwable t) {
            HotLog.i("DexClassLoader 创建失败：" + t);
            return loadInMemory(dex);
        }
    }

    /** 把 dex 置成只读（chmod 444）：Android 14+ 不允许加载可写的 dex 文件。 */
    private static void lockDex(File dex) {
        try {
            dex.setReadable(true, true);
            dex.setWritable(false, false);
        } catch (Throwable t) {
            HotLog.i("设置只读失败（可能仍会被系统拒绝）：" + t);
        }
    }

    /**
     * 兜底：API 26+ 的 InMemoryDexClassLoader，直接把 dex 字节喂进去，不落文件。
     *
     * <p>注意：内存加载拿不到「壳的加载器」做父加载器（只能给壳自己的加载器），
     * payload 里的 Hot* 门面也仍然解析得到，所以两种方式都能用；
     * 这条路只有在「文件方式被系统拒绝」时才会走。
     */
    private static ClassLoader loadInMemory(File dex) {
        try {
            byte[] data = readBytes(dex);
            if (data == null || data.length == 0) {
                HotLog.i("内存加载失败：读不到 dex 内容");
                return null;
            }
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(data.length);
            buffer.put(data);
            buffer.position(0);
            Class<?> loaderClass = Class.forName("dalvik.system.InMemoryDexClassLoader");
            java.lang.reflect.Constructor<?> ctor = loaderClass.getConstructor(
                    java.nio.ByteBuffer.class, ClassLoader.class);
            ClassLoader parent = HotDex.class.getClassLoader();
            ClassLoader loader = (ClassLoader) ctor.newInstance(buffer, parent);
            HotLog.i("已改用内存方式加载 dex（" + data.length + " 字节）");
            return loader;
        } catch (Throwable t) {
            HotLog.i("内存方式加载也失败：" + t);
            return null;
        }
    }

    /** 缓存目录：优先用宿主 dataDir，兜底用单用户固定路径。 */
    private static File baseDir(String dataDir) {
        if (dataDir != null && dataDir.length() > 0) {
            File dir = new File(dataDir, "files/" + HotConfig.DIR);
            if (dir.isDirectory() || dir.mkdirs()) return dir;
        }
        String[] candidates = {
                "/data/user/0/" + HotConfig.HOST + "/files/" + HotConfig.DIR,
                "/data/data/" + HotConfig.HOST + "/files/" + HotConfig.DIR,
        };
        for (String path : candidates) {
            File dir = new File(path);
            if (dir.isDirectory() || dir.mkdirs()) return dir;
        }
        return null;
    }

    private static File dexFile(File dir, String version) {
        return new File(dir, "classes-" + version + ".dex");
    }

    /** 只留当前这一版，旧的删掉，避免宿主目录越堆越大。 */
    private static void cleanup(File dir, File keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (file.equals(keep)) continue;
            if (name.startsWith("classes-") && name.endsWith(".dex")) file.delete();
        }
    }

    private static boolean needCheck(File dir) {
        try {
            String last = read(new File(dir, HotConfig.FILE_LAST_CHECK));
            if (last.length() == 0) return true;
            long time = Long.parseLong(last.trim());
            return System.currentTimeMillis() - time > HotConfig.CHECK_INTERVAL_MS;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isDex(byte[] data) {
        return data != null && data.length > 8
                && data[0] == 'd' && data[1] == 'e' && data[2] == 'x' && data[3] == '\n';
    }

    private static String shortId(String value) {
        if (value == null) return "";
        return value.length() > 8 ? value.substring(0, 8) : value;
    }

    private static byte[] httpGet(String address, int limit) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(HotConfig.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(HotConfig.READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code != 200) {
                HotLog.i("HTTP " + code + " " + address);
                return null;
            }
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > limit) throw new IllegalStateException("内容超过上限 " + limit + " 字节");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            HotLog.i("请求失败 " + address + "：" + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(File file) {
        InputStream in = null;
        try {
            if (file == null || !file.isFile()) return "";
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }
    /** 整份读出来（内存加载 dex 用）。 */
    private static byte[] readBytes(File file) {
        InputStream in = null;
        try {
            if (file == null || !file.isFile()) return null;
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } catch (Throwable t) {
            HotLog.i("读 dex 失败：" + t);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static void write(File file, String text) {
        try {
            write(file, text.getBytes("UTF-8"));
        } catch (Throwable t) {
            HotLog.i("写文件失败 " + file + "：" + t);
        }
    }

    private static void write(File file, byte[] data) throws Exception {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(data);
            out.flush();
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) { }
        }
    }

    private static String md5OfFile(File file) {
        try {
            InputStream in = new FileInputStream(file);
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
                return toHex(digest.digest());
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            HotLog.i("计算 md5 失败：" + t);
            return "";
        }
    }

    private static String md5(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(data);
            return toHex(digest.digest());
        } catch (Throwable t) {
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int value = b & 0xff;
            if (value < 16) builder.append('0');
            builder.append(Integer.toHexString(value));
        }
        return builder.toString();
    }
}