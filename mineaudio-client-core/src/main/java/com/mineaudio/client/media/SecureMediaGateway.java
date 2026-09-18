package com.mineaudio.client.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 本机安全媒体网关（设计文档 §27-29）：
 * 只监听 127.0.0.1，URL 不携带任何外部参数；/media/&lt;128-bit token&gt; 映射内存中的资源。
 * 负责重定向逐跳校验、缓存与 Range 响应，解码器只访问本机地址。
 */
public final class SecureMediaGateway implements AutoCloseable {

    public record Resource(String cacheKey, URI url, Map<String, String> headers, long expiresAtMs) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BUFFER_SIZE = 64 * 1024;

    private final MediaFirewall firewall;
    private final MediaCache cache;
    private final HttpClient http;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final int port;

    public SecureMediaGateway(MediaFirewall firewall, MediaCache cache) throws IOException {
        this.firewall = firewall;
        this.cache = cache;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/media/", this::handle);
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "MineAudio-MediaGateway");
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(executor);
        this.server.start();
        this.port = server.getAddress().getPort();
    }

    public int port() {
        return port;
    }

    /** 注册资源并返回仅本机可访问的播放地址。 */
    public String register(Resource resource) {
        byte[] tokenBytes = new byte[16];
        RANDOM.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        resources.put(token, resource);
        return "http://127.0.0.1:" + port + "/media/" + token;
    }

    public void unregister(String localUrl) {
        String token = tokenOf(localUrl);
        if (token != null) resources.remove(token);
    }

    @Override
    public void close() {
        resources.clear();
        server.stop(0);
        executor.shutdownNow();
    }

    // ---------- 请求处理 ----------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String token = tokenOf(exchange.getRequestURI().getPath());
            Resource resource = token == null ? null : resources.get(token);
            if (resource == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (cache.has(resource.cacheKey())) {
                serveFile(exchange, cache.fileFor(resource.cacheKey()));
                cache.touch(resource.cacheKey());
                return;
            }
            fetchAndServe(exchange, resource);
        } catch (Throwable t) {
            try {
                exchange.sendResponseHeaders(502, -1);
            } catch (IOException ignored) {
                // 连接已断开
            }
        } finally {
            exchange.close();
        }
    }

    /** 缓存命中：支持 Range（供 seek）。 */
    private void serveFile(HttpExchange exchange, Path file) throws IOException {
        long length = Files.size(file);
        long start = 0;
        long end = length - 1;
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length()).split(",")[0].trim();
            String[] parts = spec.split("-", 2);
            try {
                if (!parts[0].isEmpty()) start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            } catch (NumberFormatException ignored) {
                start = 0;
                end = length - 1;
            }
            if (start < 0) start = 0;
            if (end >= length) end = length - 1;
            if (start > end) {
                exchange.sendResponseHeaders(416, -1);
                return;
            }
        }
        long contentLength = end - start + 1;
        boolean partial = range != null;
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        if (partial) {
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + length);
            exchange.sendResponseHeaders(206, contentLength);
        } else {
            exchange.sendResponseHeaders(200, contentLength);
        }
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(start);
            transfer(in, exchange.getResponseBody(), contentLength);
        }
    }

    /** 缓存未命中：带 Range 的请求转发上游（不缓存）；普通请求边发边写 .part，完整结束后提交缓存。 */
    private void fetchAndServe(HttpExchange exchange, Resource resource) throws Exception {
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && !range.isBlank()) {
            proxyRange(exchange, resource, range);
            return;
        }
        serveFull(exchange, resource);
    }

    /** 转发单段 Range 请求（seek 用）：上游返回 206 时透传，200/416 原样返回。 */
    private void proxyRange(HttpExchange exchange, Resource resource, String range) throws Exception {
        HttpResponse<InputStream> response = openUpstream(resource, range);
        int status = response.statusCode();
        if (status == 416) {
            response.body().close();
            exchange.sendResponseHeaders(416, -1);
            return;
        }
        if (status != 206) {
            response.body().close();
            serveFull(exchange, resource);
            return;
        }
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(0);
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        response.headers().firstValue("Content-Range")
                .ifPresent(value -> exchange.getResponseHeaders().set("Content-Range", value));
        exchange.sendResponseHeaders(206, contentLength);
        try (InputStream in = response.body()) {
            transfer(in, exchange.getResponseBody(), contentLength > 0 ? contentLength : Long.MAX_VALUE);
        }
    }

    /** 全量拉取：逐跳校验重定向，边发边写 .part，完整结束后提交缓存。 */
    private void serveFull(HttpExchange exchange, Resource resource) throws Exception {
        HttpResponse<InputStream> response = openUpstream(resource, null);
        boolean cacheEnabled = cache.directory() != null;
        Path part = cacheEnabled ? cache.partFor(resource.cacheKey()) : null;
        OutputStream fileOut = part == null ? null : Files.newOutputStream(part);
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.sendResponseHeaders(200, 0);
        OutputStream body = exchange.getResponseBody();
        long written = 0;
        try (InputStream in = response.body()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                body.write(buffer, 0, read);
                if (fileOut != null) fileOut.write(buffer, 0, read);
                written += read;
            }
            body.flush();
        } finally {
            if (fileOut != null) {
                fileOut.close();
            }
        }
        if (part != null && written > 0) {
            cache.commit(part, resource.cacheKey());
        }
    }

    /** 逐跳校验重定向后打开上游流；range 非空时携带 Range 请求头（416 原样返回给调用方）。 */
    private HttpResponse<InputStream> openUpstream(Resource resource, String range) throws Exception {
        URI current = resource.url();
        firewall.validate(current);
        HttpResponse<InputStream> response = null;
        int redirects = 0;
        for (int hop = 0; hop <= firewall.policy().maxRedirects(); hop++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofMinutes(10))
                    .GET();
            if (range != null) {
                builder.header("Range", range);
            }
            if (resource.headers() != null) {
                resource.headers().forEach((name, value) -> {
                    if (isAllowedHeader(name)) builder.header(name, value);
                });
            }
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                response.body().close();
                if (location == null) throw new MediaSecurityException("HTTP_3XX", "redirect without location");
                redirects++;
                if (redirects > firewall.policy().maxRedirects()) {
                    throw new MediaSecurityException("FIREWALL_REJECTED", "too many redirects");
                }
                current = firewall.validateRedirect(current.resolve(location));
                continue;
            }
            if (status == 416) {
                return response;
            }
            if (status >= 400) {
                response.body().close();
                throw new MediaSecurityException("HTTP_" + status, "upstream status " + status);
            }
            break;
        }
        if (response == null) throw new IOException("no response");
        return response;
    }

    /** Header 白名单（设计文档 §26）：禁止 Cookie / Authorization 等账号凭据。 */
    private static boolean isAllowedHeader(String name) {
        String lower = name.toLowerCase();
        return lower.equals("user-agent") || lower.equals("referer") || lower.equals("origin")
                || lower.equals("range") || lower.equals("accept");
    }

    private static void transfer(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        out.flush();
    }

    static String tokenOf(String pathOrUrl) {
        String path = pathOrUrl;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            path = path.substring(scheme + 3);
            int slash = path.indexOf('/');
            path = slash < 0 ? "" : path.substring(slash);
        }
        int marker = path.lastIndexOf("/media/");
        if (marker < 0) return null;
        String token = path.substring(marker + "/media/".length());
        return token.matches("[0-9a-f]{32}") ? token : null;
    }
}
