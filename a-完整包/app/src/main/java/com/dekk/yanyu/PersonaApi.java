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

/** HTTPS only, platform certificate verification, no credentials in logs. */
public final class PersonaApi {
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final String BASE = "https://dekk.example.com/API/?action=";
    public interface Callback { void done(JSONObject result, String error); }
    private PersonaApi() { }

    /**
     * V2：action 形如 "market&page=1&search=x"，参数部分打成信封放进 query；
     * 服务端解密后会注入 $_GET，所以 GET 接口同样适用。
     */
    private static String buildUrl(String action) throws Exception {
        int cut = action.indexOf('&');
        String name = cut < 0 ? action : action.substring(0, cut);
        return BASE + name + "&ver=" + ApiCrypto.VERSION;
    }

    /** "page=1&search=a%20b" → {"page":"1","search":"a b"}。 */
    private static String encodeParams(String query) throws Exception {
        JSONObject params = new JSONObject();
        if (query.length() > 0) {
            String[] pairs = query.split("&");
            for (int i = 0; i < pairs.length; i++) {
                String pair = pairs[i];
                if (pair.length() == 0) continue;
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                try {
                    params.put(java.net.URLDecoder.decode(key, "UTF-8"),
                            java.net.URLDecoder.decode(value, "UTF-8"));
                } catch (Throwable ignored) { }
            }
        }
        return params.toString();
    }

    /**
     * V2-POST：把 "page=1&search=x" 与 JSON body 合并成一个参数对象，
     * 统一放进加密信封（不再拼 URL，避免长度限制和参数明文泄露）。
     */
    private static String requestParams(String action, JSONObject body) throws Exception {
        int cut = action.indexOf('&');
        JSONObject params = new JSONObject(cut < 0 ? "{}" : encodeParams(action.substring(cut + 1)));
        if (body != null) {
            java.util.Iterator<String> keys = body.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                params.put(key, body.get(key));
            }
        }
        return params.toString();
    }

    public static void request(final Activity activity, final String action,
            final JSONObject body, final Callback callback) { requestWithToken(activity,action,body,AccountStore.token(activity),callback); }
    public static void requestWithToken(final Activity activity, final String action,
            final JSONObject body, final String suppliedToken, final Callback callback) {
        final android.content.Context context = activity.getApplicationContext();
        final String token = suppliedToken == null ? "" : suppliedToken;
        IO.execute(new Runnable() {
            public void run() {
                JSONObject result = null; String error = null; String detail = null;
                HttpsURLConnection conn = null;
                try {
                    // V2：所有请求（含原 GET 接口）统一走 POST + 加密信封
                    String params = requestParams(action, body);
                    conn = (HttpsURLConnection) new URL(buildUrl(action)).openConnection();
                    TlsDebug.apply(conn);
                    conn.setConnectTimeout(15000); conn.setReadTimeout(25000);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    if (token.length() > 0) conn.setRequestProperty("Authorization", "Bearer " + token);
                    JSONObject outer = new JSONObject();
                    outer.put("v", 2);
                    outer.put("env", ApiCrypto.seal(params));
                    byte[] data = outer.toString().getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(data.length);
                    OutputStream out = conn.getOutputStream();
                    try { out.write(data); } finally { out.close(); }
                    int status = conn.getResponseCode();
                    InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    if (in == null) throw new Exception("empty response");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try {
                        byte[] buf = new byte[4096]; int n;
                        while ((n = in.read(buf)) != -1) {
                            if (bytes.size() + n > 1048576) throw new Exception("response too large");
                            bytes.write(buf, 0, n);
                        }
                    } finally {
                        in.close();
                    }
                    result = new JSONObject(ApiCrypto.open(bytes.toString("UTF-8")));
                    if (status == 401 && token.length() > 0 && token.equals(AccountStore.token(context)))
                        AccountStore.clear(context);
                    if (status < 200 || status >= 300 || !result.optBoolean("ok")) {
                        // 服务端的所有错误都通过 message 返回，原样交给界面提示
                        String message = result.optString("message", "").trim();
                        error = message.length() > 0 ? message : ("请求失败（HTTP " + status + "）");
                        result = null;
                    }
                } catch (Exception e) {
                    error = "网络请求失败，请检查网络后重试";
                    detail = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                }
                finally {
                    if (conn != null) conn.disconnect();
                    ApiCrypto.clearSession();   // 本请求结束（无论成败）才清会话密钥
                }
                // 便于真机排查：这次请求发到哪、结果如何（不含 token / 内容）
                try {
                    HotBridge.log("[DEKK][API] " + action
                            + " (POST)"
                            + " → " + (error == null ? "ok" : ("失败: " + error + (detail == null ? "" : "｜原因: " + detail))));
                } catch (Throwable ignored) { }
                final JSONObject value = result; final String failure = error;
                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        if (!activity.isFinishing() && !activity.isDestroyed()) callback.done(value, failure);
                    }
                });
            }
        });
    }
    public static JSONObject object(String... pairs) {
        JSONObject value = new JSONObject();
        try { for (int i = 0; i + 1 < pairs.length; i += 2) value.put(pairs[i], pairs[i + 1]); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        return value;
    }
}