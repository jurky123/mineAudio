package com.mineaudio.stream.resolve;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/**
 * A1：解析许可耗尽时 resolve() 也必须立即返回（主线程永不阻塞）。
 * 无论网络状况如何，调用本身的耗时只包含参数校验与请求提交。
 */
class ResolverNonBlockingTest {

    @Test
    void resolveReturnsImmediatelyUnderContention() {
        NeteaseEapiResolver resolver = new NeteaseEapiResolver(new NeteaseEapiResolver.Config(
                true, "MINEAUDIO_NO_SUCH_ENV", "", "exhigh", 1500, 1, 64));
        long start = System.currentTimeMillis();
        CompletableFuture<ResolveResult> first = resolver
                .resolve(ResolveRequest.of("ncmlite", "2652820720", null)).toCompletableFuture();
        CompletableFuture<ResolveResult> second = resolver
                .resolve(ResolveRequest.of("ncmlite", "1955651132", null)).toCompletableFuture();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 2000, "resolve() 必须立即返回，实际耗时=" + elapsed + "ms");
        // 不等待网络结果，避免测试依赖外部服务；只验证调用不阻塞
        first.cancel(true);
        second.cancel(true);
    }

    @Test
    void invalidIdFailsFastWithoutPermit() {
        NeteaseEapiResolver resolver = new NeteaseEapiResolver(new NeteaseEapiResolver.Config(
                true, "MINEAUDIO_NO_SUCH_ENV", "", "exhigh", 1500, 1, 64));
        CompletableFuture<ResolveResult> stage = resolver
                .resolve(ResolveRequest.of("ncmlite", "not-a-number", null)).toCompletableFuture();
        assertTrue(stage.isCompletedExceptionally(), "非法 ID 应立即失败");
    }
}
