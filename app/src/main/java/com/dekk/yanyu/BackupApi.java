package com.dekk.yanyu;

import android.app.Activity;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/**
 * 聊天记录备份的 V2 请求层（PUT / GET / DELETE）。
 *
 * 与 PersonaApi 使用同一套 V2 加密信封，但单独实现的原因是：
 * 1) 备份 blob 体积远大于普通接口，需要更大的响应上限；
 * 2) 加解密与上传都放在独立 IO 线程，避免在 UI 线程跑 PBKDF2 卡顿。
 *
 * 服务端只保存密文（blob），授权码不以明文离开设备。
 */
final class BackupApi {
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final String BASE = "https://dekk.example.com/API/?action=";
    /** 单次响应上限：备份 blob 下载后还要解信封，留足余量。 */
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;
    interface Callback { void done(JSONObject result, String error); }

    private BackupApi() { }

    static String linkFor(String id) { return BASE + "backup_get&id=" + id; }

    /** 从用户输入的链接或裸 id 中取出 32 位十六进制 id。 */
    static String parseId(String linkOrId) {
        if (linkOrId == null) return null;
        String trimmed = linkOrId.trim();
        if (trimmed.length() == 0) return null;
        int index = trimmed.indexOf("id=");
        if (index >= 0) trimmed = trimmed.substring(index + 3);
        int cut = trimmed.indexOf('&');
        if (cut >= 0) trimmed = trimmed.substring(0, cut);
        trimmed = trimmed.trim();
        if (trimmed.length() == 32 && trimmed.matches("[0-9a-fA-F]{32}")) return trimmed.toLowerCase();
        return null;
    }

    static void put(final Activity activity, final String blob, final String blobHash,
            final String codeHash, final Callback callback) {
        JSONObject body = PersonaApi.object("blob", blob, "blob_hash", blobHash, "code_hash", codeHash);
        call(activity, "backup_put", body, callback);
    }

    static void get(final Activity activity, final String id, final String codeHash, final Callback callback) {
        JSONObject body = PersonaApi.object("id", id, "code_hash", codeHash);
        call(activity, "backup_get", body, callback);
    }

    static void delete(final Activity activity, final String id, final Callback callback) {
        call(activity, "backup_delete", PersonaApi.object("id", id), callback);
    }

    private static void call(final Activity activity, final String action,
            final JSONObject body, final Callback callback) {
        final android.content.Context context = activity.getApplicationContext();
        final String token = AccountStore.token(context);
        IO.execute(new Runnable() {
            public void run() {
                JSONObject result = null;
                String error = null;
                HttpsURLConnection conn = null;
                try {
                    conn = (HttpsURLConnection) new URL(BASE + action + "&ver=" + ApiCrypto.VERSION).openConnection();
                    TlsDebug.apply(conn);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    if (token != null && token.length() > 0) conn.setRequestProperty("Authorization", "Bearer " + token);
                    JSONObject outer = new JSONObject();
                    outer.put("v", 2);
                    outer.put("env", ApiCrypto.seal(body.toString()));
                    byte[] data = outer.toString().getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(data.length);
                    OutputStream out = conn.getOutputStream();
                    try { out.write(data); } finally { out.close(); }
                    int status = conn.getResponseCode();
                    InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    if (in == null) throw new Exception("empty response");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if (bytes.size() + n > MAX_RESPONSE) throw new Exception("response too large");
                            bytes.write(buf, 0, n);
                        }
                    } finally {
                        in.close();
                    }
                    result = new JSONObject(ApiCrypto.open(bytes.toString("UTF-8")));
                    if (status == 401 && token != null && token.length() > 0 && token.equals(AccountStore.token(context)))
                        AccountStore.clear(context);
                    if (status < 200 || status >= 300 || !result.optBoolean("ok")) {
                        String message = result.optString("message", "").trim();
                        error = message.length() > 0 ? message : ("请求失败（HTTP " + status + "）");
                        result = null;
                    }
                } catch (Exception e) {
                    error = "网络请求失败，请检查网络后重试";
                } finally {
                    if (conn != null) conn.disconnect();
                    ApiCrypto.clearSession();
                }
                try { HotBridge.log("[DEKK][备份] " + action + " → " + (error == null ? "ok" : ("失败: " + error))); }
                catch (Throwable ignored) { }
                final JSONObject value = result;
                final String failure = error;
                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        callback.done(value, failure);
                    }
                });
            }
        });
    }
}