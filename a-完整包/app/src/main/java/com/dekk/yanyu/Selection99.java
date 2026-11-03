package com.dekk.yanyu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Pure Java 7 selection policy; input order is the visible list order. */
public final class Selection99 {
    private Selection99() { }
    public static List<String> choose(List<String> ordered, Collection<?> selected) {
        List<String> ids = new ArrayList<String>(new LinkedHashSet<String>(ordered));
        List<String> result = new ArrayList<String>();
        if (ids.isEmpty()) return result;
        int anchor = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (selected.contains(ids.get(i))) { anchor = i; break; }
        }
        int limit = Math.min(99, ids.size());
        for (int i = anchor; i >= 0 && result.size() < limit; i--) result.add(ids.get(i));
        for (int i = anchor + 1; i < ids.size() && result.size() < limit; i++) result.add(ids.get(i));
        return result;
    }
}
