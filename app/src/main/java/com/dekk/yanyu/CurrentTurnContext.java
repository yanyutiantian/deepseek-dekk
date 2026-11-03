package com.dekk.yanyu;

/** Carries the session and its previous-question snapshot from send time to serialization. */
final class CurrentTurnContext {
    private static final long WINDOW_MS = 120000L;
    private static final ThreadLocal<String[]> LOCAL = new ThreadLocal<String[]>();
    private static volatile String[] last;
    private static volatile long at;
    private CurrentTurnContext() { }

    static void set(String session, String previous) {
        String[] value = {session == null ? "" : session, previous == null ? "" : previous};
        LOCAL.set(value);
        last = value;
        at = System.currentTimeMillis();
    }

    private static String[] current() {
        String[] value = LOCAL.get();
        if (value == null) value = last;
        if (value == null || System.currentTimeMillis() - at > WINDOW_MS) return null;
        return value;
    }

    static String session() {
        String[] value = current();
        return value == null ? "" : value[0];
    }

    static String previous() {
        String[] value = current();
        return value == null ? "" : value[1];
    }

    static void clear() {
        LOCAL.remove();
        last = null;
    }
}