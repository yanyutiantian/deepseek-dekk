package com.dekk.yanyu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「备份与导入」页。
 *
 * 备份：读取当前打开的会话 → 本地高强度加密 → 上传服务器 → 弹窗只给「复制」（链接+授权码）与「关闭」。
 * 导入：输入链接与授权码 → 下载解密 → 本机暂存 → 「上传为文件附件」把全部消息写成 txt 交给宿主上传。
 *
 * 注意：模块只加密上传密文，服务器看不到明文，也拿不到授权码本身（只存 SHA-256）。
 */
public final class BackupPage {
    private BackupPage() { }

    public static void show(final Activity activity) {
        final CenterPage page = new CenterPage(activity, "备份与导入", null);

        page.section("重要提醒");
        LinearLayout intro = page.card();
        page.caption(intro, ImportContextStore.DISCLAIMER);

        page.section("备份当前会话");
        LinearLayout backup = page.card();
        final TextView status = page.label("", 13);
        status.setTextColor(page.secondary);
        status.setPadding(0, page.dp(14), 0, page.dp(8));
        backup.addView(status);
        renderStatus(status);
        page.row(backup, "加密备份并上传", new Runnable() {
            public void run() {
                status.setText("正在读取并加密当前会话…");
                ConversationBackup.export(activity, new ConversationBackup.ExportCallback() {
                    public void done(String link, String code, int count, String error) {
                        if (error != null) { status.setText("备份失败：" + error); page.message(error); return; }
                        renderStatus(status);
                        showResult(activity, link, code, count);
                    }
                });
            }
        });

        page.section("导入备份");
        LinearLayout restore = page.card();
        final EditText linkField = page.field(restore, "备份链接（或备份编号）", android.text.InputType.TYPE_CLASS_TEXT, false);
        final EditText codeField = page.field(restore, "授权码", android.text.InputType.TYPE_CLASS_TEXT, false);
        page.row(restore, "导入到本机", new Runnable() {
            public void run() {
                final String link = linkField.getText().toString().trim();
                final String code = codeField.getText().toString().trim();
                if (link.length() == 0) { page.message("请填写备份链接或备份编号"); return; }
                if (code.length() == 0) { page.message("请填写授权码"); return; }
                page.message("正在下载并解密…");
                ConversationBackup.restore(activity, link, code, new ConversationBackup.RestoreCallback() {
                    public void done(int count, String error) {
                        if (error != null) { page.message("导入失败：" + error); return; }
                        new AlertDialog.Builder(activity)
                                .setTitle("导入成功")
                                .setMessage("已导入 " + count + " 条消息。\n\n"
                                        + "上下文会通过文件上传，"
                                        + "保证数据完整。")
                                .setPositiveButton("上传为文件附件", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) { upload(activity, page); }
                                })
                                .setNegativeButton("稍后", null)
                                .show();
                    }
                });
            }
        });
        page.show();
    }

    private static void upload(final Activity activity, final CenterPage page) {
        String json = ImportContextStore.loadJson(activity.getApplicationContext());
        if (json == null || json.length() == 0) { page.message("还没有导入内容，请先导入备份"); return; }
        page.message("正在生成文件并上传为附件…");
        ImportRenderer.uploadFile(activity, json, new ImportRenderer.Callback() {
            public void done(int count, int total, String error) {
                if (error != null) { page.message(error); return; }
                page.message("已作为文件附件上传（" + count + " 条）。发送消息时会随附件一起提交给 AI");
            }
        });
    }

    private static void renderStatus(TextView status) {
        Object owner = MusicSend.owner();
        if (owner == null) { status.setText("当前没有打开任何会话。请先进入一个 DeepSeek 会话，再回来备份。"); return; }
        status.setText("已绑定当前会话，点下面的按钮即可加密并上传。\n"
                + "备份只包含本会话的角色、正文与顺序，不含登录信息与设备信息。");
    }

    /** 备份完成：只提供「复制」（链接 + 授权码一起复制到剪贴板）与「关闭」。 */
    private static void showResult(final Activity activity, final String link, final String code, int count) {
        new AlertDialog.Builder(activity)
                .setTitle("备份完成（" + count + " 条消息）")
                .setMessage(resultText(link, code)
                        + "\n\n点「复制」会把上面的链接和授权码一起复制到剪贴板。\n"
                        + "· 授权码只显示这一次，请立即妥善保存。\n"
                        + "· 备份会在 24 小时后从服务器删除。")
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) { copy(activity, resultText(link, code)); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 链接与授权码展示/复制的统一格式。 */
    private static String resultText(String link, String code) {
        return "备份链接：" + link + "            授权码：" + code;
    }

    private static void copy(Context context, String text) {
        try {
            ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("dekk-backup", text));
        } catch (Throwable ignored) { }
    }
}
