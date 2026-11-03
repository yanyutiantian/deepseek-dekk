package com.dekk.yanyu;

import java.util.Collection;
import java.util.LinkedHashMap;

final class RecallSessionCache {
    private static final int MAX_CHARS = 2000000;
    private static final int MAX_MESSAGES = 64;
    private static final int MAX_SESSIONS = 32;
    private int chars;
    private final LinkedHashMap<String, LinkedHashMap<Integer, Entry>> sessions = new LinkedHashMap(16, 0.75f, true);

    static final class Entry {
        final double insertedAt;
        final Object snapshot;
        final String text;

        Entry(Object obj, String str, double d) {
            this.snapshot = obj;
            this.text = str;
            this.insertedAt = d;
        }
    }

    synchronized void put(String str, int i, Entry entry) {
        if (str != null) {
            if (!(str.length() == 0 || entry == null || entry.text.length() == 0)) {
                if (entry.text.length() <= MAX_CHARS) {
                    LinkedHashMap linkedHashMap = (LinkedHashMap) this.sessions.get(str);
                    if (linkedHashMap == null) {
                        linkedHashMap = new LinkedHashMap(16, 0.75f, true);
                        this.sessions.put(str, linkedHashMap);
                    }
                    Entry entry2 = (Entry) linkedHashMap.put(Integer.valueOf(i), entry);
                    if (entry2 != null) {
                        this.chars -= entry2.text.length();
                    }
                    this.chars += entry.text.length();
                    while (linkedHashMap.size() > MAX_MESSAGES) {
                        this.chars -= ((Entry) linkedHashMap.remove((Integer) linkedHashMap.keySet().iterator().next())).text.length();
                    }
                    while (true) {
                        if (this.sessions.size() > MAX_SESSIONS || this.chars > MAX_CHARS) {
                            Collection<Entry> values = ((LinkedHashMap) this.sessions.remove((String) this.sessions.keySet().iterator().next())).values();
                            Collection collection = values;
                            for (Entry entry3 : values) {
                                this.chars -= entry3.text.length();
                            }
                        } else {
                            return;
                        }
                    }
                }
            }
        }
    }

    synchronized Entry get(String str, int i, double d) {
        Entry entry;
        Entry entry2;
        LinkedHashMap linkedHashMap = (LinkedHashMap) this.sessions.get(str);
        entry = null;
        if (linkedHashMap == null) {
            entry2 = null;
        } else {
            entry2 = (Entry) linkedHashMap.get(Integer.valueOf(i));
        }
        if (entry2 == null || Double.compare(entry2.insertedAt, d) != 0) {
            entry2 = null;
        } else {
            entry = entry2;
        }
        return entry;
    }


    /**
     * 同一消息但时间戳会变（服务器下发的过滤/撤回更新会带上新的时间）用这个方法找。
     * 只新增，不改原有 get 的任何行为。
     */
    synchronized Entry getIgnoreTime(String str, int i) {
        LinkedHashMap linkedHashMap = (LinkedHashMap) this.sessions.get(str);
        if (linkedHashMap == null) {
            return null;
        }
        return (Entry) linkedHashMap.get(Integer.valueOf(i));
    }

    synchronized void clear() {
        this.sessions.clear();
        this.chars = 0;
    }
}