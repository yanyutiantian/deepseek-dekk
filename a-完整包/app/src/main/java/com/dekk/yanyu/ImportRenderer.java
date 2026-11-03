package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 导入结果落地：把解密后的聊天记录写成 txt，作为文件附件交给宿主上传。
 *
 * 明确的方案（不再使用隐藏提示词）：
 *   - 解密出来的**全部消息**都写进 txt，一条不落；
 *   - txt 用可读的对话格式（[用户]/[助手]），AI 读文件时能直接理解；
 *   - 交给宿主自己的上传链路变成真附件（AttachmentBridge），发送消息时随附件携带；
 *   - 不再把上下文塞进隐藏提示词（几百上千条会撑爆请求，导致对话不可用）。
 *
 * render() 只是可选附加项：把导入内容也在当前会话里显示出来。
 */
final class ImportRenderer {
    /** 可选的本地渲染上限，仅影响「显示」，不影响上传的 txt 内容（txt 始终是全量）。 */
    static final int MAX_RENDER = 300;

    interface Callback { void done(int count, int total, String error); }

    private ImportRenderer() { }

    /** 把异常链（含 cause）压成一行，反射调用失败时真正的错在 cause 里。 */
    private static String describe(Throwable t) {
        StringBuilder builder = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 5) {
            if (depth > 0) builder.append(" <- ");
            builder.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && message.length() > 0) builder.append(": ").append(message);
            current = current.getCause();
            depth++;
        }
        return builder.toString();
    }

    /** 全量转成可读对话文本（写入 txt 用，也是 AI 实际读到的内容）。 */
    static String toReadable(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray messages = root.optJSONArray("messages");
            StringBuilder builder = new StringBuilder();
            builder.append("[聊天记录导入]\n");
            String title = root.optString("title", "");
            if (title.length() > 0) builder.append("来源会话：").append(title).append('\n');
            builder.append("来源标识：").append(root.optString("session_id", "")).append('\n');
            long exported = root.optLong("exported_at", 0L);
            builder.append("导出时间：").append(exported > 0 ? new java.util.Date(exported * 1000L).toString() : "未知").append('\n');
            int total = messages == null ? 0 : messages.length();
            builder.append("消息条数：").append(total).append("\n\n");
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject item = messages.optJSONObject(i);
                    if (item == null) continue;
                    String role = item.optString("role", "USER");
                    String text = item.optString("text", "");
                    if (text.length() == 0) continue;
                    builder.append("USER".equals(role) ? "[用户] " : "[助手] ").append(text).append("\n\n");
                }
            }
            return builder.toString();
        } catch (Throwable t) {
            // 解析失败时退回原 JSON，至少让文件里有内容
            return json;
        }
    }

    /** 把导入内容写成 txt（全量、可读格式）。 */
    static File writeTxt(Context context, String json) throws Exception {
        File dir = new File(context.getFilesDir(), "dekk_import");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, "dekk-import-" + System.currentTimeMillis() + ".txt");
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(toReadable(json).getBytes("UTF-8"));
        } finally {
            out.close();
        }
        return file;
    }

    /**
     * 默认路径：只做「写 txt + 作为文件附件上传」，不做任何本地渲染。
     * 全部消息进文件，随用户的消息一起发给 AI。
     */
    static void uploadFile(final Activity activity, final String json, final Callback callback) {
        if (activity == null || json == null) { callback.done(0, 0, "没有可上传的内容"); return; }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                int total = ImportContextStore.countOf(json);
                if (!AttachmentBridge.available()) {
                    callback.done(0, total, "请先打开一个会话，再上传附件");
                    return;
                }
                try {
                    File txt = writeTxt(activity.getApplicationContext(), json);
                    final int count = total;
                    AttachmentBridge.attach(activity.getApplicationContext(), txt, new AttachmentBridge.Result() {
                        public void done(boolean ok, String error) {
                            if (ok) callback.done(count, count, null);
                            else callback.done(0, count, error);
                        }
                    });
                } catch (Throwable t) {
                    HotBridge.log("[DEKK][导入] 生成 txt 失败: " + t);
                    callback.done(0, total, "生成文件失败：" + t.getClass().getSimpleName());
                }
            }
        });
    }

    /**
     * 可选附加：在当前会话里把导入内容渲染成原生消息，同时仍上传全量文件。
     */
    static void render(final Activity activity, final String json, final Callback callback) {
        if (activity == null || json == null) { callback.done(0, 0, "没有可渲染的内容"); return; }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                int total = ImportContextStore.countOf(json);
                if (!ConversationInjector.ready()) { callback.done(0, total, "模块尚未接管宿主，请重启 App"); return; }
                Object owner = MusicSend.owner();
                if (owner == null) { callback.done(0, total, "请先打开一个会话，再渲染"); return; }
                Object session;
                try {
                    session = HotHelpers.callMethod(owner, "M");
                } catch (Throwable t) {
                    callback.done(0, total, "读取当前会话失败"); return;
                }
                if (session == null) { callback.done(0, total, "请先打开一个会话，再渲染"); return; }

                int rendered = 0;
                try {
                    JSONArray messages = new JSONObject(json).optJSONArray("messages");
                    int size = messages == null ? 0 : messages.length();
                    int from = Math.max(0, size - MAX_RENDER);
                    int seq = ConversationInjector.maxSeq(session);
                    for (int i = from; i < size; i++) {
                        JSONObject item = messages.optJSONObject(i);
                        if (item == null) continue;
                        String role = item.optString("role", "USER");
                        String text = item.optString("text", "");
                        if (text.trim().length() == 0) continue;
                        ConversationInjector.inject(session, "USER".equals(role) ? "USER" : "ASSISTANT", text, ++seq);
                        rendered++;
                    }
                } catch (Throwable t) {
                    HotBridge.log("[DEKK][导入] 渲染失败: " + describe(t));
                    callback.done(rendered, total, "渲染失败：" + describe(t));
                    return;
                }
                // 渲染之外仍然走文件附件（全量）
                try {
                    File txt = writeTxt(activity.getApplicationContext(), json);
                    AttachmentBridge.attach(activity.getApplicationContext(), txt, new AttachmentBridge.Result() {
                        public void done(boolean ok, String error) {
                            if (!ok) HotBridge.log("[DEKK][导入] 附件失败: " + error);
                        }
                    });
                } catch (Throwable t) {
                    HotBridge.log("[DEKK][导入] 生成 txt 失败: " + t);
                }
                callback.done(rendered, total, null);
            }
        });
    }
}