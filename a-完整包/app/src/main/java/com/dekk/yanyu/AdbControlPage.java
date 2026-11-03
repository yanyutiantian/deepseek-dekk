package com.dekk.yanyu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** Third level page: connection state, the two AI permissions and manual operations. */
public final class AdbControlPage {
    public static void show(Activity activity) {
        new AdbControlPage(activity);
    }

    private final Activity activity;
    private final CenterPage page;

    private AdbControlPage(final Activity activity) {
        this.activity = activity;
        this.page = new CenterPage(activity, "ADB操控", null);

        page.section("连接状态");
        LinearLayout status = page.card();
        final TextView state = page.label(cachedStatus(), 14);
        state.setTextColor(page.secondary);
        state.setPadding(0, page.dp(8), 0, page.dp(8));
        status.addView(state);
        page.row(status, "测试连接  ›", new Runnable() {
            public void run() {
                state.setText("正在测试…");
                start(new Call() {
                    public AdbControl.Result call() { return AdbControl.test(activity); }
                }, state, null);
            }
        });
        page.row(status, "刷新状态  ›", new Runnable() {
            public void run() {
                state.setText("正在检测…");
                start(new Call() {
                    public AdbControl.Result call() { return AdbControl.status(activity); }
                }, state, null);
            }
        });
        page.row(status, "扩展设置说明", null).setTextColor(page.secondary);

        page.section("AI权限（默认关闭）");
        LinearLayout ai = page.card();
        toggle(ai, "允许AI冻结应用", AdbControlStore.allowFreeze(activity),
                new Toggle() {
                    public void set(boolean value) { AdbControlStore.setAllowFreeze(activity, value); }
                });
        toggle(ai, "允许AI查看应用使用时长", AdbControlStore.allowUsage(activity),
                new Toggle() {
                    public void set(boolean value) { AdbControlStore.setAllowUsage(activity, value); }
                });
        TextView note = page.label("开启后，AI 可在回答末尾给出指令；指令行不会显示在气泡里，执行结果会在下一次请求中带给 AI。冻结只针对第三方应用，到期自动恢复。", 12);
        note.setTextColor(page.secondary);
        note.setPadding(0, page.dp(10), 0, page.dp(10));
        ai.addView(note);

        page.section("手动操作");
        LinearLayout manual = page.card();
        final TextView output = page.label("", 14);
        output.setTextColor(page.secondary);
        output.setPadding(0, page.dp(8), 0, page.dp(8));
        page.row(manual, "查看今天的应用使用时长前10  ›", new Runnable() {
            public void run() { refresh(output, CMD_USAGE); }
        });
        page.row(manual, "查看当前冻结  ›", new Runnable() {
            public void run() { refresh(output, CMD_FROZEN); }
        });
        page.row(manual, "解除全部冻结  ›", new Runnable() {
            public void run() { refresh(output, CMD_UNFREEZE_ALL); }
        });
        page.row(manual, "手动冻结应用  ›", new Runnable() {
            public void run() { pickApp(output); }
        });
        page.row(manual, "刷新使用时长快照  ›", new Runnable() {
            public void run() {
                if (!AdbControlStore.allowUsage(activity)) {
                    output.setText("请先打开“允许AI查看应用使用时长”");
                    return;
                }
                AdbControl.refreshUsageAsync(activity);
                output.setText("已在后台刷新，下一条消息会带上最新快照");
            }
        });
        manual.addView(output);

        page.show();
        // 打开页面就检测一次，避免一直停在“正在检测…”。
        start(new Call() {
            public AdbControl.Result call() { return AdbControl.status(activity); }
        }, state, null);
    }

    /** Shows the last known result immediately, then it is replaced by a fresh check. */
    private String cachedStatus() {
        String text = AdbControlStore.statusText(activity);
        long at = AdbControlStore.statusAt(activity);
        if (text.length() == 0) return "正在检测…";
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date(at));
        return "上次检测 " + time + "\n" + text;
    }

    private static final String CMD_USAGE = "usage";
    private static final String CMD_FROZEN = "frozen";
    private static final String CMD_UNFREEZE_ALL = "unfreezeAll";

    private interface Call {
        AdbControl.Result call();
    }

    private interface Toggle {
        void set(boolean value);
    }

    private void toggle(LinearLayout card, String title, boolean checked, final Toggle toggle) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, page.dp(10), 0, page.dp(10));
        TextView label = page.label(title, 16);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        final M3SwitchView switchView = new M3SwitchView(activity);
        switchView.setContentDescription(title);
        switchView.setTrackColors(0xFF3482FF, page.night ? 0x33FFFFFF : 0x1F000000);
        switchView.setChecked(checked);
        switchView.setOnCheckedChangeListener(new M3SwitchView.OnCheckedChangeListener() {
            public void onCheckedChanged(M3SwitchView view, boolean value) {
                toggle.set(value);
            }
        });
        row.addView(switchView, new LinearLayout.LayoutParams(page.dp(52), page.dp(32)));
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchView.performClick(); }
        });
        card.addView(row);
    }

    private void refresh(final TextView output, final String command) {
        output.setText("正在执行…");
        start(new Call() {
            public AdbControl.Result call() {
                if (CMD_USAGE.equals(command)) return AdbControl.usage(activity, 10);
                if (CMD_FROZEN.equals(command)) return AdbControl.frozen(activity);
                return AdbControl.unfreezeAll(activity);
            }
        }, output, null);
    }

    private void start(final Call call, final TextView output, final Runnable after) {
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final AdbControl.Result result;
                try {
                    result = call.call();
                } catch (Throwable t) {
                    activity.runOnUiThread(new Runnable() {
                        public void run() { output.setText("执行失败"); }
                    });
                    return;
                }
                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        if (page.active()) output.setText(result.text);
                        if (after != null) after.run();
                    }
                });
            }
        }, "dekk-adb-ui");
        worker.setDaemon(true);
        worker.start();
    }

    private void pickApp(final TextView output) {
        output.setText("正在读取应用列表…");
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final List<AdbControl.AppItem> items = AdbControl.apps();
                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        if (!page.active()) return;
                        if (items.isEmpty()) {
                            output.setText("没有可冻结的应用，或扩展未连接");
                            return;
                        }
                        output.setText("共 " + items.size() + " 个可冻结应用");
                        String[] labels = new String[items.size()];
                        for (int i = 0; i < items.size(); i++) {
                            labels[i] = items.get(i).label + "\n" + items.get(i).packageName;
                        }
                        new ModuleDialog(activity)
                                .setTitle("选择要冻结的应用")
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        askMinutes(items.get(which), output);
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        }, "dekk-adb-apps");
        worker.setDaemon(true);
        worker.start();
    }

    private void askMinutes(final AdbControl.AppItem item, final TextView output) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(AdbProtocol.MIN_MINUTES));
        new ModuleDialog(activity)
                .setTitle(item.label + " 冻结分钟数（" + AdbProtocol.MIN_MINUTES + "-"
                        + AdbProtocol.MAX_MINUTES + "）")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("冻结", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        int minutes;
                        try {
                            minutes = Integer.parseInt(input.getText().toString().trim());
                        } catch (Throwable t) {
                            output.setText("请输入数字");
                            return;
                        }
                        final int length = AdbProtocol.clampMinutes(minutes);
                        output.setText("正在冻结…");
                        start(new Call() {
                            public AdbControl.Result call() {
                                return AdbControl.freeze(activity, item.packageName, length);
                            }
                        }, output, null);
                    }
                })
                .show();
    }
}