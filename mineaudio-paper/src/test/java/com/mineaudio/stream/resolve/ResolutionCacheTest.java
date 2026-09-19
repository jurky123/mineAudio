package com.mineaudio.stream.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class ResolutionCacheTest {

    private long now = 1_000_000L;
    private final ResolutionCache cache = new ResolutionCache(() -> now);

    private static ResolveResult result(Instant expiresAt) {
        return new ResolveResult(URI.create("https://example.com/a.mp3"), null, null, 0, expiresAt, null);
    }

    @Test
    void coalescesConcurrentRequests() {
        AtomicInteger loads = new AtomicInteger();
        CompletableFuture<ResolveResult> gate = new CompletableFuture<>();
        Supplier<CompletionStage<ResolveResult>> loader = () -> {
            loads.incrementAndGet();
            return gate;
        };
        CompletableFuture<ResolveResult> first = cache.resolve("k", loader).toCompletableFuture();
        CompletableFuture<ResolveResult> second = cache.resolve("k", loader).toCompletableFuture();
        gate.complete(result(null));
        assertEquals(1, loads.get(), "同曲并发只请求一次");
        assertSame(first.join(), second.join());
    }

    @Test
    void servesFromCacheWithinTtl() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<CompletionStage<ResolveResult>> loader = () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(result(null));
        };
        cache.resolve("k", loader).toCompletableFuture().join();
        now += 60_000;
        cache.resolve("k", loader).toCompletableFuture().join();
        assertEquals(1, loads.get());
    }

    @Test
    void reloadsAfterTtl() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<CompletionStage<ResolveResult>> loader = () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(result(null));
        };
        cache.resolve("k", loader).toCompletableFuture().join();
        now += 5 * 60_000 + 1;
        cache.resolve("k", loader).toCompletableFuture().join();
        assertEquals(2, loads.get());
    }

    @Test
    void refreshesBeforeUrlExpiry() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<CompletionStage<ResolveResult>> loader = () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(result(Instant.ofEpochMilli(now + 10_000)));
        };
        cache.resolve("k", loader).toCompletableFuture().join();
        now += 1_001;
        cache.resolve("k", loader).toCompletableFuture().join();
        assertEquals(2, loads.get(), "过期前 60s 内应刷新");
    }

    @Test
    void invalidateForcesReload() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<CompletionStage<ResolveResult>> loader = () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(result(null));
        };
        cache.resolve("k", loader).toCompletableFuture().join();
        cache.invalidate("k");
        cache.resolve("k", loader).toCompletableFuture().join();
        assertEquals(2, loads.get());
    }
}
