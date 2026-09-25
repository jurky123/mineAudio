package com.mineaudio.library;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.mineaudio.MineAudioPlugin;

/**
 * 本地曲库上传托管：客户端点歌到全服时，把音频/封面 POST 上来，
 * 服务端临时缓存（按内容 sha256 去重、可配保留时长）并通过 HTTP 分发（支持 Range）。
 *
 * <p>仅由持有上传令牌的客户端写入；读取公开（其他玩家直接拉流）。不参与原版资源包托管（那是 PackHost）。</p>
 */
public final class LibraryHost {

    private static final int AUDIO_LIMIT_EXT = 16;
    private static final List<String> AUDIO_EXT = List.of(
            "mp3", "flac", "ogg", "oga", "m4a", "mp4", "aac", "wav", "opus", "webm");
    private static final List<String> COVER_EXT = List.of("jpg", "jpeg", "png", "gif", "bmp");

    private final MineAudioPlugin plugin;
    private final Path libraryDir;
    private final String token;
    private final long maxBytes;
    private final long retentionMs;
    private final String publicBase;

    private HttpServer server;
    private ScheduledExecutorService cleaner;

    public LibraryHost(MineAudioPlugin plugin) {
        this.plugin = plugin;
        String configured = plugin.getConfig().getString("library.token", "");
        this.token = configured == null || configured.isBlank()
                ? Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong())
                : configured.trim();
        this.maxBytes = Math.max(1, plugin.getConfig().getLong("library.max-file-mb", 50)) * 1024L * 1024L;
        this.retentionMs = Math.max(1, plugin.getConfig().getLong("library.retention-hours", 24)) * 3600_000L;
        String bind = plugin.getConfig().getString("library.bind", "0.0.0.0");
        int port = plugin.getConfig().getInt("library.port", 8767);
        String publicUrl = plugin.getConfig().getString("library.public-url", "");
        Path dir = plugin.getDataFolder().toPath().resolve("library");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            plugin.getLogger().warning("[library] 无法创建托管目录：" + e.getMessage());
        }
        this.libraryDir = dir;
        if (publicUrl == null || publicUrl.isBlank()) {
            String host = plugin.getServer().getIp();
            if (host == null || host.isBlank()) host = "127.0.0.1";
            publicUrl = "http://" + host + ":" + port;
        }
        this.publicBase = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        try {
            this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            this.server.createContext("/mineaudio/upload", this::handleUpload);
            this.server.createContext("/mineaudio/media/", this::handleMedia);
            this.server.setExecutor(Executors.newFixedThreadPool(2));
            this.server.start();
            this.cleaner = Executors.newSingleThreadScheduledExecutor();
            this.cleaner.scheduleWithFixedDelay(this::cleanup, 60, 60, TimeUnit.MINUTES);
            plugin.getLogger().info("[library] 上传托管已启动：监听 " + bind + ":" + port + "，对外基址 " + publicBase);
        } catch (IOException e) {
            this.server = null;
            plugin.getLogger().warning("[library] 启动失败（端口被占用？）：" + e.getMessage());
        }
    }

    /** 上传令牌（写入 HELLO_ACK，仅发给 MineAudio 客户端）。 */
    public String token() {
        return token;
    }

    public String publicBase() {
        return publicBase;
    }

    /** 由已托管的 id + 扩展名拼出对其他玩家可见的 URL。 */
    public String mediaUrl(String id, String ext) {
        return publicBase + "/mineaudio/media/" + id + "." + ext;
    }

    public void stop() {
        if (cleaner != null) {
            cleaner.shutdownNow();
            cleaner = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ---------- HTTP ----------

    private void handleUpload(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain", "POST only");
                return;
            }
            var query = parseQuery(exchange.getRequestURI());
            if (!constantEquals(token, query.get("token"))) {
                respond(exchange, 403, "text/plain", "bad token");
                return;
            }
            String kind = "cover".equalsIgnoreCase(query.get("kind")) ? "cover" : "audio";
            String ext = normalizeExt(query.get("ext"), kind);
            if (ext == null) {
                respond(exchange, 400, "text/plain", "bad ext");
                return;
            }
            byte[] body = readLimited(exchange.getRequestBody());
            if (body.length == 0) {
                respond(exchange, 400, "text/plain", "empty body");
                return;
            }
            String id = sha256Hex(body);
            Path target = libraryDir.resolve(id + "." + ext);
            if (!Files.isRegularFile(target)) {
                Path tmp = libraryDir.resolve(id + "." + ext + ".tmp");
                Files.write(tmp, body);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String json = "{\"id\":\"" + id + "\",\"ext\":\"" + ext + "\",\"url\":\""
                    + mediaUrl(id, ext) + "\"}";
            respond(exchange, 200, "application/json", json);
        } catch (Throwable t) {
            respond(exchange, 500, "text/plain", "error: " + t);
        }
    }

    private void handleMedia(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String name = path.substring("/mineaudio/media/".length());
        if (!name.matches("[a-f0-9]{64}\\.[a-z0-9]{1,8}")) {
            respond(exchange, 404, "text/plain", "not found");
            return;
        }
        Path file = libraryDir.resolve(name);
        if (!Files.isRegularFile(file)) {
            respond(exchange, 404, "text/plain", "not found");
            return;
        }
        try {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // 触碰失败不影响读取
        }
        long size = Files.size(file);
        String range = exchange.getRequestHeaders().getFirst("Range");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Content-Type", contentType(name));
        if (range != null && range.startsWith("bytes=")) {
            long[] span = parseRange(range, size);
            if (span == null) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                respond(exchange, 416, "text/plain", "range not satisfiable");
                return;
            }
            long start = span[0];
            long end = span[1];
            long length = end - start + 1;
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
            exchange.sendResponseHeaders(206, length);
            try (InputStream in = Files.newInputStream(file); OutputStream out = exchange.getResponseBody()) {
                skipFully(in, start);
                copy(in, out, length);
            }
        } else {
            exchange.sendResponseHeaders(200, size);
            try (InputStream in = Files.newInputStream(file); OutputStream out = exchange.getResponseBody()) {
                in.transferTo(out);
            }
        }
    }

    // ---------- 工具 ----------

    private byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("文件超过上限 " + (maxBytes / 1024 / 1024) + "MB");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - retentionMs;
        try (var stream = Files.list(libraryDir)) {
            for (Path path : stream.toList()) {
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException ignored) {
                    // 单个删除失败不影响其它
                }
            }
        } catch (IOException ignored) {
            // 目录不可读时跳过
        }
    }

    private static java.util.Map<String, String> parseQuery(URI uri) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        String query = uri.getRawQuery();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            map.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
        }
        return map;
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String normalizeExt(String raw, String kind) {
        if (raw == null) return null;
        String ext = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (ext.length() > AUDIO_LIMIT_EXT) return null;
        List<String> allowed = "cover".equals(kind) ? COVER_EXT : AUDIO_EXT;
        return allowed.contains(ext) ? ext : null;
    }

    private static boolean constantEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static long[] parseRange(String range, long size) {
        try {
            String spec = range.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash < 0) return null;
            String startRaw = spec.substring(0, dash).trim();
            String endRaw = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (startRaw.isEmpty()) {
                long suffix = Long.parseLong(endRaw);
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(startRaw);
                end = endRaw.isEmpty() ? size - 1 : Math.min(Long.parseLong(endRaw), size - 1);
            }
            if (start > end || start >= size) return null;
            return new long[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void copy(InputStream in, OutputStream out, long count) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        long remaining = count;
        while (remaining > 0) {
            int read = in.read(chunk, 0, (int) Math.min(chunk.length, remaining));
            if (read < 0) break;
            out.write(chunk, 0, read);
            remaining -= read;
        }
    }

    private static String contentType(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "mp3" -> "audio/mpeg";
            case "flac" -> "audio/flac";
            case "ogg", "oga" -> "audio/ogg";
            case "m4a", "mp4", "aac" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "opus" -> "audio/opus";
            case "webm" -> "audio/webm";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "image/jpeg";
        };
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String sha256Hex(byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest(data)) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** 供命令/调试用：当前托管文件数与目录。 */
    public String describe() {
        try (var stream = Files.list(libraryDir)) {
            long count = stream.count();
            return "托管 " + count + " 个文件（" + libraryDir + "）";
        } catch (IOException e) {
            return "托管目录不可读";
        }
    }

    public File directory() {
        return libraryDir.toFile();
    }
}
