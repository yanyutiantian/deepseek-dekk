package com.dekk.yanyu;

/**
 * 一问多答队列：按会话 ID 计数，不包含请求级 token。
 * 由 MultiReplyStateHook 在 ao1.T（完成）和 ao1.J（发送事件）中驱动。
 */
public final class MultiReplyQueue {
    private static String activeSession = null;
    private static java.lang.ref.WeakReference<Object> activeOwner;
    private static boolean awaitingSession;
    private static int targetCount = 0;
    private static int completedCount = 0;

    private MultiReplyQueue() { }

    public static synchronized void start(Object owner, String sessionId, int total) {
        cancel();
        if (owner == null || total < 2 || total > 3) return;
        activeOwner = new java.lang.ref.WeakReference<Object>(owner);
        awaitingSession = sessionId == null || sessionId.length() == 0;
        activeSession = sessionId;
        targetCount = total;
        completedCount = 0;
    }

    public static synchronized boolean shouldTrigger(String sessionId) {
        return activeSession != null && activeSession.equals(sessionId)
                && completedCount < targetCount;
    }

    public static synchronized void onComplete(String sessionId) {
        if (activeSession != null && activeSession.equals(sessionId)) {
            completedCount++;
        }
    }

    public static synchronized boolean bindCompletionSession(Object owner, String sessionId) {
        if (targetCount == 0 || activeOwner == null || activeOwner.get() != owner
                || sessionId == null || sessionId.length() == 0) return false;
        if (awaitingSession && completedCount == 0) {
            activeSession = sessionId;
            awaitingSession = false;
        }
        return sessionId.equals(activeSession);
    }

    public static synchronized void cancel() {
        activeOwner = null;
        awaitingSession = false;
        activeSession = null;
        targetCount = 0;
        completedCount = 0;
    }

    public static synchronized String completionStatus(String sessionId) {
        return "active=" + (targetCount > 0)
                + " startIdPresent=" + (activeSession != null && activeSession.length() > 0)
                + " endIdPresent=" + (sessionId != null && sessionId.length() > 0)
                + " sameSession=" + (activeSession != null && activeSession.equals(sessionId))
                + " completed=" + completedCount + " target=" + targetCount;
    }

    public static synchronized boolean isActive() {
        return activeSession != null;
    }

    public static synchronized String getPrompt() {
        return "请结合本会话原始问题与上一条助手回答，继续补充有价值的内容，"
                + "不要重新回答或重复已有内容，也不要在回答中提及这条自动续答指令。";
    }
}