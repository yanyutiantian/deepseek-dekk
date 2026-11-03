package com.dekk.yanyu;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONObject;

/**
 * Module side of the ADB bridge.
 *
 * Requests copy the shape of the extension's own self check: raw socket, plain HTTP,
 * "Connection: close", read until EOF. That shape is proven on this device, and a loopback
 * TCP write is not subject to the cleartext policy that blocks HttpURLConnection inside
 * DeepSeek (targetSdk 36, no usesCleartextTraffic).
 *
 * Channels, tried in order and remembered once one works:
 * 1) raw loopback socket 2) abstract AF_UNIX socket 3) HttpURLConnection (login style probe)
 */
final class AdbBridge {
    private static final String RAW = "raw";
    private static final String LOCAL = "local";
    private static final String URL_CONN = "urlconn";

    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_BYTES = 131072;

    static final class Reply {
        final boolean ok;
        final boolean reachable;
        final String error;
        final String raw;
        final JSONObject body;

        Reply(boolean ok, boolean reachable, String error, String raw, JSONObject body) {
            this.ok = ok;
            this.reachable = reachable;
            this.error = error;
            this.raw = raw;
            this.body = body;
        }
    }

    private AdbBridge() { }

    /** Which channel answered last; shown on the settings page. */
    static volatile String lastTransport = "";
    private static volatile String preferred = "";
    private static volatile boolean urlConnProbed = false;
    private static volatile String lastFailure = "";

    static String lastFailure() {
        return lastFailure;
    }

    static Reply call(String command, JSONObject extra) {
        String commandJson = commandJson(command, extra);
        String path = pathFor(command);
        if (path == null) return request("POST", AdbProtocol.PATH_COMMAND, commandJson);
        return request("GET", path, commandJson);
    }

    /** GET paths mirror CommandRouter, so read-only commands need no request body. */
    private static String pathFor(String command) {
        if (AdbProtocol.CMD_PING.equals(command)) return "/ping";
        if (AdbProtocol.CMD_USAGE.equals(command)) return "/usage";
        if (AdbProtocol.CMD_FROZEN.equals(command)) return "/frozen";
        if (AdbProtocol.CMD_UNFREEZE_ALL.equals(command)) return "/unfreezeAll";
        if (AdbProtocol.CMD_APPS.equals(command)) return "/apps";
        return null;
    }

    private static String commandJson(String command, JSONObject extra) {
        JSONObject request = new JSONObject();
        try {
            request.put(AdbProtocol.KEY_CMD, command);
            if (extra != null) {
                Iterator<String> keys = extra.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    request.put(key, extra.get(key));
                }
            }
        } catch (Throwable ignored) { }
        return request.toString();
    }

    static Reply request(String method, String path, String commandJson) {
        List<String> order = new ArrayList<String>();
        if (LOCAL.equals(preferred)) {
            order.add(LOCAL);
        } else if (URL_CONN.equals(preferred)) {
            order.add(URL_CONN);
        }
        order.add(RAW);
        order.add(LOCAL);
        if (!urlConnProbed) order.add(URL_CONN);

        List<String> notes = new ArrayList<String>();
        for (String channel : order) {
            Reply reply = attempt(channel, method, path, commandJson);
            if (reply.reachable) {
                preferred = channel;
                lastTransport = label(channel);
                lastFailure = "";
                log(label(channel) + " " + method + " " + path + " -> "
                        + (reply.ok ? "OK" : "扩展返回失败：" + reply.error));
                return reply;
            }
            log(label(channel) + " " + method + " " + path + " -> " + reply.error);
            notes.add(label(channel) + "：" + reply.error);
        }
        lastTransport = "";
        lastFailure = join(notes);
        log("扩展不可达 -> " + lastFailure);
        return new Reply(false, false, lastFailure, null, null);
    }

    private static Reply attempt(String channel, String method, String path, String commandJson) {
        if (LOCAL.equals(channel)) return viaLocalSocket(commandJson);
        if (URL_CONN.equals(channel)) return viaUrlConnection(method, path, commandJson);
        return viaRawSocket(method, path, commandJson);
    }

    private static String label(String channel) {
        if (LOCAL.equals(channel)) return "本机套接字";
        if (URL_CONN.equals(channel)) return "明文URL连接";
        return "回环Socket";
    }

    /** Channel 1: raw loopback socket, plain HTTP text; same framing as the extension self check. */
    private static Reply viaRawSocket(String method, String path, String commandJson) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(AdbProtocol.HOST, AdbProtocol.PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            byte[] payload = commandJson == null ? new byte[0] : commandJson.getBytes("UTF-8");
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(AdbProtocol.HOST).append(':').append(AdbProtocol.PORT).append("\r\n");
            head.append("Accept: application/json\r\n");
            if (payload.length > 0) {
                head.append("Content-Type: application/json; charset=utf-8\r\n");
                head.append("Content-Length: ").append(payload.length).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            OutputStream stream = socket.getOutputStream();
            stream.write(head.toString().getBytes("UTF-8"));
            if (payload.length > 0) stream.write(payload);
            stream.flush();
            String text = stripHeaders(readAll(socket.getInputStream()));
            if (text == null || text.trim().length() == 0) return new Reply(false, false, "没有返回内容", null, null);
            return parse(text);
        } catch (Throwable t) {
            return new Reply(false, false, describe(t), null, null);
        } finally {
            if (socket != null) try { socket.close(); } catch (Throwable ignored) { }
        }
    }

    /** Channel 3: HttpURLConnection, written the way the login request in PersonaApi is written. */
    private static Reply viaUrlConnection(String method, String path, String commandJson) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://" + AdbProtocol.HOST + ":"
                    + AdbProtocol.PORT + path).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestMethod(method);
            if (commandJson != null && "POST".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] data = commandJson.getBytes("UTF-8");
                connection.setFixedLengthStreamingMode(data.length);
                OutputStream out = connection.getOutputStream();
                try { out.write(data); } finally { out.close(); }
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            urlConnProbed = true;
            if (in == null) return new Reply(false, false, "没有返回内容", null, null);
            String text;
            try { text = readAll(in); } finally { in.close(); }
            if (text == null || text.trim().length() == 0) return new Reply(false, false, "没有返回内容", null, null);
            return parse(text);
        } catch (Throwable t) {
            urlConnProbed = true;
            return new Reply(false, false, describeUrlConnection(t), null, null);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Channel 2: abstract AF_UNIX socket; one JSON line in, one JSON line out. */
    private static Reply viaLocalSocket(String commandJson) {
        LocalSocket socket = null;
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(AdbProtocol.SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT));
            OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), "UTF-8");
            writer.write(commandJson);
            writer.write("\n");
            writer.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String line = reader.readLine();
            if (line == null || line.length() == 0) return new Reply(false, false, "没有返回内容", null, null);
            return parse(line);
        } catch (Throwable t) {
            return new Reply(false, false, describe(t), null, null);
        } finally {
            if (socket != null) try { socket.close(); } catch (Throwable ignored) { }
        }
    }

    private static Reply parse(String text) {
        String clean = stripHeaders(text).trim();
        JSONObject parsed;
        try {
            parsed = new JSONObject(clean);
        } catch (Throwable t) {
            return new Reply(false, false, "返回内容不是 JSON: " + preview(clean), clean, null);
        }
        boolean ok = parsed.optBoolean(AdbProtocol.KEY_OK, false);
        return new Reply(ok, true, parsed.optString(AdbProtocol.KEY_ERROR, ok ? "" : "扩展返回失败"), clean, parsed);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        try {
            int read;
            while ((read = in.read(buffer)) > 0) {
                all.write(buffer, 0, read);
                if (all.size() > MAX_BYTES) break;
            }
        } catch (java.net.SocketTimeoutException timeout) {
            if (all.size() == 0) throw timeout;
        }
        return all.toString("UTF-8");
    }

    /** Drops everything up to and including the blank line after the status line. */
    private static String stripHeaders(String text) {
        if (text == null) return null;
        int at = text.indexOf("\r\n\r\n");
        if (at >= 0) return text.substring(at + 4);
        at = text.indexOf("\n\n");
        if (at >= 0) return text.substring(at + 2);
        return text;
    }

    static String preview(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }

    private static String join(List<String> notes) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) text.append("；");
            text.append(notes.get(i));
        }
        return text.toString();
    }

    public static void log(String message) {
        HotBridge.log("[DEKK][ADB] " + message);
        try {
            if (message != null && message.contains("防撤回")) {
                mirrorAntiRecallLog(message);
            }
        } catch (Throwable ignore) {
        }
    }

    /** 把防撤回日志镜像到宿主私有目录（/data/data/com.deepseek.chat/files/dekk_anti.log），便于直接查看。 */
    private static void mirrorAntiRecallLog(String message) {
        java.io.File dir = null;
        try {
            android.content.Context app = HotApp.currentApplication();
            if (app != null) {
                dir = app.getFilesDir();
            }
        } catch (Throwable ignore) {
        }
        if (dir == null) {
            return;
        }
        java.io.File file = new java.io.File(dir, "dekk_anti.log");
        try {
            if (file.exists() && file.length() > 262144L) {
                file.delete();
            }
            java.io.FileWriter writer = new java.io.FileWriter(file, true);
            try {
                writer.write(System.currentTimeMillis() + " " + message + "\n");
            } finally {
                writer.close();
            }
        } catch (Throwable ignore) {
        }
    }

    private static String describe(Throwable t) {
        String name = t.getClass().getSimpleName();
        String message = t.getMessage() == null ? "" : t.getMessage();
        if (message.indexOf("Cleartext") >= 0) return "明文被系统网络策略拒绝";
        if ("ConnectException".equals(name)) return "连接被拒绝（扩展服务未启动）";
        if ("PermissionError".equals(name)) return "被系统拒绝（SELinux）";
        if ("SocketException".equals(name) && message.indexOf("denied") >= 0) return "被系统拒绝（SELinux）";
        if ("SocketException".equals(name)) return "套接字不可用: " + message;
        if ("SocketTimeoutException".equals(name)) return "连接超时";
        if ("NetworkOnMainThreadException".equals(name)) return "网络调用跑在主线程";
        if ("SecurityException".equals(name)) return "缺少 INTERNET 权限";
        return name + (message.length() == 0 ? "" : "（" + message + "）");
    }

    private static String describeUrlConnection(Throwable t) {
        String message = t.getMessage() == null ? "" : t.getMessage();
        if (message.indexOf("Cleartext") >= 0) {
            return "明文被拒绝（DeepSeek targetSdk 36 未声明 usesCleartextTraffic）";
        }
        return describe(t);
    }

    /** Runs at hook install time so a failure shows up in the log without any user action. */
    static void probeAsync() {
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    log("启动探测 uid=" + android.os.Process.myUid() + " 目标=" + AdbProtocol.url()
                            + AdbProtocol.PATH_COMMAND + " 套接字=" + AdbProtocol.SOCKET_NAME);
                    Reply reply = request("GET", "/ping", commandJson(AdbProtocol.CMD_PING, null));
                    if (reply.reachable && reply.ok) {
                        log("启动探测完成：通道=" + lastTransport + " 返回=" + preview(reply.raw));
                    } else {
                        log("启动探测失败：无可用通道 -> " + lastFailure);
                    }
                } catch (Throwable t) {
                    log("启动探测异常：" + t.getClass().getSimpleName());
                }
            }
        }, "dekk-adb-probe");
        worker.setDaemon(true);
        worker.start();
    }
}