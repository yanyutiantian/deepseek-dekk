package com.dekk.yanyu;

/** Loopback protocol for the local helper app; keep these constants in sync with it. */
final class AdbProtocol {
    static final String HOST = "127.0.0.1";
    static final int PORT = 47810;
    static final String PATH_COMMAND = "/command";
    /** Abstract AF_UNIX name shared with the extension; bypasses IP firewalls. */
    static final String SOCKET_NAME = "dekk_adb_47810";
    static final int MIN_MINUTES = 1;
    static final int MAX_MINUTES = 60;
    static final int MAX_LINE = 8192;
    static final int CONNECT_TIMEOUT_MS = 1500;
    static final int READ_TIMEOUT_MS = 9000;
    static final int LOCK_TIMEOUT_MS = 30000;

    static final String CMD_PING = "ping";
    static final String CMD_FREEZE = "freeze";
    static final String CMD_UNFREEZE = "unfreeze";
    static final String CMD_UNFREEZE_ALL = "unfreezeAll";
    static final String CMD_FROZEN = "frozen";
    static final String CMD_USAGE = "usage";
    static final String CMD_APPS = "apps";

    static final String KEY_TOKEN = "token";
    static final String KEY_CMD = "cmd";
    static final String KEY_PACKAGE = "package";
    static final String KEY_MINUTES = "minutes";
    static final String KEY_LIMIT = "limit";
    static final String KEY_OK = "ok";
    static final String KEY_ERROR = "error";
    static final String KEY_UNTIL = "until";
    static final String KEY_ITEMS = "items";
    static final String KEY_LABEL = "label";
    static final String KEY_MS = "ms";
    static final String KEY_SHIZUKU = "shizuku";
    static final String KEY_USAGE_ACCESS = "usageAccess";

    private AdbProtocol() { }

    static int clampMinutes(int minutes) {
        if (minutes < MIN_MINUTES) return MIN_MINUTES;
        return minutes > MAX_MINUTES ? MAX_MINUTES : minutes;
    }

    static String hostPort() {
        return HOST + ":" + PORT;
    }

    static String url() {
        return "http://" + HOST + ":" + PORT;
    }
}