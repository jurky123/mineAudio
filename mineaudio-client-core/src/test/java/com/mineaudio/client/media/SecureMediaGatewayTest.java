package com.mineaudio.client.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class SecureMediaGatewayTest {

    @Test
    void tokenParsing() {
        String token = "0123456789abcdef0123456789abcdef";
        assertEquals(token, SecureMediaGateway.tokenOf("http://127.0.0.1:1234/media/" + token));
        assertEquals(token, SecureMediaGateway.tokenOf("/media/" + token));
        assertNull(SecureMediaGateway.tokenOf("/media/short"));
        assertNull(SecureMediaGateway.tokenOf("/other/" + token));
    }

    @Test
    void registersAndCloses(@TempDir Path dir) throws Exception {
        MediaCache cache = new MediaCache(dir, 1024, 1024);
        cache.init();
        SecureMediaGateway gateway = new SecureMediaGateway(
                new MediaFirewall(MediaFirewall.Policy.defaults()), cache);
        try {
            String url = gateway.register(new SecureMediaGateway.Resource(
                    "key", URI.create("https://93.184.216.34/a.mp3"), java.util.Map.of(), 0));
            assertTrue(url.startsWith("http://127.0.0.1:" + gateway.port() + "/media/"));
            gateway.unregister(url);
        } finally {
            gateway.close();
        }
    }

    @Test
    void proxiesRangeWhenCacheIncomplete(@TempDir Path dir) throws Exception {
        byte[] data = new byte[10000];
        new Random(42).nextBytes(data);
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/a.mp3", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            try {
                if (range != null && range.startsWith("bytes=")) {
                    String spec = range.substring("bytes=".length()).split(",")[0].trim();
                    String[] parts = spec.split("-", 2);
                    long start = Long.parseLong(parts[0]);
                    long end = parts.length > 1 && !parts[1].isEmpty()
                            ? Long.parseLong(parts[1]) : data.length - 1;
                    exchange.getResponseHeaders().set("Content-Range",
                            "bytes " + start + "-" + end + "/" + data.length);
                    exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                    exchange.sendResponseHeaders(206, end - start + 1);
                    exchange.getResponseBody().write(data, (int) start, (int) (end - start + 1));
                } else {
                    exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                }
            } finally {
                exchange.close();
            }
        });
        upstream.start();
        try {
            MediaCache cache = new MediaCache(dir, 1024, 1024);
            cache.init();
            MediaFirewall firewall = new MediaFirewall(
                    new MediaFirewall.Policy(false, false, 5, List.of(), List.of()));
            SecureMediaGateway gateway = new SecureMediaGateway(firewall, cache);
            try {
                String url = gateway.register(new SecureMediaGateway.Resource("k",
                        URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/a.mp3"),
                        Map.of(), 0));
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Range", "bytes=5000-5099").GET().build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(206, response.statusCode());
                assertEquals("bytes 5000-5099/10000",
                        response.headers().firstValue("Content-Range").orElse(""));
                assertArrayEquals(Arrays.copyOfRange(data, 5000, 5100), response.body());
            } finally {
                gateway.close();
            }
        } finally {
            upstream.stop(0);
        }
    }
}
