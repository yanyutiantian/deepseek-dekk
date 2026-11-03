package com.dekk.yanyu;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 「自定义美化 UI（HTML）」设置页：开关、正则、模板、预览、复制提示词。 */
final class HtmlUiPage {

    private HtmlUiPage() { }

    static void show(final Activity a) {
        final CenterPage p = new CenterPage(a, "自定义美化 UI", null).navigationKey("html-ui");
        final boolean dark = p.night;

        p.section("状态");
        LinearLayout state = p.card();
        MiuixUi.switchRow(a, state, dark, MiuixIcon.PALETTE, "启用 HTML UI",
                HtmlUiStore.enabled(a), new M3SwitchView.OnCheckedChangeListener() {
            public void onCheckedChanged(M3SwitchView view, boolean checked) {
                HtmlUiStore.setEnabled(a, checked);
            }
        });
        MiuixUi.switchRow(a, state, dark, MiuixIcon.IMAGE, "在聊天页接管（实验）",
                HtmlUiStore.takeover(a), new M3SwitchView.OnCheckedChangeListener() {
            public void onCheckedChanged(M3SwitchView view, boolean checked) {
                HtmlUiStore.setTakeover(a, checked);
                Toast.makeText(a, checked ? "已开启接管：回到聊天页生效" : "已关闭接管", Toast.LENGTH_SHORT).show();
            }
        });
        p.caption(state, "开启后即可在下面「预览效果」里看到界面；「接管」会把聊天区换成你的 HTML"
                + "（顶部标题栏与底部输入框保留，随时可以关掉恢复原样）。");

        p.section("渲染区域");
        LinearLayout layoutCard = p.card();
        p.rowIcon(layoutCard, MiuixIcon.GAUGE, "顶部留白：" + HtmlUiStore.topInset(a) + " dp ›",
                new Runnable() { public void run() { editInset(a, true); } });
        p.rowIcon(layoutCard, MiuixIcon.GAUGE, "底部留白：" + HtmlUiStore.bottomInset(a) + " dp ›",
                new Runnable() { public void run() { editInset(a, false); } });
        p.rowIcon(layoutCard, MiuixIcon.SEARCH, "聊天输入框提示语：" + HtmlUiStore.composerHints(a) + " ›",
                new Runnable() { public void run() { editHints(a); } });
        p.caption(layoutCard, "接管模式下渲染层会占住这两条留白之间的屏幕。"
                + "如果渲染层把聊天输入框/工具栏盖住了，把「底部留白」调大；"
                + "顶部把宿主标题栏盖住了就调大「顶部留白」。\n"
                + "「提示语」用来判定当前是不是聊天页（用 | 分隔多个关键词）："
                + "只有屏幕上出现带这些关键词的输入框时才会渲染，"
                + "所以切到历史/我的等页面渲染层会自动消失。判错时改这里即可。");

        p.section("头像与壁纸");
        LinearLayout assets = p.card();
        MiuixUi.switchRow(a, assets, dark, MiuixIcon.PERSON, "调用美化头像",
                HtmlUiStore.useAvatar(a), new M3SwitchView.OnCheckedChangeListener() {
            public void onCheckedChanged(M3SwitchView view, boolean checked) {
                HtmlUiStore.setUseAvatar(a, checked);
            }
        });
        MiuixUi.switchRow(a, assets, dark, MiuixIcon.IMAGE, "调用聊天壁纸",
                HtmlUiStore.useWallpaper(a), new M3SwitchView.OnCheckedChangeListener() {
            public void onCheckedChanged(M3SwitchView view, boolean checked) {
                HtmlUiStore.setUseWallpaper(a, checked);
            }
        });
        p.caption(assets, "模板里用 {{avatar}}（AI头像）、{{user_avatar}}（你的头像）、{{wallpaper}} 或 "
                + "{{wallpaper_css}}（壁纸）引用，渲染时会替换成真实内容。");

        p.section("消息正则");
        LinearLayout regexCard = p.card();
        final EditText userRegex = p.field(regexCard, "用户消息正则（第 1 个括号＝正文）",
                InputType.TYPE_CLASS_TEXT, false);
        userRegex.setText(HtmlUiStore.userRegex(a));
        final EditText aiRegex = p.field(regexCard, "AI 消息正则（第 1 个括号＝正文）",
                InputType.TYPE_CLASS_TEXT, false);
        aiRegex.setText(HtmlUiStore.aiRegex(a));
        p.rowIcon(regexCard, MiuixIcon.EDIT, "保存正则 ›", new Runnable() {
            public void run() {
                HtmlUiStore.setRegex(a, userRegex.getText().toString().trim(),
                        aiRegex.getText().toString().trim());
                Toast.makeText(a, "正则已保存", Toast.LENGTH_SHORT).show();
            }
        });
        p.rowIcon(regexCard, MiuixIcon.CHECK, "查看匹配结果 ›", new Runnable() {
            public void run() { showMatchResult(a); }
        });
        p.caption(regexCard, "渲染前先把整段聊天记录（含历史）按顺序拼成 "
                + "[DEEK-USER]…[/DEEK-USER] 与 [DEEK-AI]…[/DEEK-AI]，再用上面的正则把消息挑出来；"
                + "两条正则都没命中时，会自动改用宿主自带的角色信息，保证用户和 AI 的消息都不会丢。");

        p.section("HTML 模板");
        LinearLayout templateCard = p.card();
        final EditText editor = p.field(templateCard, "HTML 模板（含 CSS / JS，需实现 render(messages)）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, true);
        editor.setText(HtmlUiStore.template(a));
        p.rowIcon(templateCard, MiuixIcon.EDIT, "保存模板 ›", new Runnable() {
            public void run() {
                String value = editor.getText().toString();
                if (value.trim().length() == 0) {
                    Toast.makeText(a, "模板不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                HtmlUiStore.setTemplate(a, value);
                Toast.makeText(a, "模板已保存", Toast.LENGTH_SHORT).show();
            }
        });
        p.rowIcon(templateCard, MiuixIcon.DOWNLOAD, "恢复默认（苹果信息）模板 ›", new Runnable() {
            public void run() {
                HtmlUiStore.resetTemplate(a);
                Toast.makeText(a, "已恢复默认模板", Toast.LENGTH_SHORT).show();
                show(a);
            }
        });
        p.rowIcon(templateCard, MiuixIcon.DOC, "复制当前模板 ›", new Runnable() {
            public void run() { copy(a, editor.getText().toString(), "模板已复制"); }
        });
        String regexHint = "（附带默认正则）";
        p.rowIcon(templateCard, MiuixIcon.STAR, "复制 AI 提示词" + regexHint + " ›", new Runnable() {
            public void run() { copy(a, HtmlUiPrompt.TEXT, "提示词已复制，发给任意 AI 即可"); }
        });
        p.caption(templateCard, "提示词里写清了占位符、消息结构和正则约定，AI 会直接产出可粘贴的 HTML 与两条正则。");

        p.action("预览效果", new Runnable() {
            public void run() { preview(a); }
        });
        p.show();
    }

    /** 聊天页判定关键词：文本输入，| 分隔。 */
    private static void editHints(final Activity a) {
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(HtmlUiStore.composerHints(a));
        input.setSelection(input.getText().length());
        new ModuleDialog(a).setTitle("聊天输入框提示语关键词")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        HtmlUiStore.setComposerHints(a, input.getText().toString().trim());
                        Toast.makeText(a, "已保存，回到聊天页生效", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    /** 顶部 / 底部留白：数字输入，保存后立即重建渲染层。 */
    private static void editInset(final Activity a, final boolean top) {
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        final int current = top ? HtmlUiStore.topInset(a) : HtmlUiStore.bottomInset(a);
        input.setText(String.valueOf(current));
        input.setSelection(input.getText().length());
        new ModuleDialog(a).setTitle(top ? "顶部留白（dp）" : "底部留白（dp）")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        int value;
                        try {
                            value = Integer.parseInt(input.getText().toString().trim());
                        } catch (Throwable ignored) {
                            value = current;
                        }
                        int topInset = top ? value : HtmlUiStore.topInset(a);
                        int bottomInset = top ? HtmlUiStore.bottomInset(a) : value;
                        HtmlUiStore.setInsets(a, topInset, bottomInset);
                        Toast.makeText(a, "已保存，回到聊天页生效", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    /** 预览页：真实模板 + 真实头像/壁纸渲染，可用示例消息或已采集的聊天记录。 */
    static void preview(final Activity a) {
        final CenterPage p = new CenterPage(a, "预览", null);
        final WebView web = HtmlUiHook.createWebView(a);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(-1, p.dp(430));
        webParams.bottomMargin = p.dp(14);
        p.body.addView(web, webParams);

        final int count = HtmlUiHook.messages().size();
        LinearLayout card = p.card();
        p.rowIcon(card, MiuixIcon.DOC, "用示例消息预览 ›", new Runnable() {
            public void run() { HtmlUiHook.previewInto(web, a, true); }
        });
        p.rowIcon(card, MiuixIcon.CHAT, "用真实聊天记录预览 ›", new Runnable() {
            public void run() {
                if (HtmlUiHook.messages().isEmpty()) {
                    Toast.makeText(a, "还没采集到聊天记录，先去聊天页逛一圈", Toast.LENGTH_LONG).show();
                    return;
                }
                HtmlUiHook.previewInto(web, a, false);
            }
        });
        p.rowIcon(card, MiuixIcon.EDIT, "重新载入模板 ›", new Runnable() {
            public void run() { HtmlUiHook.previewInto(web, a, HtmlUiHook.messages().isEmpty()); }
        });
        p.caption(card, count == 0
                ? "当前还没有采集到聊天记录，先用示例消息看效果。开启「启用 HTML UI」后进入聊天页即可采集。"
                : "已采集到 " + count + " 条消息（含历史），可直接用真实记录预览。");
        p.show();
        HtmlUiHook.previewInto(web, a, count == 0);
    }

    /** 用当前正则跑一遍已采集的记录，看看挑出来多少条。 */
    private static void showMatchResult(Activity a) {
        java.util.List<HtmlUiMessages.Msg> collected = HtmlUiHook.messages();
        if (collected.isEmpty()) {
            Toast.makeText(a, "还没有采集到聊天记录", Toast.LENGTH_LONG).show();
            return;
        }
        String transcript = HtmlUiMessages.transcript(collected);
        java.util.List<HtmlUiMessages.Msg> matched = HtmlUiMessages.classify(transcript,
                HtmlUiStore.userRegex(a), HtmlUiStore.aiRegex(a));
        StringBuilder builder = new StringBuilder();
        builder.append("聊天记录共 ").append(collected.size()).append(" 条；正则命中 ")
                .append(matched.size()).append(" 条。\n");
        int shown = 0;
        for (int i = 0; i < collected.size() && shown < 3; i++, shown++) {
            HtmlUiMessages.Msg message = collected.get(i);
            String text = message.text.length() > 40 ? message.text.substring(0, 40) + "…" : message.text;
            builder.append('\n').append("user".equals(message.role) ? "我：" : "AI：").append(text.replace('\n', ' '));
        }
        if (matched.isEmpty()) {
            builder.append("\n\n两条正则都没命中，渲染时会自动改用宿主的角色信息。");
        }
        new ModuleDialog(a).setTitle("正则匹配结果").setMessage(builder.toString())
                .setPositiveButton("知道了", null).show();
    }

    private static void copy(Context context, String text, String toast) {
        try {
            ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("DEKK", text));
            }
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }
}