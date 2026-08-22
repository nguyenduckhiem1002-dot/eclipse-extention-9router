package com.casla.eclipse.ai.completion;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe LRU cache with TTL for AI completions.
 * Reduces perceived latency and avoids redundant network calls when
 * backspacing or typing through known suggestions.
 */
public final class CompletionCache {
    private static final CompletionCache INSTANCE = new CompletionCache(64, 60_000);

    private record Entry(String completion, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final int maxEntries;
    private final long ttlMillis;
    private final Map<String, Entry> cache;

    public CompletionCache(int maxEntries, long ttlMillis) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlMillis = Math.max(100, ttlMillis);
        this.cache = new LinkedHashMap<>(maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > CompletionCache.this.maxEntries;
            }
        };
    }

    public static CompletionCache get() {
        return INSTANCE;
    }

    public synchronized String get(String key) {
        if (key == null || key.isBlank()) return null;
        Entry entry = cache.get(key);
        if (entry == null) return null;
        if (entry.isExpired()) {
            cache.remove(key);
            return null;
        }
        return entry.completion();
    }

    public synchronized void put(String key, String completion) {
        if (key == null || key.isBlank() || completion == null || completion.isBlank()) return;
        Instant expiresAt = Instant.now().plusMillis(ttlMillis);
        cache.put(key, new Entry(completion, expiresAt));
    }

    public synchronized void clear() {
        cache.clear();
    }

    public synchronized int size() {
        return cache.size();
    }
}
