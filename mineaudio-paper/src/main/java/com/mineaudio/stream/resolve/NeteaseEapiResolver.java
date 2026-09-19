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
    static final String ENDPOINT = "https://music.163.com/eapi" + API_PATH;
    /** 曲目详情（封面/标题/歌手），响应为明文 JSON（部分环境会加密，decodeBody 两者兼容）。 */
    static final String DETAIL_PATH = "/api/v3/song/detail";
    static final String SOURCE_ID = "netease";

    private static final Set<String> SOURCES = Set.of("netease", "ncmlite");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; PCT-AL10) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/70.0.3538.64 HuaweiBrowser/10.0.3.311 Mobile Safari/537.36";
    private static final String DEVICE_HEADER_JSON =
            "\"header\":{\"os\":\"android\",\"appver\":\"8.10.10\",\"versioncode\":\"140\","
                    + "\"mobilename\":\"PCT-AL10\",\"buildver\":\"1700000000\","
                    + "\"resolution\":\"1920x1080\",\"channel\":\"xiaomi\",\"__csrf\":\"\"}";
    private static final Pattern SONG_ID = Pattern.compile("\\d{1,20}");

    private final Config config;
    private final ResolutionCache cache = new ResolutionCache();
    private final HttpClient http;
    private final Semaphore concurrency;
    private final SongMetaCache songMetaCache = new SongMetaCache();
    /** 详情请求的短超时：best-effort，不能拖住播放地址的完成。 */
    private static final int DETAIL_TIMEOUT_MS = 1500;

    /** 共享歌曲元数据缓存（与搜索互相预热）。 */
    public SongMetaCache songMetaCache() {
        return songMetaCache;
    }

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

    public record Config(boolean enabled, String credentialEnv, String credentialFile,
                         String level, int timeoutMs, int maxConcurrent, int coverPx) {
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
        return cache.resolve(key, () -> {
            // A1：主线程调用永不阻塞——无许可时直接返回限流错误
            if (!concurrency.tryAcquire()) {
                return CompletableFuture.failedFuture(new ResolveException(
                        ResolveFailureKind.RATE_LIMITED, "网易解析并发已满，请稍后重试"));
            }
            CompletionStage<ResolveResult> stage = requestUrl(key, id, true);
            stage.whenComplete((result, error) -> concurrency.release());
            return stage;
        });
    }

    private CompletionStage<ResolveResult> requestUrl(String key, String id, boolean retryOnAuth) {
        String cookie = cookieHeader();
        String json = "{\"ids\":\"[" + id + "]\",\"level\":\"" + config.level()
                + "\",\"encodeType\":\"flac\",\"e_r\":true," + DEVICE_HEADER_JSON + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofMillis(Math.max(500, config.timeoutMs())))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com")
                .header("X-Real-IP", "118.88.88.88")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(EapiCrypto.body(API_PATH, json)))
                .build();

        CompletableFuture<ResolveResult> future = new CompletableFuture<>();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        future.completeExceptionally(mapTransportError(error));
                        return;
                    }
                    try {
                        ResolveResult parsed = parse(response.statusCode(), decodeBody(response.body()));
                        SongMetaCache.Meta meta = songMetaCache.get(key);
                        ResolveResult withMeta = meta == null ? parsed : applyMeta(parsed, meta);
                        if (meta != null) {
                            future.complete(withMeta);
                            return;
                        }
                        enrichWithDetail(id, withMeta).whenComplete((enriched, enrichError) ->
                                future.complete(enrichError == null && enriched != null ? enriched : withMeta));
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
            throw new ResolveException(ResolveFailureKind.REMOTE_UNAVAILABLE,
                    "网易接口 HTTP " + statusCode + "：" + snippet(body));
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new ResolveException(ResolveFailureKind.INVALID_RESPONSE,
                    "网易返回非 JSON：" + snippet(body));
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
        return new ResolveResult(upgradeToHttps(url), null, null, durationMs, expiresAt, null);
    }

    /** 网易 CDN 常返回 http 链接；升级为 https（客户端防火墙要求，CDN 实测支持）。 */
    static URI upgradeToHttps(String url) {
        URI uri = URI.create(url);
        if (!"http".equalsIgnoreCase(uri.getScheme())) return uri;
        try {
            return new URI("https", uri.getUserInfo(), uri.getHost(), uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (java.net.URISyntaxException e) {
            return uri;
        }
    }

    /** eapi 响应默认 AES-ECB 加密；解密失败时按明文处理（部分错误响应是明文 JSON）。 */
    /** 拉曲目详情补封面/标题/歌手；失败一律回退 base，不影响播放。 */
    private CompletionStage<ResolveResult> enrichWithDetail(String id, ResolveResult base) {
        String json = "{\"c\":\"[{\\\"id\\\":\\\"" + id + "\\\"}]\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://music.163.com/eapi" + DETAIL_PATH))
                .timeout(Duration.ofMillis(Math.max(500, Math.min(config.timeoutMs(), DETAIL_TIMEOUT_MS))))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com")
                .header("X-Real-IP", "118.88.88.88")
                .header("Cookie", cookieHeader())
                .POST(HttpRequest.BodyPublishers.ofString(EapiCrypto.body(DETAIL_PATH, json)))
                .build();
        CompletableFuture<ResolveResult> enriched = new CompletableFuture<>();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, error) -> {
                    if (error != null || response.statusCode() != 200) {
                        enriched.complete(base);
                        return;
                    }
                    try {
                        ResolveResult merged = mergeDetail(base, decodeBody(response.body()), config.coverPx());
                        songMetaCache.put(SOURCE_ID + ":" + id, merged.title(),
                                merged.artist(), merged.coverUrl());
                        enriched.complete(merged);
                    } catch (Exception e) {
                        enriched.complete(base);
                    }
                });
        return enriched;
    }

    /** 命中元数据缓存时直接补齐（同一次请求内不重复拉详情）。 */
    private static ResolveResult applyMeta(ResolveResult base, SongMetaCache.Meta meta) {
        return new ResolveResult(base.streamUrl(),
                meta.title() != null && !meta.title().isBlank() ? meta.title() : base.title(),
                meta.artist() != null ? meta.artist() : base.artist(),
                base.durationMs(), base.expiresAt(),
                meta.coverUrl() != null ? meta.coverUrl() : base.coverUrl());
    }

    /** 解析 song/detail 响应，失败字段回退 base。 */
    static ResolveResult mergeDetail(ResolveResult base, String body, int coverPx) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray songs = root.has("songs") ? root.getAsJsonArray("songs") : null;
            if (songs == null || songs.isEmpty()) return base;
            JsonObject song = songs.get(0).getAsJsonObject();
            String title = song.has("name") && !song.get("name").isJsonNull()
                    ? song.get("name").getAsString() : base.title();
            String artist = base.artist();
            if (song.has("ar") && song.get("ar").isJsonArray() && !song.getAsJsonArray("ar").isEmpty()) {
                JsonObject first = song.getAsJsonArray("ar").get(0).getAsJsonObject();
                if (first.has("name") && !first.get("name").isJsonNull()) {
                    artist = first.get("name").getAsString();
                }
            }
            String cover = base.coverUrl();
            if (song.has("al") && song.get("al").isJsonObject()) {
                JsonObject album = song.getAsJsonObject("al");
                if (album.has("picUrl") && !album.get("picUrl").isJsonNull()) {
                    cover = com.mineaudio.stream.CoverUrls.thumb(
                            album.get("picUrl").getAsString(), coverPx);
                }
            }
            return new ResolveResult(base.streamUrl(), title, artist, base.durationMs(),
                    base.expiresAt(), cover);
        } catch (Exception e) {
            return base;
        }
    }

    static String decodeBody(byte[] body) {
        // 明文响应直接使用；否则才尝试 AES 解密（避免明文长度恰为 16 倍数时解出乱码）
        String plain = new String(body, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (plain.startsWith("{")) {
            return plain;
        }
        try {
            return EapiCrypto.decrypt(body);
        } catch (Exception e) {
            return plain;
        }
    }

    private static String snippet(String body) {
        if (body == null) return "(空响应)";
        String trimmed = body.strip();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120) + "…";
    }

    public String cookieHeader() {
        String credential = credential();
        String base = "os=android; appver=8.10.10; deviceId=MineAudioClient";
        return credential == null || credential.isBlank() ? base : base + "; MUSIC_U=" + credential;
    }

    /** 凭证优先读环境变量，其次读凭证文件（一行，支持 # 注释）；返回 null 表示未配置。 */
    private String credential() {
        String env = config.credentialEnv();
        if (env != null && !env.isBlank()) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) return value.trim();
        }
        String file = config.credentialFile();
        if (file == null || file.isBlank()) return null;
        try {
            java.nio.file.Path path = java.nio.file.Path.of(file);
            if (!java.nio.file.Files.isRegularFile(path)) return null;
            for (String line : java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) return trimmed;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    public boolean hasCredential() {
        return credential() != null;
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
