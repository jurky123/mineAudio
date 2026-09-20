package com.mineaudio.stream.resolve;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 短期解析缓存 + 同曲合并（single-flight）：
 * 同一首歌的并发请求只向上游发一次；URL 有明确过期时间时提前 60s 刷新，否则用 5 分钟 TTL。
 */
public final class ResolutionCache {

    private static final long FALLBACK_TTL_MS = 5 * 60_000L;
    private static final long REFRESH_MARGIN_MS = 60_000L;
    /** 条目上限：超出时先清过期，再整体减半，防止长期运行无界增长。 */

    /** 条目上限：超出时先清过期，再整体减半，防止长期运行无界增长。 */
    private static final int MAX_ENTRIES = 256;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<ResolveResult>> inFlight = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public ResolutionCache() {
        this(System::currentTimeMillis);
    }

    public ResolutionCache(LongSupplier clock) {
        this.clock = clock;
    }

    public CompletionStage<ResolveResult> resolve(String key, Supplier<CompletionStage<ResolveResult>> loader) {
        Entry cached = entries.get(key);
        if (cached != null && clock.getAsLong() < cached.refreshAfterMs()) {
            return CompletableFuture.completedFuture(cached.result());
        }
        CompletableFuture<ResolveResult> existing = inFlight.get(key);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<ResolveResult> future = new CompletableFuture<>();
        if (inFlight.putIfAbsent(key, future) != null) {
            return inFlight.get(key);
        }
        loader.get().whenComplete((result, error) -> {
            inFlight.remove(key, future);
            if (error != null) {
                future.completeExceptionally(unwrap(error));
                return;
            }
            evictIfNeeded();
            entries.put(key, new Entry(result, refreshAfter(result)));
            future.complete(result);
        });
        return future;
    }

    public void invalidate(String key) {
        entries.remove(key);
    }

    private void evictIfNeeded() {
        if (entries.size() < MAX_ENTRIES) {
            return;
        }
        long now = clock.getAsLong();
        entries.entrySet().removeIf(entry -> now >= entry.getValue().refreshAfterMs());
        if (entries.size() >= MAX_ENTRIES) {
            int target = MAX_ENTRIES / 2;
            var iterator = entries.keySet().iterator();
            while (entries.size() > target && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    private long refreshAfter(ResolveResult result) {
        Instant expiresAt = result.expiresAt();
        if (expiresAt != null) {
            return Math.max(clock.getAsLong() + 1000, expiresAt.toEpochMilli() - REFRESH_MARGIN_MS);
        }
        return clock.getAsLong() + FALLBACK_TTL_MS;
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private record Entry(ResolveResult result, long refreshAfterMs) {
    }
}
