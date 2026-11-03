package com.dekk.yanyu;

import android.content.Context;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** AI issued ADB commands: hidden instruction in, command line stripped, result queued. */
final class AdbAiHook {
    private static final Map<Object, Turn> turns = new WeakHashMap<Object, Turn>();
    private static final List<Turn> pending = new ArrayList<Turn>();
    private static boolean installed, reported;

    private static final class Turn {
        final String token = UUID.randomUUID().toString().replace("-", "");
        final Set<Object> ids = new HashSet<Object>();
        boolean injected;
        String text;
        final long created = android.os.SystemClock.uptimeMillis();
    }

    private AdbAiHook() { }

    static String prompt(Context context, String text) {
        if (context == null || text == null) return "";
        if (!AdbControlStore.enabled(context)) return "";
        if (text.contains(AdbCommand.START) || MultiReplyQueue.getPrompt().equals(text)) return "";
        Turn turn = null;
        synchronized (turns) {
            long now = android.os.SystemClock.uptimeMillis();
            for (int i = pending.size() - 1; i >= 0; i--) {
                Turn item = pending.get(i);
                if (now - item.created > 3600000L) { pending.remove(i); continue; }
                if (turn == null && text.equals(item.text)) turn = item;
            }
            if (turn == null) {
                turn = new Turn();
                turn.text = text;
                pending.add(turn);
            }
            while (pending.size() > 32) pending.remove(0);
            turn.injected = true;
        }
        String block = AdbCommand.build(AdbControlStore.allowFreeze(context),
                AdbControlStore.allowUsage(context), turn.token);
        if (block.length() > 0) HotBridge.log("[DEKK][ADB] 已附加可用指令说明");
        return block;
    }

    static void install(ClassLoader loader) {
        if (installed) return;
        try {
            HotHelpers.findAndHookMethod(HotHelpers.findClass("nb5", loader), "J",
                    HotHelpers.findClass("jb1", loader), HotHelpers.findClass("yp1", loader),
                    String.class, HotHelpers.findClass("p31", loader), new HotHook(8000) {
                        @Override
                        protected void beforeHookedMethod(HotParam param) {
                            Context context = HotApp.currentApplication();
                            if (!AdbControlStore.enabled(context)) return;
                            if (!(param.args[2] instanceof String)) return;
                            if (MultiReplyQueue.getPrompt().equals(param.args[2])) return;
                            Turn turn = new Turn();
                            turn.text = (String) param.args[2];
                            try {
                                Object session = HotHelpers.callMethod(param.args[0], "M");
                                Map<?, ?> messages = (Map<?, ?>) HotHelpers.getObjectField(session, "f");
                                turn.ids.addAll(messages.keySet());
                            } catch (Throwable t) {
                                HotBridge.log("[DEKK][ADB] 会话快照不可用，按标识关联");
                            }
                            synchronized (turns) {
                                turns.put(param.args[0], turn);
                                pending.add(turn);
                                while (pending.size() > 32) pending.remove(0);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(HotParam param) {
                            if (!param.hasThrowable()) return;
                            synchronized (turns) {
                                Turn turn = turns.remove(param.args[0]);
                                if (turn != null) pending.remove(turn);
                            }
                        }
                    });
            HotHelpers.findAndHookMethod(HotHelpers.findClass("ao1", loader), "T",
                    HotHelpers.findClass("ao1", loader), HotHelpers.findClass("d21", loader),
                    new HotHook() {
                        @Override
                        protected void afterHookedMethod(HotParam param) {
                            if (param.hasThrowable() || param.args[1] == null) return;
                            if (!"c21".equals(param.args[1].getClass().getName())) return;
                            Context context = HotApp.currentApplication();
                            if (!AdbControlStore.enabled(context)) return;
                            try {
                                Object result = param.args[1];
                                if (HotHelpers.getBooleanField(result, "b")
                                        || HotHelpers.getBooleanField(result, "d")) return;
                                Object success = HotHelpers.getObjectField(result, "a");
                                if (success == null || !"eg6".equals(success.getClass().getName())) return;
                                List<Turn> candidates;
                                synchronized (turns) {
                                    candidates = new ArrayList<Turn>(pending);
                                    turns.remove(param.args[0]);
                                }
                                if (candidates.isEmpty()) return;
                                Object session = HotHelpers.callMethod(param.args[0], "M");
                                Map<?, ?> messages = (Map<?, ?>) HotHelpers.getObjectField(session, "f");
                                for (Map.Entry<?, ?> entry : messages.entrySet()) {
                                    Object snapshot = HotHelpers.callMethod(entry.getValue(), "a");
                                    List<?> fragments = (List<?>) HotHelpers.getObjectField(snapshot, "v");
                                    AdbCommand.Item item = null;
                                    Turn matched = null;
                                    for (int i = 0; i < fragments.size() && item == null; i++) {
                                        Object fragment = fragments.get(i);
                                        if (fragment == null || !"tt8".equals(fragment.getClass().getName())) continue;
                                        String text = (String) HotHelpers.getObjectField(fragment, "c");
                                        for (Turn turn : candidates) {
                                            if (!turn.injected || turn.ids.contains(entry.getKey())) continue;
                                            if (android.os.SystemClock.uptimeMillis() - turn.created > 3600000L) continue;
                                            AdbCommand.Item parsed = AdbCommand.parse(text, turn.token);
                                            if (parsed == null) continue;
                                            item = parsed;
                                            matched = turn;
                                            HotHelpers.setObjectField(fragment, "c",
                                                    AdbCommand.visible(text, turn.token));
                                            break;
                                        }
                                    }
                                    if (item == null || matched == null) continue;
                                    if (!AdbControl.allowed(context, item)) {
                                        HotBridge.log("[DEKK][ADB] 指令被忽略：对应权限未开启");
                                        continue;
                                    }
                                    synchronized (turns) { pending.remove(matched); }
                                    run(context, item);
                                    break;
                                }
                            } catch (Throwable t) {
                                error(t);
                            }
                        }
                    });
            installed = true;
            HotBridge.log("[DEKK][ADB] 指令解析已安装");
        } catch (Throwable t) {
            error(t);
        }
    }

    private static void run(final Context context, final AdbCommand.Item item) {
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    HotBridge.log("[DEKK][ADB] 执行结果：" + AdbControl.run(context, item));
                } catch (Throwable t) {
                    error(t);
                }
            }
        }, "dekk-adb-command");
        worker.setDaemon(true);
        worker.start();
    }

    public static void error(Throwable t) {
        if (reported) return;
        reported = true;
        HotBridge.log("[DEKK][ADB] 适配异常：" + t.getClass().getSimpleName());
    }
}