package com.dekk.yanyu;

import android.content.Context;

/** Automatic supplement. Send time only records the session; the block is appended while the
 *  request is serialized, so the visible message stays untouched. The recalled-answer cache
 *  in AntiRecallHook is never read here. */
final class ContextSummaryHook {
    private static boolean installed, reported;
    private ContextSummaryHook() { }

    static void install(ClassLoader loader) {
        if (installed) return;
        try {
            HotHelpers.findAndHookMethod("nb5", loader, "J",
                    HotHelpers.findClass("jb1", loader), HotHelpers.findClass("yp1", loader),
                    String.class, HotHelpers.findClass("p31", loader), new HotHook() {
                        @Override
                        protected void beforeHookedMethod(HotParam param) {
                            try {
                                if (param.args == null || param.args.length != 4) return;
                                Context context = HotApp.currentApplication();
                                if (!ContextSummaryStore.enabled(context)) return;
                                if (!(param.args[2] instanceof String)) return;
                                String text = (String) param.args[2];
                                if (text.length() == 0 || text.equals(MultiReplyQueue.getPrompt())) return;
                                Object session = HotHelpers.callMethod(param.args[0], "M");
                                if (session == null) return;
                                String id = (String) HotHelpers.getObjectField(session, "a");
                                if (id == null || id.length() == 0) return;
                                // Snapshot for this turn, then store the current question for the next one.
                                CurrentTurnContext.set(id, PreviousQuestionStore.get(id));
                                PreviousQuestionStore.remember(id, text);
                            } catch (Throwable t) {
                                error(t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(HotParam param) {
                            if (!param.hasThrowable()) return;
                            try {
                                String id = CurrentTurnContext.session();
                                if (id.length() > 0) PreviousQuestionStore.restore(id, CurrentTurnContext.previous());
                            } catch (Throwable t) {
                                error(t);
                            }
                        }
                    });
            installed = true;
            HotBridge.log("[DEKK][上下文补充] 发送记录已安装");
        } catch (Throwable t) {
            error(t);
        }
    }

    /** Request-only text; empty when the switch is off or nothing is recorded yet. */
    static String blockFor(Context context) {
        if (!ContextSummaryStore.enabled(context)) return "";
        return ContextSummaryBlock.block(CurrentTurnContext.previous(), ContextSummaryStore.notes(context));
    }

    private static void error(Throwable t) {
        if (reported) return;
        reported = true;
        HotBridge.log("[DEKK][上下文补充] 适配异常，放行原消息: " + t.getClass().getSimpleName());
    }
}