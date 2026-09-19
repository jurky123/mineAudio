package com.mineaudio.stream.search;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineaudio.stream.resolve.EapiCrypto;
import com.mineaudio.stream.resolve.ResolveException;
import com.mineaudio.stream.resolve.ResolveFailureKind;

/**
 * 网易搜索（免签明文接口）+ 批量 song/detail 补封面/标题/歌手。
 * 结果缓存 5 分钟并合并同关键词并发请求；失败按 ResolveFailureKind 分类。
 */
public final class NeteaseSearch {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; PCT-AL10) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/70.0.3538.64 HuaweiBrowser/10.0.3.311 Mobile Safari/537.36";
    private static final String DETAIL_PATH = "/api/v3/song/detail";
    private static final String DETAIL_ENDPOINT = "https://music.163.com/eapi" + DETAIL_PATH;
    private static final long CACHE_TTL_MS = 5 * 60_000L;
    private static final int MAX_KEYWORD_LENGTH = 40;

    private record Cached(long expiresAt, CompletionStage<List<SearchResult>> stage) {
    }

    private final boolean enabled;
    private final int timeoutMs;
    private final int maxResults;
    private final Supplier<String> cookieSupplier;
    private final HttpClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public NeteaseSearch(boolean enabled, int timeoutMs, int maxResults, Supplier<String> cookieSupplier) {
        this.enabled = enabled;
        this.timeoutMs = Math.max(500, timeoutMs);
        this.maxResults = Math.max(1, Math.min(10, maxResults));
        this.cookieSupplier = cookieSupplier;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .build();
    }

    public CompletionStage<List<SearchResult>> search(String keyword) {
        if (!enabled) {
            return CompletableFuture.failedFuture(
                    new ResolveException(ResolveFailureKind.DISABLED, "搜索已禁用"));
        }
        if (keyword == null || keyword.isBlank()) {
            return CompletableFuture.failedFuture(
                    new ResolveException(ResolveFailureKind.INVALID_RESPONSE, "关键词为空"));
        }
        String trimmed = keyword.strip();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            trimmed = trimmed.substring(0, MAX_KEYWORD_LENGTH);
        }
        String key = trimmed.toLowerCase();
        long now = System.currentTimeMillis();
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.stage();
        }
        CompletableFuture<List<SearchResult>> future = new CompletableFuture<>();
        Cached fresh = new Cached(now + CACHE_TTL_MS, future);
        Cached existing = cache.putIfAbsent(key, fresh);
        if (existing != null && existing.expiresAt() > now) {
            return existing.stage();
        }
        if (existing != null) {
            cache.put(key, fresh);
        }
        requestSearch(trimmed).whenComplete((results, error) -> {
            if (error != null) {
                cache.remove(key, fresh);
                future.completeExceptionally(unwrap(error));
            } else {
                future.complete(results);
            }
        });
        return future;
    }

    private CompletionStage<List<SearchResult>> requestSearch(String keyword) {
        String url = "https://music.163.com/api/search/get/?type=1&limit=" + maxResults
                + "&offset=0&s=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com")
                .header("Cookie", cookieSupplier.get())
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    if (response.statusCode() == 429) {
                        return CompletableFuture.failedFuture(
                                new ResolveException(ResolveFailureKind.RATE_LIMITED, "网易搜索限流"));
                    }
                    if (response.statusCode() != 200) {
                        return CompletableFuture.failedFuture(new ResolveException(
                                ResolveFailureKind.REMOTE_UNAVAILABLE,
                                "网易搜索 HTTP " + response.statusCode()));
                    }
                    List<SearchResult> base;
                    try {
                        base = parseSearch(response.body());
                    } catch (ResolveException e) {
                        return CompletableFuture.failedFuture(e);
                    }
                    if (base.isEmpty()) {
                        return CompletableFuture.completedFuture(base);
                    }
                    return enrich(base);
                });
    }

    static List<SearchResult> parseSearch(String body) throws ResolveException {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new ResolveException(ResolveFailureKind.INVALID_RESPONSE, "网易搜索返回非 JSON");
        }
        JsonArray songs = null;
        if (root.has("result") && root.get("result").isJsonObject()) {
            JsonObject result = root.getAsJsonObject("result");
            if (result.has("songs") && result.get("songs").isJsonArray()) {
                songs = result.getAsJsonArray("songs");
            }
        }
        List<SearchResult> results = new ArrayList<>();
        if (songs == null) return results;
        for (var element : songs) {
            if (!element.isJsonObject()) continue;
            JsonObject song = element.getAsJsonObject();
            if (!song.has("id")) continue;
            String id = song.get("id").getAsString();
            String title = song.has("name") && !song.get("name").isJsonNull()
                    ? song.get("name").getAsString() : "";
            String artist = "";
            if (song.has("artists") && song.get("artists").isJsonArray()
                    && !song.getAsJsonArray("artists").isEmpty()) {
                artist = stringOr(song.getAsJsonArray("artists").get(0).getAsJsonObject(), "name", "");
            }
            long duration = song.has("duration") ? song.get("duration").getAsLong() : 0;
            int fee = song.has("fee") ? song.get("fee").getAsInt() : 0;
            results.add(new SearchResult("ncmlite", id, title, artist, null, duration,
                    playableFee(fee), noteFee(fee)));
        }
        return results;
    }

    private CompletionStage<List<SearchResult>> enrich(List<SearchResult> base) {
        StringBuilder ids = new StringBuilder();
        for (SearchResult result : base) {
            if (ids.length() > 0) ids.append(',');
            ids.append("{\"id\":\"").append(result.id()).append("\"}");
        }
        String json = "{\"c\":\"[" + ids + "]\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(DETAIL_ENDPOINT))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com")
                .header("Cookie", cookieSupplier.get())
                .POST(HttpRequest.BodyPublishers.ofString(EapiCrypto.body(DETAIL_PATH, json)))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() != 200) return base;
                    try {
                        return mergeDetail(base, decodeBody(response.body()));
                    } catch (Exception e) {
                        return base;
                    }
                });
    }

    static List<SearchResult> mergeDetail(List<SearchResult> base, String body) {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return base;
        }
        if (!root.has("songs") || !root.get("songs").isJsonArray()) return base;
        Map<String, JsonObject> byId = new ConcurrentHashMap<>();
        for (var element : root.getAsJsonArray("songs")) {
            if (!element.isJsonObject()) continue;
            JsonObject song = element.getAsJsonObject();
            if (song.has("id")) byId.put(song.get("id").getAsString(), song);
        }
        List<SearchResult> merged = new ArrayList<>(base.size());
        for (SearchResult result : base) {
            JsonObject song = byId.get(result.id());
            if (song == null) {
                merged.add(result);
                continue;
            }
            String title = stringOr(song, "name", result.title());
            String artist = result.artist();
            if (song.has("ar") && song.get("ar").isJsonArray() && !song.getAsJsonArray("ar").isEmpty()) {
                artist = stringOr(song.getAsJsonArray("ar").get(0).getAsJsonObject(), "name", artist);
            }
            String cover = result.coverUrl();
            if (song.has("al") && song.get("al").isJsonObject()) {
                cover = stringOr(song.getAsJsonObject("al"), "picUrl", cover);
            }
            merged.add(new SearchResult(result.source(), result.id(), title, artist, cover,
                    result.durationMs(), result.playable(), result.note()));
        }
        return merged;
    }

    private static String decodeBody(byte[] body) {
        try {
            return EapiCrypto.decrypt(body);
        } catch (Exception e) {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static boolean playableFee(int fee) {
        return fee == 0 || fee == 1 || fee == 8;
    }

    private static String noteFee(int fee) {
        return switch (fee) {
            case 0 -> "可播放";
            case 1 -> "VIP";
            case 8 -> "标准音质";
            case 4 -> "需购买";
            default -> "未知";
        };
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        if (object.has(key) && !object.get(key).isJsonNull()) {
            return object.get(key).getAsString();
        }
        return fallback;
    }

    private static Throwable unwrap(Throwable error) {
        return error.getCause() != null ? error.getCause() : error;
    }
}
