package com.dekk.yanyu;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 导入备份后的本地暂存。
 *
 * 导入的会话内容不会出现在 DeepSeek 服务端历史里（它不是新账号的消息），
 * 所以这里只负责把解密后的明文暂存在应用私有目录，供两种用途：
 *   1) 打包成 txt 交给宿主上传成「文件附件」（主路径，见 ImportRenderer.uploadFile）；
 *   2) 可选地渲染成当前会话里的原生消息（见 ImportRenderer.render）。
 *
 * 注意：**不再**把上下文塞进隐藏提示词 —— 几百上千条会撑爆请求，导致对话不可用。
 */
final class ImportContextStore {
    private static final String PREFS = "dekk_import";
    private static final String KEY_READY = "ready";
    private static final String FILE_NAME = "import_pending.json";

    /** 免责声明原文，导入前必须展示。 */
    static final String DISCLAIMER =
            "导入的聊天记录会写成一个 txt 文件，发送消息时携带。\n"
            + "导入之前，需要先开启一个新会话，并且发送一条消息。\n"
            + "请勿导入包含隐私、密码、密钥等敏感信息的内容。\n"
            + "如发生泄露或其他问题，所有后果由用户自行承担。";

    private ImportContextStore() { }

    static boolean ready(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READY, false)
                && file(context).exists();
    }

    /** 保存解密后的明文 JSON。 */
    static void save(Context context, String json) throws Exception {
        FileOutputStream out = new FileOutputStream(file(context));
        try {
            out.write(json.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READY, true).apply();
    }

    private static String read(Context context) {
        File target = file(context);
        if (!target.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(target);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
            } finally {
                in.close();
            }
            return bytes.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取已暂存的明文 JSON（未导入时返回 null）。 */
    static String loadJson(Context context) {
        if (!ready(context)) return null;
        return read(context);
    }

    /** 清除暂存（用户主动放弃导入时用）。 */
    static void consume(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READY, false).apply();
        File target = file(context);
        if (target.exists()) target.delete();
    }

    /** 备份 JSON 里的消息条数（用于界面提示）。 */
    static int countOf(String json) {
        try {
            JSONArray messages = new JSONObject(json).optJSONArray("messages");
            return messages == null ? 0 : messages.length();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
}