package com.mineaudio.stream.resolve;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 直链解析器：uri 直接可用，无需外部请求。 */
public final class DirectUrlResolver implements StreamResolver {

    @Override
    public String id() {
        return "direct";
    }

    @Override
    public boolean supports(ResolveRequest request) {
        return request.uri() != null && !request.uri().isBlank();
    }

    @Override
    public CompletionStage<ResolveResult> resolve(ResolveRequest request) {
        try {
            return CompletableFuture.completedFuture(ResolveResult.url(URI.create(request.uri())));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(
                    new ResolveException(ResolveFailureKind.INVALID_RESPONSE, "非法 URL"));
        }
    }
}
