package com.lspilot.enhancer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Merges a partial UI list without dropping rows already persisted in the host database. */
final class HistoryRetention {
    interface MessageIdReader {
        String read(Object message) throws Exception;
    }

    private HistoryRetention() {
    }

    static List<Object> merge(List<?> persisted, List<?> current, MessageIdReader ids)
            throws Exception {
        if (persisted == null || current == null || ids == null) return null;
        List<Object> result = new ArrayList<>(persisted.size() + current.size());
        Map<String, Integer> positions = new HashMap<>();
        Set<String> currentIds = new HashSet<>();

        for (Object message : persisted) {
            String id = normalize(ids.read(message));
            if (id == null || positions.put(id, result.size()) != null) return null;
            result.add(message);
        }
        for (Object message : current) {
            String id = normalize(ids.read(message));
            if (id == null || !currentIds.add(id)) return null;
            Integer position = positions.get(id);
            if (position == null) {
                positions.put(id, result.size());
                result.add(message);
            } else {
                result.set(position, message);
            }
        }
        return result;
    }

    static boolean hasExpectedPersistedCount(List<?> persisted, int rowCount) {
        return persisted != null && rowCount >= 0 && persisted.size() == rowCount;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
