package com.mineaudio.stream.resolve;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 网易云最小解析器：只调用 eapi 的 song/enhance/player/url/v1，返回可播放直链。
 * 凭证（MUSIC_U）从环境变量读取，绝不写入日志、配置或协议；无凭证时尝试匿名解析免费曲目。
 */
public final class NeteaseEapiResolver implements StreamResolver {

    static final String API_PATH = "/api/song/enhance/player/url/v1";
    static final String ENDPOINT = "https://interface.music.163.com" + API_PATH;
    static final String SOURCE_ID = "netease";

    private static final Set<String> SOURCES = Set.of("netease", "ncmlite");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final Pattern SONG_ID = Pattern.compile("\\d{1,20}");

    private final Config config;
    private final ResolutionCache cache = new ResolutionCache();
    private final HttpClient http;
    private final Semaphore concurrency;

    public NeteaseEapiResolver(Config config) {
        this.config = config;
        this.concurrency = new Semaphore(Math.max(1, config.maxConcurrent()));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, config.timeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(runnable -> {
                    Thread thread = new Thread(runnable, "MineAudio-Resolver");
                    thread.setDaemon(true);
                    thread.start();
                })
                .build();
    }

    public record Config(boolean enabled, String credentialEnv, String level, int timeoutMs, int maxConcurrent) {
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public boolean supports(ResolveRequest request) {
        if (!config.enabled() || request.source() == null || request.id() == null) return false;
        return SOURCES.contains(request.source().toLowerCase(Locale.ROOT));
    }

    @Override
    public CompletionStage<ResolveResult> resolve(ResolveRequest request) {
        String id = request.id().trim();
        if (!SONG_ID.matcher(id).matches()) {
            return CompletableFuture.failedFuture(
                    new ResolveException(ResolveFailureKind.INVALID_RESPONSE, "非法歌曲 ID"));
        }
        String key = SOURCE_ID + ":" + id;
        return cache.resolve(key, () -> requestUrl(key, id, true));
    }

    private CompletionStage<ResolveResult> requestUrl(String key, String id, boolean retryOnAuth) {
        String cookie = cookieHeader();
        String json = "{\"ids\":\"[" + id + "]\",\"level\":\"" + config.level()
                + "\",\"encodeType\":\"flac\",\"e_r\":true,\"header\":{\"os\":\"pc\",\"appver\":\"8.9.70\"}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofMillis(Math.max(500, config.timeoutMs())))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(EapiCrypto.body(API_PATH, json)))
                .build();

        CompletableFuture<ResolveResult> future = new CompletableFuture<>();
        try {
            concurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(new ResolveException(
                    ResolveFailureKind.TIMEOUT, "解析线程被中断"));
        }
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    concurrency.release();
                    if (error != null) {
                        future.completeExceptionally(mapTransportError(error));
                        return;
                    }
                    try {
                        future.complete(parse(response.statusCode(), response.body()));
                    } catch (ResolveException e) {
                        if (retryOnAuth && e.kind() == ResolveFailureKind.ACCOUNT_NOT_ENTITLED) {
                            cache.invalidate(key);
                            requestUrl(key, id, false).whenComplete((retry, retryError) -> {
                                if (retryError != null) future.completeExceptionally(unwrap(retryError));
                                else future.complete(retry);
                            });
                            return;
                        }
                        future.completeExceptionally(e);
                    }
                });
        return future;
    }

    static ResolveResult parse(int statusCode, String body) throws ResolveException {
        if (statusCode == 401 || statusCode == 403) {
            throw new ResolveException(ResolveFailureKind.ACCOUNT_NOT_ENTITLED,
                    "网易接口拒绝访问（HTTP " + statusCode + "）");
        }
        if (statusCode == 429) {
            throw new ResolveException(ResolveFailureKind.RATE_LIMITED, "网易接口限流");
        }
        if (statusCode != 200) {
            throw new ResolveException(ResolveFailureKind.REMOTE_UNAVAILABLE, "网易接口 HTTP " + statusCode);
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new ResolveException(ResolveFailureKind.INVALID_RESPONSE, "网易返回非法 JSON");
        }
        if (!root.has("data") || !root.get("data").isJsonArray()) {
            throw new ResolveException(ResolveFailureKind.INVALID_RESPONSE, "网易返回缺少 data");
        }
        JsonArray data = root.getAsJsonArray("data");
        if (data.isEmpty()) {
            throw new ResolveException(ResolveFailureKind.NOT_PLAYABLE, "网易未返回曲目数据");
        }
        JsonObject item = data.get(0).getAsJsonObject();
        String url = item.has("url") && !item.get("url").isJsonNull() ? item.get("url").getAsString() : null;
        int code = item.has("code") ? item.get("code").getAsInt() : -1;
        if (url == null || url.isBlank()) {
            int fee = item.has("fee") ? item.get("fee").getAsInt() : 0;
            if (fee != 0) {
                throw new ResolveException(ResolveFailureKind.ACCOUNT_NOT_ENTITLED,
                        "该曲目需要账号权限（fee=" + fee + "）");
            }
            throw new ResolveException(ResolveFailureKind.NOT_PLAYABLE,
                    "无可用播放地址（code=" + code + "）");
        }
        long expiSeconds = item.has("expi") ? item.get("expi").getAsLong() : 0;
        Instant expiresAt = expiSeconds > 0 ? Instant.now().plusSeconds(expiSeconds) : null;
        long durationMs = item.has("time") ? item.get("time").getAsLong() : 0;
        return new ResolveResult(URI.create(url), null, null, durationMs, expiresAt);
    }

    private String cookieHeader() {
        String credential = credential();
        String base = "os=pc; appver=8.9.70";
        return credential == null || credential.isBlank() ? base : base + "; MUSIC_U=" + credential;
    }

    /** 凭证只从环境变量读取；返回 null 表示未配置（匿名尝试）。 */
    private String credential() {
        String env = config.credentialEnv();
        return env == null || env.isBlank() ? null : System.getenv(env);
    }

    private static ResolveException mapTransportError(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException) {
            return new ResolveException(ResolveFailureKind.TIMEOUT, "网易接口超时");
        }
        return new ResolveException(ResolveFailureKind.REMOTE_UNAVAILABLE,
                "网易接口不可达：" + cause.getClass().getSimpleName());
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) return error.getCause();
        return error;
    }
}
