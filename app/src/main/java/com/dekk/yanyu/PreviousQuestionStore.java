package com.dekk.yanyu;

import java.util.LinkedHashMap;

/** Process-local, bounded: the user's own last question per session. */
final class PreviousQuestionStore {
    private static final int MAX_SESSIONS = 32;
    private static final int LIMIT = 4000;
    private static final LinkedHashMap<String, String> questions =
            new LinkedHashMap<String, String>(16, .75f, true);
    private PreviousQuestionStore() { }

    static synchronized String get(String session) {
        if (session == null) return "";
        String text = questions.get(session);
        return text == null ? "" : text;
    }

    static synchronized void remember(String session, String question) {
        if (session == null || session.length() == 0 || question == null) return;
        String text = question.trim();
        if (text.length() == 0 || text.length() > LIMIT) return;
        questions.put(session, text);
        while (questions.size() > MAX_SESSIONS) questions.remove(questions.keySet().iterator().next());
    }

    /** Rollback after a failed send; blank previous means "no record". */
    static synchronized void restore(String session, String previous) {
        if (session == null || session.length() == 0) return;
        String text = previous == null ? "" : previous.trim();
        if (text.length() == 0) questions.remove(session);
        else questions.put(session, text);
    }

    static synchronized void clear() { questions.clear(); }
}