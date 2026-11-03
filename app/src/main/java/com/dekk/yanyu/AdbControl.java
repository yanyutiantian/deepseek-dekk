package com.dekk.yanyu;

import android.content.Context;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** High level calls to the ADB extension, plus human readable summaries. */
final class AdbControl {
    static final class Result {
        final boolean ok;
        final String text;
        Result(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }
    }

    private AdbControl() { }

    static Result status(Context context) {
        AdbBridge.Reply reply = AdbBridge.call(AdbProtocol.CMD_PING, null);
        if (!reply.reachable || !reply.ok) {
            String failure = "无法连接扩展：" + reply.error;
            HotBridge.log("[DEKK][ADB] 设置页状态检测失败 -> " + reply.error);
            AdbControlStore.saveStatus(context, failure);
            return new Result(false, failure);
        }
        JSONObject body = reply.body;
        boolean shizuku = body != null && body.optBoolean(AdbProtocol.KEY_SHIZUKU, false);
        boolean usageAccess = body != null && body.optBoolean(AdbProtocol.KEY_USAGE_ACCESS, false);
        StringBuilder text = new StringBuilder();
        text.append("扩展已连接（").append(AdbProtocol.url()).append("，通道：")
                .append(AdbBridge.lastTransport).append("）");
        text.append(shizuku ? "，Shizuku 已授权" : "，Shizuku 未授权");
        text.append(usageAccess ? "，使用情况访问已授权" : "，使用情况访问未授权");
        HotBridge.log("[DEKK][ADB] 设置页状态检测成功 通道="
                + AdbBridge.lastTransport + " 返回=" + AdbBridge.preview(reply.raw));
        AdbControlStore.saveStatus(context, text.toString());
        return new Result(true, text.toString());
    }

    /** Diagnostic used by the settings page: shows exactly why the port is unreachable. */
    static Result test(Context context) {
        HotBridge.log("[DEKK][ADB] 设置页测试连接开始");
        AdbBridge.Reply reply = AdbBridge.call(AdbProtocol.CMD_PING, null);
        if (!reply.reachable) {
            HotBridge.log("[DEKK][ADB] 设置页测试连接失败 -> " + reply.error);
            return new Result(false, "无法连接扩展：" + reply.error);
        }
        HotBridge.log("[DEKK][ADB] 设置页测试连接成功 通道="
                + AdbBridge.lastTransport + " 返回=" + AdbBridge.preview(reply.raw));
        JSONObject body = reply.body;
        boolean shizuku = body != null && body.optBoolean(AdbProtocol.KEY_SHIZUKU, false);
        boolean usageAccess = body != null && body.optBoolean(AdbProtocol.KEY_USAGE_ACCESS, false);
        return new Result(true, "连接正常（" + AdbProtocol.url() + "，通道：" + AdbBridge.lastTransport + "）"
                + (shizuku ? "，Shizuku 已授权" : "，Shizuku 未授权")
                + (usageAccess ? "，使用情况访问已授权" : "，使用情况访问未授权"));
    }

    static Result usage(Context context, int limit) {
        JSONObject extra = new JSONObject();
        try {
            extra.put(AdbProtocol.KEY_LIMIT, limit);
        } catch (Throwable ignored) { }
        AdbBridge.Reply reply = AdbBridge.call(AdbProtocol.CMD_USAGE, extra);
        if (!reply.ok) return new Result(false, reply.error);
        JSONObject body = reply.body;
        if (body != null && !body.optBoolean(AdbProtocol.KEY_USAGE_ACCESS, false)) {
            return new Result(false, "扩展未获得使用情况访问权限，请在扩展中授权");
        }
        JSONArray items = body == null ? null : body.optJSONArray(AdbProtocol.KEY_ITEMS);
        if (items == null || items.length() == 0) return new Result(true, "今天还没有可统计的使用记录");
        StringBuilder text = new StringBuilder("今天使用时长前 ").append(items.length()).append("：");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (i > 0) text.append("；");
            text.append(i + 1).append(". ").append(item.optString(AdbProtocol.KEY_LABEL))
                    .append("（").append(item.optString(AdbProtocol.KEY_PACKAGE)).append("）")
                    .append(formatMinutes(item.optLong(AdbProtocol.KEY_MS)));
        }
        return new Result(true, text.toString());
    }

    static Result freeze(Context context, String packageName, int minutes) {
        JSONObject extra = new JSONObject();
        try {
            extra.put(AdbProtocol.KEY_PACKAGE, packageName);
            extra.put(AdbProtocol.KEY_MINUTES, AdbProtocol.clampMinutes(minutes));
        } catch (Throwable ignored) { }
        AdbBridge.Reply reply = AdbBridge.call(AdbProtocol.CMD_FREEZE, extra);
        if (!reply.ok) return new Result(false, reply.error);
        JSONObject body = reply.body;
        if (body == null) return new Result(false, "扩展返回为空");
        return new Result(true, "已冻结 " + body.optString(AdbProtocol.KEY_LABEL, packageName)
                + "（" + packageName + "）" + body.optInt(AdbProtocol.KEY_MINUTES) + " 分钟，到期自动恢复");
    }

    static Result unfreezeAll(Context context) {
        AdbBridge.Reply reply = AdbBridge.call(AdbProtocol.CMD_UNFREEZE_ALL, null);
        if (!reply.ok) return new Result(false, reply.error);
        JSONObject body = reply.body;
        int count = body == null ? 0 : body.optInt("count", 0);
        return new Result(true, count == 0 ? "当前没有被冻结的应用" : "已恢复 " + count + " 个应用");
    }

    static Result frozen(Context context) {
        AdbBridge.Reply reply = AdbBridge.call(AdbProtocol.CMD_FROZEN, null);
        if (!reply.ok) return new Result(false, reply.error);
        JSONObject body = reply.body;
        JSONArray items = body == null ? null : body.optJSONArray(AdbProtocol.KEY_ITEMS);
        if (items == null || items.length() == 0) return new Result(true, "当前没有被冻结的应用");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (i > 0) text.append("；");
            text.append(item.optString(AdbProtocol.KEY_LABEL)).append("（")
                    .append(item.optString(AdbProtocol.KEY_PACKAGE)).append("）剩余 ")
                    .append(item.optInt(AdbProtocol.KEY_MINUTES)).append(" 分钟");
        }
        return new Result(true, text.toString());
    }

    /** Blocking refresh of the usage snapshot; call from a background thread only. */
    static boolean refreshUsageCache(Context context) {
        Result result = usage(context, 10);
        if (!result.ok) return false;
        String takenAt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date());
        AdbControlStore.saveUsage(context, takenAt + "\n" + result.text);
        return true;
    }

    /** Request-only block; always available while the switch is on, refreshed in the background. */
    static String usageBlock(Context context) {
        if (!AdbControlStore.allowUsage(context)) return "";
        String cached = AdbControlStore.usageText(context);
        int split = cached.indexOf('\n');
        if (cached.length() == 0 || split <= 0) {
            refreshUsageAsync(context);
            return "";
        }
        if (AdbControlStore.usageStale(context)) refreshUsageAsync(context);
        return AdbUsageBlock.build(cached.substring(0, split), cached.substring(split + 1));
    }

    /** Keeps sending non-blocking: the snapshot is fetched while the user is still typing. */
    static void refreshUsageAsync(final Context context) {
        if (context == null) return;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try { refreshUsageCache(context); } catch (Throwable ignored) { }
            }
        }, "dekk-adb-usage");
        worker.setDaemon(true);
        worker.start();
    }

    /** Runs one AI requested command and queues the outcome for the next request. */
    static String run(Context context, AdbCommand.Item item) {
        Result result;
        String command;
        if (AdbProtocol.CMD_FREEZE.equals(item.type)) {
            command = "冻结 " + item.packageName + " " + item.minutes + " 分钟";
            result = freeze(context, item.packageName, item.minutes);
        } else {
            command = "查看今天应用使用时长";
            result = usage(context, 10);
        }
        String text = result.ok ? result.text : "失败：" + result.text;
        AdbControlStore.queueResult(context, AdbResultBlock.build(command, text));
        return text;
    }

    /** Both switches off means no permission text is sent at all. */
    static boolean allowed(Context context, AdbCommand.Item item) {
        if (AdbProtocol.CMD_FREEZE.equals(item.type)) return AdbControlStore.allowFreeze(context);
        return AdbControlStore.allowUsage(context);
    }

    static String formatMinutes(long ms) {
        long minutes = Math.max(1L, ms / 60000L);
        if (minutes < 60) return minutes + "分钟";
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        return rest == 0 ? hours + "小时" : hours + "小时" + rest + "分钟";
    }

    static final class AppItem {
        final String packageName;
        final String label;
        AppItem(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    /** Freeze candidates come from the extension, which can see every package. */
    static List<AppItem> apps() {
        List<AppItem> items = new java.util.ArrayList<AppItem>();
        AdbBridge.Reply reply = AdbBridge.call(AdbProtocol.CMD_APPS, null);
        if (!reply.ok || reply.body == null) return items;
        JSONArray array = reply.body.optJSONArray(AdbProtocol.KEY_ITEMS);
        if (array == null) return items;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            items.add(new AppItem(item.optString(AdbProtocol.KEY_PACKAGE),
                    item.optString(AdbProtocol.KEY_LABEL)));
        }
        return items;
    }
}