package com.dekk.yanyu;

import android.app.Activity;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 聊天记录迁移：采集当前会话 → 加密上传 → 生成链接与授权码；以及反向导入。
 *
 * 只处理「当前打开的会话」（由 MusicSend 绑定）。采集在主线程读宿主对象，
 * 加密与网络在 IO 线程完成，回调统一回到 UI 线程。
 */
final class ConversationBackup {
    /** 明文 JSON 上限：保证加密 + V2 信封后仍在服务端 600000 上限内。 */
    static final int MAX_PLAIN = 280000;
    /** 加密后 blob（base64 字符串）上限。 */
    static final int MAX_BLOB = 400000;

    private static final ExecutorService IO = Executors.newFixedThreadPool(1);

    interface ExportCallback { void done(String link, String code, int count, String error); }
    interface RestoreCallback { void done(int count, String error); }

    private ConversationBackup() { }

    // ---------------------------------------------------------------- 导出

    static void export(final Activity activity, final ExportCallback callback) {
        final JSONObject payload;
        try {
            payload = collect();
        } catch (Throwable t) {
            callback.done(null, null, 0, readable(t));
            return;
        }
        final byte[] plain;
        try {
            plain = payload.toString().getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            callback.done(null, null, 0, "编码失败");
            return;
        }
        if (plain.length > MAX_PLAIN) {
            callback.done(null, null, 0, "当前会话过大（约 " + (plain.length / 1024) + " KB，上限 "
                    + (MAX_PLAIN / 1024) + " KB）。请先精简会话内容再备份。");
            return;
        }
        final String code = BackupCrypto.newCode();
        final int count = payload.optInt("count", 0);
        IO.execute(new Runnable() {
            public void run() {
                try {
                    final String blob = BackupCrypto.seal(plain, code);
                    if (blob.length() > MAX_BLOB) {
                        ui(activity, new Runnable() { public void run() {
                            callback.done(null, null, 0, "加密后仍超过服务器上限，请精简后再试");
                        }});
                        return;
                    }
                    final String blobHash = BackupCrypto.blobHash(blob);
                    final String codeHash = BackupCrypto.codeHash(code);
                    ui(activity, new Runnable() { public void run() {
                        BackupApi.put(activity, blob, blobHash, codeHash, new BackupApi.Callback() {
                            public void done(JSONObject result, String error) {
                                if (error != null) { callback.done(null, null, 0, error); return; }
                                String id = result == null ? "" : result.optString("id", "");
                                if (id.length() == 0) { callback.done(null, null, 0, "服务器未返回备份链接"); return; }
                                callback.done(BackupApi.linkFor(id), code, count, null);
                            }
                        });
                    }});
                } catch (Throwable t) {
                    final String message = readable(t);
                    ui(activity, new Runnable() { public void run() {
                        callback.done(null, null, 0, "加密失败：" + message);
                    }});
                }
            }
        });
    }

    /** 读取当前会话并序列化。必须在主线程调用。 */
    static JSONObject collect() throws Exception {
        Object owner = MusicSend.owner();
        if (owner == null) throw new IllegalStateException("请先打开一个会话");
        Object session = HotHelpers.callMethod(owner, "M");
        if (session == null) throw new IllegalStateException("请先打开一个会话");
        String sessionId = string(HotHelpers.getObjectField(session, "a"));
        String title = "";
        try {
            Object value = HotHelpers.callMethod(session, "h");
            if (value instanceof String) title = (String) value;
        } catch (Throwable ignored) { }
        Map<?, ?> messages = (Map<?, ?>) HotHelpers.getObjectField(session, "f");
        JSONArray array = new JSONArray();
        if (messages != null) {
            for (Object message : messages.values()) {
                String role = roleOf(message);
                String text = "USER".equals(role) ? userText(message) : assistantText(message);
                if (text == null || text.trim().length() == 0) continue;
                JSONObject item = new JSONObject();
                item.put("role", role);
                item.put("text", text);
                array.put(item);
            }
        }
        if (array.length() == 0) throw new IllegalStateException("当前会话还没有可备份的消息");
        JSONObject root = new JSONObject();
        root.put("v", 1);
        root.put("source", "dekk");
        root.put("app", "com.deepseek.chat");
        root.put("app_version", "2.5.1");
        root.put("exported_at", System.currentTimeMillis() / 1000L);
        root.put("session_id", sessionId);
        root.put("title", title);
        root.put("count", array.length());
        root.put("messages", array);
        return root;
    }

    private static String roleOf(Object message) {
        try {
            Object value = HotHelpers.callMethod(message, "D");
            return value instanceof String ? (String) value : "ASSISTANT";
        } catch (Throwable t) {
            return "ASSISTANT";
        }
    }

    private static String userText(Object message) {
        try {
            Object value = HotHelpers.callMethod(message, "r");
            return value instanceof String ? (String) value : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** AI 正文：vq.C() 会合并全部内容块（含代码/引用块）。 */
    private static String assistantText(Object message) {
        try {
            Object value = HotHelpers.callMethod(message, "C");
            return value instanceof String ? (String) value : "";
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 导入

    static void restore(final Activity activity, final String link, final String code,
            final RestoreCallback callback) {
        final String id = BackupApi.parseId(link);
        if (id == null) { callback.done(0, "备份链接格式不正确"); return; }
        if (BackupCrypto.normalizeCode(code).length() == 0) { callback.done(0, "请填写授权码"); return; }
        IO.execute(new Runnable() {
            public void run() {
                try {
                    final String codeHash = BackupCrypto.codeHash(code);
                    ui(activity, new Runnable() { public void run() {
                        BackupApi.get(activity, id, codeHash, new BackupApi.Callback() {
                            public void done(JSONObject result, String error) {
                                if (error != null) { callback.done(0, error); return; }
                                final String blob = result == null ? "" : result.optString("blob", "");
                                final String blobHash = result == null ? "" : result.optString("blob_hash", "");
                                if (blob.length() == 0) { callback.done(0, "服务器返回的备份为空"); return; }
                                IO.execute(new Runnable() { public void run() {
                                    try {
                                        if (blobHash.length() > 0 && !blobHash.equalsIgnoreCase(BackupCrypto.blobHash(blob)))
                                            throw new BackupCrypto.BackupCryptoException("备份校验失败，文件可能已损坏");
                                        byte[] plain = BackupCrypto.open(blob, code);
                                        final String json = new String(plain, "UTF-8");
                                        final int count = ImportContextStore.countOf(json);
                                        ImportContextStore.save(activity.getApplicationContext(), json);
                                        ui(activity, new Runnable() { public void run() {
                                            callback.done(count, null);
                                        }});
                                    } catch (Throwable t) {
                                        final String message = readable(t);
                                        ui(activity, new Runnable() { public void run() {
                                            callback.done(0, message);
                                        }});
                                    }
                                }});
                            }
                        });
                    }});
                } catch (Throwable t) {
                    final String message = readable(t);
                    ui(activity, new Runnable() { public void run() {
                        callback.done(0, message);
                    }});
                }
            }
        });
    }

    // ---------------------------------------------------------------- 工具

    private static String string(Object value) { return value instanceof String ? (String) value : ""; }

    private static String readable(Throwable t) {
        if (t instanceof BackupCrypto.BackupCryptoException) return t.getMessage();
        if (t instanceof IllegalStateException) return t.getMessage();
        String message = t.getMessage();
        return message == null || message.length() == 0 ? t.getClass().getSimpleName() : message;
    }

    private static void ui(Activity activity, Runnable runnable) {
        if (activity == null) return;
        activity.runOnUiThread(runnable);
    }
}