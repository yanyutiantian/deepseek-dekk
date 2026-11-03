package com.dekk.yanyu;

/**
 * 热更新壳的配置。
 *
 * <p>壳 APK 里只有「下载 dex + 装进 DexClassLoader + 反射启动 payload」这三件事，
 * 功能代码全在服务器上的 classes.dex 里，所以以后改功能只要换 dex，用户不用重新下模块。
 */
final class HotConfig {

    /** 宿主包名。 */
    static final String HOST = "com.deepseek.chat";

    /** payload 与版本文件（version.txt 的内容就是 dex 的 md5）。 */
    static final String DEX_URL = "https://dekk.example.com/dex/classes.dex";
    static final String VERSION_URL = "https://dekk.example.com/dex/version.txt";

    /** dex 里的入口类与方法名。 */
    static final String ENTRY_CLASS = "com.dekk.yanyu.DekkEntry";
    static final String ENTRY_METHOD = "install";

    /** 缓存目录（放在宿主自己的私有目录下，不需要任何存储权限）。 */
    static final String DIR = "dekk-hot";
    static final String FILE_VERSION = "classes.md5";
    static final String FILE_LAST_CHECK = "checked_at";

    /**
     * 版本检查间隔：0 表示**每次宿主冷启动都查一次**（用户要的即时更新）。
     * 检查只是拉一个几十字节的 version.txt，开销可以忽略。
     */
    static final long CHECK_INTERVAL_MS = 0L;

    /** 网络超时与 dex 大小上限。 */
    static final int CONNECT_TIMEOUT_MS = 8000;
    static final int READ_TIMEOUT_MS = 20000;
    static final int MAX_DEX_BYTES = 16 * 1024 * 1024;

    private HotConfig() { }
}