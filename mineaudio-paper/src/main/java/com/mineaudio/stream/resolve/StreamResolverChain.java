package com.mineaudio.stream.resolve;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Resolver 链：按注册顺序选择第一个支持的解析器；都不支持时返回 UNSUPPORTED_SOURCE。 */
public final class StreamResolverChain {

    private final List<StreamResolver> resolvers;

    public StreamResolverChain(List<StreamResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    public CompletionStage<ResolveResult> resolve(ResolveRequest request) {
        for (StreamResolver resolver : resolvers) {
            if (resolver.supports(request)) {
                return resolver.resolve(request);
            }
        }
        return CompletableFuture.failedFuture(new ResolveException(
                ResolveFailureKind.UNSUPPORTED_SOURCE,
                "无可用解析器：" + request.source()));
    }

    public List<String> describe() {
        return resolvers.stream().map(StreamResolver::id).toList();
    }
}
