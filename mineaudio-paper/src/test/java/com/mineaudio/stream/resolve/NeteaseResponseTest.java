package com.mineaudio.stream.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class NeteaseResponseTest {

    @Test
    void parsesPlayableSong() throws Exception {
        String json = """
                {"data":[{"id":1,"url":"https://m7.music.126.net/a.mp3","br":320000,"code":200,"expi":1200,"time":240000}],"code":200}
                """;
        ResolveResult result = NeteaseEapiResolver.parse(200, json);
        assertEquals(URI.create("https://m7.music.126.net/a.mp3"), result.streamUrl());
        assertEquals(240000, result.durationMs());
        assertTrue(result.expiresAt().isAfter(Instant.now()));
    }

    @Test
    void vipTrackWithoutCredentialIsEntitlementFailure() {
        String json = """
                {"data":[{"id":1,"url":null,"code":404,"fee":1,"freeTrialPrivilege":{"cannotListenReason":1}}],"code":200}
                """;
        ResolveException error = assertThrows(ResolveException.class,
                () -> NeteaseEapiResolver.parse(200, json));
        assertEquals(ResolveFailureKind.ACCOUNT_NOT_ENTITLED, error.kind());
    }

    @Test
    void nullUrlFreeTrackIsNotPlayable() {
        String json = "{\"data\":[{\"id\":1,\"url\":null,\"code\":404,\"fee\":0}],\"code\":200}";
        ResolveException error = assertThrows(ResolveException.class,
                () -> NeteaseEapiResolver.parse(200, json));
        assertEquals(ResolveFailureKind.NOT_PLAYABLE, error.kind());
    }

    @Test
    void invalidJsonIsClassified() {
        ResolveException error = assertThrows(ResolveException.class,
                () -> NeteaseEapiResolver.parse(200, "<html>"));
        assertEquals(ResolveFailureKind.INVALID_RESPONSE, error.kind());
    }

    @Test
    void httpStatusMapped() {
        ResolveException unauthorized = assertThrows(ResolveException.class,
                () -> NeteaseEapiResolver.parse(403, ""));
        assertEquals(ResolveFailureKind.ACCOUNT_NOT_ENTITLED, unauthorized.kind());

        ResolveException limited = assertThrows(ResolveException.class,
                () -> NeteaseEapiResolver.parse(429, ""));
        assertEquals(ResolveFailureKind.RATE_LIMITED, limited.kind());

        ResolveException unavailable = assertThrows(ResolveException.class,
                () -> NeteaseEapiResolver.parse(502, ""));
        assertEquals(ResolveFailureKind.REMOTE_UNAVAILABLE, unavailable.kind());
    }

    @Test
    void chainUnsupportedSource() {
        StreamResolverChain chain = new StreamResolverChain(java.util.List.of(new DirectUrlResolver()));
        java.util.concurrent.CompletionException error = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> chain.resolve(new ResolveRequest("ncmlite", "1", null))
                        .toCompletableFuture().join());
        assertInstanceOf(ResolveException.class, error.getCause());
        assertEquals(ResolveFailureKind.UNSUPPORTED_SOURCE,
                ((ResolveException) error.getCause()).kind());
    }
}
