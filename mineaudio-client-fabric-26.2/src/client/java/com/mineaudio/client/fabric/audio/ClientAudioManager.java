package com.mineaudio.client.fabric.audio;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mineaudio.client.ProtocolClient;
import com.mineaudio.client.decode.AudioDecoder;
import com.mineaudio.client.decode.SeekStatus;
import com.mineaudio.client.decode.PcmRingBuffer;
import com.mineaudio.client.media.MediaCache;
import com.mineaudio.client.media.MediaFirewall;
import com.mineaudio.client.media.MediaSecurityException;
import com.mineaudio.client.media.SecureMediaGateway;
import com.mineaudio.protocol.Packets;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 客户端播放会话管理：接收服务端 PLAY/STOP/PAUSE/RESUME/SEEK/VOLUME 指令，
 * 通过 Minecraft 自带的 OpenAL 通道（{@link ChannelAccess}）播放 LavaPlayer 解码的 PCM，
 * 并按上报间隔回传状态。所有 Channel 操作都通过 {@link ChannelAccess.ChannelHandle} 在声音引擎线程执行。
 */
public final class ClientAudioManager implements ProtocolClient.Listener {

    public static final List<String> CAPABILITIES = List.of(
            "stream_playback", "seek", "pause", "volume", "multi_session", "positional");
    public static final List<String> FORMATS = List.of(
            "mp3", "aac", "m4a", "flac", "wav", "ogg", "webm");

    private static final int RING_BYTES = 48000 * 2 * 2 * 2;
    private static final int BYTES_PER_MS = 48000 * 2 * 2 / 1000;
    /** 超过该时长视为未知（LavaPlayer 对无 Content-Length 的流会报 Long.MAX_VALUE）。 */
    private static final long MAX_REASONABLE_DURATION_MS = 12L * 60 * 60 * 1000;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /**
     * 本地控件绑定的会话：严格只选 MUSIC；环境音/音效会话永不抢占。
     * 服务端 UI/HUD 读的是 orchestrator 的 currentMusic，本字段与之同义（客户端视角的当前音乐）。
     */
    private volatile Session current;

    private void reselectCurrent() {
        Session music = null;
        for (Session candidate : sessions.values()) {
            if ("MUSIC".equalsIgnoreCase(candidate.bus)) {
                music = candidate;
                break;
            }
        }
        current = music;
    }
    /** 本地动作命令序号（仅用于 UI 展示/调试，不下发）。 */
    private final java.util.concurrent.atomic.AtomicLong localCommandSeq = new java.util.concurrent.atomic.AtomicLong();
    private ChannelAccess channelAccess;
    private volatile boolean available;
    private MediaFirewall.Policy serverPolicy;
    private MediaFirewall firewall;
    private SecureMediaGateway gateway;
    private boolean gatewayTried;
    /** 客户端本地曲库（玩家自有文件，仅本地存储/播放）。 */
    private volatile com.mineaudio.client.fabric.library.LocalLibraryService library;
    /** 本地曲库分页：每页固定插槽数（MineUI list 不支持 local 绑定，改用固定插槽）。 */
    private static final int LIB_PAGE_SIZE = 8;
    private volatile int libPage;
    private volatile long libPageGeneration;

    public void setLibrary(com.mineaudio.client.fabric.library.LocalLibraryService library) {
        this.library = library;
    }

    /** 本地曲库结构性变化计数（供 MineUI 触发列表重排）。 */
    public long localGeneration() {
        com.mineaudio.client.fabric.library.LocalLibraryService lib = library;
        return (lib == null ? 0L : lib.library().generation()) + libPageGeneration;
    }

    /** 本地曲库标量状态：lib_count / lib_note / lib_page / lib_<slot>_<title|artist|time|present>。 */
    private Object libraryState(String key) {
        com.mineaudio.client.fabric.library.LocalLibraryService lib = library;
        if (key.equals("lib_count")) {
            return lib == null ? 0 : lib.library().size();
        }
        if (key.equals("lib_note")) {
            return lib == null ? "" : lib.library().note();
        }
        int total = lib == null ? 0 : lib.library().size();
        int pages = Math.max(1, (total + LIB_PAGE_SIZE - 1) / LIB_PAGE_SIZE);
        int page = Math.max(0, Math.min(libPage, pages - 1));
        if (key.equals("lib_page")) {
            return "第 " + (page + 1) + "/" + pages + " 页";
        }
        // 支持 lib0_title 与 lib_0_title 两种写法
        String rest = key.substring(3);
        if (rest.startsWith("_")) rest = rest.substring(1);
        int us = rest.indexOf('_');
        if (us <= 0) return null;
        int slot;
        try {
            slot = Integer.parseInt(rest.substring(0, us));
        } catch (NumberFormatException e) {
            return null;
        }
        String field = rest.substring(us + 1);
        com.mineaudio.client.library.LocalTrack track = lib == null ? null
                : lib.library().track(page * LIB_PAGE_SIZE + slot);
        if (field.equals("present")) {
            return track != null ? "true" : "false";
        }
        if (track == null) return "";
        return switch (field) {
            case "title" -> track.title() == null ? "" : track.title();
            case "artist" -> track.artist() == null ? "" : track.artist();
            case "time" -> track.timeText();
            default -> null;
        };
    }

    /** 媒体防火墙：本地默认策略与服务端策略取交集。 */
    private synchronized MediaFirewall firewall() {
        if (firewall == null) {
            MediaFirewall.Policy local = MediaFirewall.Policy.defaults();
            if (serverPolicy != null) {
                local = local.intersect(serverPolicy);
            }
            firewall = new MediaFirewall(local);
        }
        return firewall;
    }

    /** 本机媒体网关（含缓存）；创建失败时返回 null，退回直连。 */
    private synchronized SecureMediaGateway gateway() {
        if (gatewayTried) return gateway;
        gatewayTried = true;
        try {
            java.nio.file.Path dir = FabricLoader.getInstance().getGameDir().resolve("mineaudio-cache");
            MediaCache cache = new MediaCache(dir, 2L * 1024 * 1024 * 1024, 256L * 1024 * 1024);
            cache.init();
            cache.cleanupParts();
            gateway = new SecureMediaGateway(firewall(), cache);
        } catch (Throwable t) {
            com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.warn(
                    "[audio] 媒体网关初始化失败，直连播放：{}", t.toString());
            gateway = null;
        }
        return gateway;
    }

    /** 声音引擎通道是否可用（反射失败时禁用流媒体能力，退回服务端 fallback）。 */
    public boolean available() {
        if (!available) {
            resolveChannelAccess();
        }
        return available;
    }

    private synchronized void resolveChannelAccess() {
        if (available) return;
        try {
            SoundManager manager = Minecraft.getInstance().getSoundManager();
            if (manager == null) return;
            Field engineField = SoundManager.class.getDeclaredField("soundEngine");
            engineField.setAccessible(true);
            SoundEngine engine = (SoundEngine) engineField.get(manager);
            if (engine == null) return;
            Field accessField = SoundEngine.class.getDeclaredField("channelAccess");
            accessField.setAccessible(true);
            channelAccess = (ChannelAccess) accessField.get(engine);
            available = channelAccess != null;
        } catch (Throwable t) {
            channelAccess = null;
            available = false;
            com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.warn(
                    "无法接入 Minecraft 音频通道，流媒体播放不可用：{}", t.toString());
        }
    }

    // ---------- 服务端指令 ----------

    @Override
    public void onPlay(String sessionId, Packets.Play play) {
        if (sessionId == null || play.url() == null || play.url().isBlank()) {
            ProtocolClient.get().sendError(sessionId, play.resourceVersion(),
                    "BAD_REQUEST", "PLAY 缺少 sessionId/url");
            return;
        }
        Session existing = sessions.get(sessionId);
        if (existing != null && existing.revision >= play.resourceVersion()) {
            return;
        }
        if (existing != null) existing.stop();
        Session session = new Session(sessionId, play);
        sessions.put(sessionId, session);
        // 本地控件只针对 MUSIC；环境音/音效会话永不成为 current。
        // 不变量：本客户端任一时刻至多一条 MUSIC 会话，收到新 MUSIC 时停掉其它 MUSIC（防包序/旧服务端叠加）
        if ("MUSIC".equalsIgnoreCase(play.bus())) {
            for (Session other : List.copyOf(sessions.values())) {
                if (other != session && "MUSIC".equalsIgnoreCase(other.bus)) {
                    sessions.remove(other.id);
                    other.stop();
                }
            }
            current = session;
        }
        session.start();
    }

    @Override
    public void onStop(String sessionId, Packets.Stop stop) {
        Session session = sessions.remove(sessionId);
        if (session != null) {
            session.stop();
            if (current == session) {
                reselectCurrent();
            }
        }
    }

    @Override
    public void onPause(String sessionId, Packets.Pause pause) {
        Session session = sessions.get(sessionId);
        if (session != null) session.schedule(pause.executeAtServerTime(), () -> session.setPaused(true));
    }

    @Override
    public void onResume(String sessionId, Packets.Resume resume) {
        Session session = sessions.get(sessionId);
        if (session != null) session.schedule(resume.executeAtServerTime(), () -> session.setPaused(false));
    }

    @Override
    public void onSeek(String sessionId, Packets.Seek seek) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.schedule(seek.executeAtServerTime(), () -> session.seek(seek.requestId(), seek.positionMs()));
        }
    }

    @Override
    public void onVolume(String sessionId, Packets.Volume volume) {
        Session session = sessions.get(sessionId);
        if (session != null) session.setVolume(volume.volume());
    }

    @Override
    public void onHelloAck(Packets.HelloAck ack) {
        if (ack.firewall() != null) {
            serverPolicy = new MediaFirewall.Policy(
                    ack.firewall().httpsOnly(), ack.firewall().denyPrivateNetwork(),
                    ack.firewall().maxRedirects(), List.of(), List.of());
            firewall = null;
        }
        com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                "服务端已确认 MineAudio 客户端（server={} report={}ms）",
                ack.serverVersion(), ack.reportIntervalMs());
    }

    @Override
    public void onTickReport() {
        for (Session session : sessions.values()) {
            session.report();
        }
    }

    /** 每个客户端 tick 调用：泵送流缓冲、实时音量，并在播放音乐时压掉原版背景音乐。 */
    public void tick() {
        boolean suppressVanilla = false;
        for (Session session : sessions.values()) {
            session.pump();
            if (session.isMusicActive()) {
                suppressVanilla = true;
            }
        }
        if (suppressVanilla) {
            try {
                Minecraft.getInstance().getMusicManager().stopPlaying();
            } catch (Throwable ignored) {
                // 音乐管理器不可用时忽略
            }
        }
    }

    public void closeAll() {
        for (Session session : sessions.values()) {
            session.stop();
        }
        sessions.clear();
        current = null;
    }

    // ---------- MineUI 本地状态 / 动作（{local.mineaudio.*} / local:mineaudio.*） ----------

    /** 本地状态键：position / duration / percent / time / playing / buffering / volume / visible / library。 */
    public Object localState(String key) {
        // 本地曲库状态不依赖当前会话
        if (key.equals("library")) {
            java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
            com.mineaudio.client.fabric.library.LocalLibraryService lib = library;
            if (lib != null) {
                int index = 0;
                for (com.mineaudio.client.library.LocalTrack track : lib.library().tracks()) {
                    java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("index", index++);
                    item.put("title", track.title() == null ? "" : track.title());
                    item.put("artist", track.artist() == null ? "" : track.artist());
                    item.put("time", track.timeText());
                    items.add(item);
                }
            }
            return items;
        }
        if (key.startsWith("lib")) {
            return libraryState(key);
        }
        Session session = current;
        if (session == null) return null;
        long duration = session.knownDurationMs();
        long position = session.clock.positionMs();
        return switch (key) {
            case "position" -> position;
            case "duration" -> duration;
            case "percent" -> duration > 0
                    ? Math.round(1000.0 * Math.min(position, duration) / duration) / 10.0 : 0.0;
            case "time" -> formatTime(position) + (duration > 0 ? " / " + formatTime(duration) : "");
            case "playing" -> session.isPresentationPlaying();
            case "buffering" -> session.isBuffering();
            case "volume" -> Math.round(session.currentVolume() * 100);
            case "cover" -> session.coverUrl;
            case "visible" -> duration > 0 && session.hasAudibleContent();
            default -> null;
        };
    }

    /** 本地动作：pause / resume / seek / seek_back / seek_fwd / volume / lib_*。 */
    public boolean localAction(String action, Map<String, Object> payload) {
        if (action.startsWith("lib_")) {
            return libraryAction(action, payload);
        }
        Session session = current;
        if (session == null) return false;
        long duration = session.knownDurationMs();
        switch (action) {
            case "pause" -> {
                session.setPaused(true);
                return true;
            }
            case "resume" -> {
                session.setPaused(false);
                return true;
            }
            case "seek" -> {
                Object value = payload == null ? null : payload.get("value");
                if (!(value instanceof Number number) || duration <= 0) return false;
                double percent = Math.max(0, Math.min(100, number.doubleValue()));
                return session.seek(localCommandSeq.incrementAndGet(),
                        Math.round(duration * percent / 100.0)) == SeekStatus.APPLIED;
            }
            case "seek_back" -> {
                return session.seek(localCommandSeq.incrementAndGet(),
                        Math.max(0, session.clock.positionMs() - 15_000)) == SeekStatus.APPLIED;
            }
            case "seek_fwd" -> {
                long target = session.clock.positionMs() + 15_000;
                return session.seek(localCommandSeq.incrementAndGet(),
                        duration > 0 ? Math.min(target, duration) : target) == SeekStatus.APPLIED;
            }
            case "volume" -> {
                Object value = payload == null ? null : payload.get("value");
                if (!(value instanceof Number number)) return false;
                session.setVolume((float) (Math.max(0, Math.min(100, number.doubleValue())) / 100.0));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static int indexOf(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("index");
        return value instanceof Number number ? number.intValue() : -1;
    }

    /** 本地曲库动作：刷新 / 翻页 / 试听 / 点歌(M2占位) / 删除。 */
    private boolean libraryAction(String action, Map<String, Object> payload) {
        com.mineaudio.client.fabric.library.LocalLibraryService lib = library;
        switch (action) {
            case "lib_refresh" -> {
                if (lib == null) return false;
                libPage = 0;
                libPageGeneration++;
                lib.scanAsync();
                return true;
            }
            case "lib_page_prev" -> {
                if (libPage > 0) {
                    libPage--;
                    libPageGeneration++;
                }
                return true;
            }
            case "lib_page_next" -> {
                int total = lib == null ? 0 : lib.library().size();
                int pages = Math.max(1, (total + LIB_PAGE_SIZE - 1) / LIB_PAGE_SIZE);
                if (libPage < pages - 1) {
                    libPage++;
                    libPageGeneration++;
                }
                return true;
            }
            default -> {
                // 继续解析 lib_<slot>_<action>
            }
        }
        if (lib == null) return false;
        int slot = slotOf(action);
        if (slot >= 0) {
            int absolute = libPage * LIB_PAGE_SIZE + slot;
            if (action.startsWith("lib_play_")) return playLocalByIndex(absolute);
            if (action.startsWith("lib_delete_")) return lib.library().delete(absolute);
            if (action.startsWith("lib_queue_")) return false; // M2：上传分发
        }
        // 兼容带 payload index 的旧写法（list 版本）
        if (action.equals("lib_play")) return playLocalByIndex(indexOf(payload));
        if (action.equals("lib_delete")) {
            int index = indexOf(payload);
            return index >= 0 && lib.library().delete(index);
        }
        return false;
    }

    private static int slotOf(String action) {
        int last = action.lastIndexOf('_');
        if (last < 0 || last == action.length() - 1) return -1;
        try {
            return Integer.parseInt(action.substring(last + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 本地试听：只在本机播放玩家自有文件，不经过服务器。 */
    private boolean playLocalByIndex(int index) {
        com.mineaudio.client.fabric.library.LocalLibraryService lib = library;
        if (lib == null) return false;
        com.mineaudio.client.library.LocalTrack track = lib.library().track(index);
        if (track == null) return false;
        playLocalPreview(track);
        return true;
    }

    private void playLocalPreview(com.mineaudio.client.library.LocalTrack track) {
        String sessionId = "local:" + track.id();
        Session existing = sessions.get(sessionId);
        if (existing != null) existing.stop();
        // 不变量：至多一条 MUSIC，进入本地试听先停其它 MUSIC
        for (Session other : List.copyOf(sessions.values())) {
            if (other != existing && "MUSIC".equalsIgnoreCase(other.bus)) {
                sessions.remove(other.id);
                other.stop();
            }
        }
        Packets.Play play = new Packets.Play(
                "local:" + track.id(), "local", track.id(),
                track.playableFile().toAbsolutePath().toString(),
                Map.of(), 0, 0, 0, 0, 1f, "MUSIC", 0, track.durationMs(),
                track.title(), track.artist(), null, null);
        Session session = new Session(sessionId, play, true, track);
        sessions.put(sessionId, session);
        current = session;
        session.start();
    }

    private static String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    // ---------- 会话 ----------

    private final class Session implements AudioDecoder.Sink {

        private final String id;
        private final int revision;
        private final String url;
        private final long startPositionMs;
        private final long serverStartTimeMs;
        private final String bus;
        private final long durationHintMs;
        private final String coverUrl;
        private final Packets.Play.Spatial spatial;
        /** 本地文件试听：不校验/不经过媒体网关，不向服务端上报状态。 */
        private final boolean local;
        /** 本地试听项（.ncm 在 prepare 线程按需解密）。 */
        private final com.mineaudio.client.library.LocalTrack localTrack;
        /** 媒体内容标识（不含会话身份），跨会话复用缓存。 */
        private final String cacheKey;
        private final PcmRingBuffer ring = new PcmRingBuffer(RING_BYTES);
        private volatile PcmAudioStream stream = new PcmAudioStream(ring);
        /** 通道代际：seek/恢复重建通道时防止旧 createHandle 回调覆盖新通道。 */
        private final java.util.concurrent.atomic.AtomicLong channelEpoch = new java.util.concurrent.atomic.AtomicLong();
        private final LavaPlayerDecoder decoder = new LavaPlayerDecoder();
        private final java.util.concurrent.atomic.AtomicBoolean firstPcm = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean channelRequested = new java.util.concurrent.atomic.AtomicBoolean();
        /** seek 与 PCM 写入的串行化锁：保证“检查代际+写入”与“seek+清空”互斥。 */
        private final Object ioLock = new Object();
        private volatile boolean draining;
        private volatile long drainStartedAt;
        /** 排空中进入暂停的时刻：恢复时把这段时间加回排空计时，避免暂停耗尽尾部。 */
        private volatile long drainPauseStartMs;
        /** OpenAL 源真正停止（排队缓冲播完）：由声音线程回填，供 DRAINING 终态判定。 */
        private final java.util.concurrent.atomic.AtomicBoolean drainChannelStopped =
                new java.util.concurrent.atomic.AtomicBoolean();
        private static final long SEEK_FILTER_TOLERANCE_MS = 1500;
        private static final long SEEK_FILTER_TIMEOUT_MS = 3000;

        private volatile ChannelAccess.ChannelHandle handle;
        private volatile String localUrl;
        private volatile float volume;
        private volatile float lastAppliedVolume = -1f;
        private volatile boolean started;
        private volatile boolean closed;
        private volatile String errorMessage;
        private volatile int seq;
        private volatile long pendingActionAt;
        private volatile Runnable pendingAction;
        private volatile long pendingSeekTargetMs = -1;
        private volatile long pendingSeekDeadlineAt;
        private volatile long pendingSeekRequestId;
        private volatile long lastCommandId;
        /** 播放时钟状态机：唯一允许写“听感位置”的组件（纯 Java，可单测）。 */
        private final com.mineaudio.client.playback.PlaybackClock clock =
                new com.mineaudio.client.playback.PlaybackClock();
        /** 起播时真正会先被听到的媒体位置（startPosition，或迟到跳播后的位置）。 */
        private volatile long startAnchorMs;
        private final long createdAtMs = System.currentTimeMillis();

        Session(String sessionId, Packets.Play play) {
            this(sessionId, play, false, null);
        }

        Session(String sessionId, Packets.Play play, boolean localOnly) {
            this(sessionId, play, localOnly, null);
        }

        Session(String sessionId, Packets.Play play, boolean localOnly,
                com.mineaudio.client.library.LocalTrack localTrack) {
            this.id = sessionId;
            this.local = localOnly;
            this.localTrack = localTrack;
            this.revision = play.resourceVersion();
            this.url = play.url();
            this.cacheKey = buildCacheKey(play);
            this.startPositionMs = play.positionMs();
            this.serverStartTimeMs = play.serverStartTime();
            this.bus = play.bus();
            this.durationHintMs = play.durationHintMs();
            this.coverUrl = play.coverUrl();
            this.spatial = play.spatial();
            this.volume = Math.max(0f, Math.min(1f, play.volume()));
            this.clock.reset(play.durationHintMs());
        }

        void start() {
            com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                    "[audio] PLAY session={} url={} startPos={}ms volume={} spatial={}",
                    id, url, startPositionMs, volume, spatial != null);
            Thread thread = new Thread(this::prepare, "MineAudio-Prepare");
            thread.setDaemon(true);
            thread.start();
        }

        /** 校验 URL 并注册到本机网关（含缓存），解码器只访问 127.0.0.1。 */
        private void prepare() {
            String playUrl = url;
            if (local) {
                // 本地文件直接交给解码器；.ncm 在 prepare 线程按需解密（不阻塞渲染线程）
                String path = playUrl;
                if (localTrack != null && library != null) {
                    try {
                        path = library.ensureDecoded(localTrack).toString();
                    } catch (Throwable t) {
                        fail("LOCAL_DECODE", t.toString());
                        return;
                    }
                }
                decoder.start(path, startPositionMs, this);
                return;
            }
            try {
                URI uri = URI.create(url);
                firewall().validate(uri);
                SecureMediaGateway gw = gateway();
                if (gw != null) {
                    String local = gw.register(new SecureMediaGateway.Resource(
                            cacheKey, uri, Map.of(), 0));
                    if (closed) {
                        gw.unregister(local);
                        return;
                    }
                    localUrl = local;
                    playUrl = local;
                } else {
                    com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.warn(
                            "[audio] 媒体网关不可用，直连播放 session={}", id);
                }
            } catch (MediaSecurityException e) {
                Minecraft.getInstance().execute(() -> fail(e.code(), e.getMessage()));
                return;
            } catch (Throwable t) {
                com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.warn(
                        "[audio] 媒体网关准备失败，直连播放 session={}：{}", id, t.toString());
            }
            decoder.start(playUrl, startPositionMs, this);
        }

        /** 媒体内容键：会话身份与媒体身份分离，重复播放同一曲目可命中缓存。 */
        private static String buildCacheKey(Packets.Play play) {
            String identity = play.sourceId() == null || play.sourceId().isBlank()
                    ? play.url()
                    : play.source() + "|" + play.sourceId();
            String track = play.trackId() == null ? "" : play.trackId();
            return track + "|" + identity + "|v" + play.resourceVersion();
        }

        boolean isMusicActive() {
            return !closed && !clock.ended() && "MUSIC".equalsIgnoreCase(bus);
        }

        /** 到服务端起播时刻（校时对齐）且首批 PCM 就绪后创建通道，避免提前排静音导致 underrun。 */
        private void maybeStart() {
            if (started || closed) return;
            boolean synced = ProtocolClient.get().clock().rttMs() > 0;
            long now = ProtocolClient.get().clock().serverNow();
            if (serverStartTimeMs > 0 && synced && now < serverStartTimeMs) return;
            if (!firstPcm.get()) return;
            started = true;
            long late = serverStartTimeMs > 0 ? now - serverStartTimeMs : 0;
            long anchor = startPositionMs;
            // 只在迟到窗口合理时跳播；时钟未同步或迟到过久则从头播，避免 seek 到越界位置
            if (synced && late > 50 && late < 3000) {
                com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                        "[audio] 起播迟到 {}ms，跳到 {}ms session={}", late, startPositionMs + late, id);
                decoder.seek(startPositionMs + late);
                ring.clear();
                stream.reset();
                anchor = startPositionMs + late;
            }
            startAnchorMs = anchor;
            ensureChannel();
        }

        private void ensureChannel() {
            if (channelRequested.compareAndSet(false, true) == false) return;
            ChannelAccess access = channelAccess;
            if (access == null) {
                fail("NO_CHANNEL", "客户端音频通道不可用");
                return;
            }
            long epoch = channelEpoch.get();
            access.createHandle(Library.Pool.STREAMING).thenAccept(created -> {
                if (created == null) {
                    fail("NO_CHANNEL", "声音通道池已满或设备不可用");
                    return;
                }
                if (closed || epoch != channelEpoch.get()) {
                    created.execute(Channel::stop);
                    return;
                }
                handle = created;
                PcmAudioStream currentStream = stream;
                created.execute(channel -> {
                    channel.attachBufferStream(currentStream);
                    channel.setVolume(effectiveVolume());
                    applySpatial(channel);
                    // 用户暂停意图优先：LOADING 暂停后通道只创建不播放，恢复时再起播
                    if (!clock.paused()) {
                        channel.play();
                    }
                    if ((clock.state() == com.mineaudio.client.playback.PlaybackClock.State.LOADING
                            || clock.state() == com.mineaudio.client.playback.PlaybackClock.State.BUFFERING)
                            && pendingSeekTargetMs < 0 && !clock.paused()) {
                        clock.onOutputStarted(startAnchorMs);
                    }
                    com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                            "[audio] 通道已启动 session={} effectiveVolume={} bus={} ring={}B anchor={}ms",
                            id, effectiveVolume(), bus, ring.available(),
                            pendingSeekTargetMs < 0 ? startAnchorMs : pendingSeekTargetMs);
                });
            }).exceptionally(t -> {
                fail("CHANNEL_ERROR", t.toString());
                return null;
            });
        }

        /**
         * 丢弃旧通道与旧 stream，从指定位置重新解码并重建播放通道（seek / 恢复共用）。
         * 解码器拒绝（未加载/不支持）时直接返回失败，不修改上报位置、不触碰通道。
         */
        private com.mineaudio.client.decode.SeekStatus relocate(long requestId, long positionMs) {
            com.mineaudio.client.decode.SeekStatus status;
            synchronized (ioLock) {
                status = decoder.seek(positionMs); // 内部自增代际，旧帧回调作废
                if (status != com.mineaudio.client.decode.SeekStatus.APPLIED) {
                    return status;
                }
                ring.clear();
            }
            pendingSeekTargetMs = positionMs;
            pendingSeekRequestId = requestId;
            pendingSeekDeadlineAt = System.nanoTime() / 1_000_000 + SEEK_FILTER_TIMEOUT_MS;
            draining = false; // seek 取消未完成的 drain 收尾
            clock.onSeekRequested();
            PcmAudioStream oldStream = stream;
            oldStream.markEnded();
            stream = new PcmAudioStream(ring);
            ChannelAccess.ChannelHandle old = handle;
            handle = null;
            channelEpoch.incrementAndGet();
            channelRequested.set(false);
            if (old != null) {
                old.execute(Channel::stop); // 清掉旧通道已排队的旧音频
            }
            if (started && !closed && !clock.paused()) {
                ensureChannel();
            }
            report();
            return com.mineaudio.client.decode.SeekStatus.APPLIED;
        }

        void pump() {
            if (closed) return;
            Runnable action = pendingAction;
            if (action != null && ProtocolClient.get().clock().serverNow() >= pendingActionAt) {
                pendingAction = null;
                action.run();
            }
            if (!started) {
                maybeStart();
            }
            ChannelAccess.ChannelHandle current = handle;
            float effective = effectiveVolume();
            if (current != null && Math.abs(effective - lastAppliedVolume) > 0.005f) {
                lastAppliedVolume = effective;
                current.execute(channel -> channel.setVolume(effective));
            }
            if (current == null) return;
            if (!clock.paused() && draining) {
                // 输出真正吃完的判定：不是“环形缓冲估算还剩多久”，而是
                // 1) 解码器已 EOF（PcmAudioStream 不再补静音，剩余 PCM 已全部交给 OpenAL）
                // 2) OpenAL 把已排队缓冲播完（源 stopped → MC release channel，或声音线程观测到 stopped）
                if (current.isStopped() || drainChannelStopped.get()) {
                    finishNow();
                    return;
                }
                current.execute(channel -> {
                    if (channel.stopped()) {
                        drainChannelStopped.set(true);
                    }
                });
                // 仅作异常 watchdog：正常应由 OpenAL 停止触发；60s 不计暂停时长
                if (System.currentTimeMillis() - drainStartedAt > 60_000L) {
                    com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.warn(
                            "[audio] DRAINING watchDog 超时 session={} ring={}B eof={}",
                            id, ring.available(), stream.eofReached());
                    finishNow();
                }
                return;
            }
            if (clock.paused()) {
                // 兜底：暂停期间通道若被外部引擎（SoundEngine.resume 无条件 unpause）重新起播，立即重 pause。
                // 一律只 pause 不 stop：保留 OpenAL 已排队音频，恢复时 unpause 才能精确续播
                current.execute(channel -> {
                    if (channel.playing()) {
                        channel.pause();
                    }
                });
                return;
            }
            if (clock.ended()) return;
            current.execute(channel -> {
                if (!channel.playing() && clock.playing()) {
                    // 欠载/设备侧停止：冻结时钟，重新起播后从冻结位置继续
                    clock.onUnderrun();
                    channel.updateStream();
                    channel.play();
                    clock.onOutputStarted(clock.positionMs());
                    return;
                }
                channel.updateStream();
                if (!channel.playing()) {
                    channel.play();
                }
            });
        }

        /** 按服务端执行时刻调度操作（校时对齐）。 */
        void schedule(long executeAtServerTime, Runnable action) {
            long now = ProtocolClient.get().clock().serverNow();
            if (executeAtServerTime <= 0 || executeAtServerTime <= now) {
                action.run();
                return;
            }
            pendingActionAt = executeAtServerTime;
            pendingAction = action;
        }

        void setPaused(boolean value) {
            if (clock.paused() == value) return; // 幂等：重复 resume/pause 不重置锚点
            if (value) {
                clock.onPause();
                drainPauseStartMs = System.currentTimeMillis();
                decoder.setPaused(true);
                // 一律只 pause 不 stop：stop 会丢弃 OpenAL 已排队的音频，恢复时无法还原。
                // 关闭界面触发的 SoundEngine.resume() 无条件 unpause 由 pump 兜底重 pause。
                ChannelAccess.ChannelHandle current = handle;
                if (current != null) {
                    current.execute(channel -> {
                        if (channel.playing()) {
                            channel.pause();
                        }
                    });
                }
            } else {
                // 先判定排空状态，避免解码器恢复后状态竞变
                boolean wasDraining = draining && decoder.finished();
                long resumeAt = clock.positionMs();
                ChannelAccess.ChannelHandle current = handle;
                if (current != null) {
                    // 通道全程只是 pause，OpenAL 队列完整保留：unpause 即从暂停点精确续播。
                    // 不再按播放时钟重定位——墙钟会因解码饥饿补静音而缓慢漂移到实际音频之前，
                    // 越到曲末漂移越大，重定位就会跳过尚未听到的一段（尾部暂停恢复跳播的根因）
                    clock.resumeFromOutput(resumeAt);
                    decoder.setPaused(false);
                    current.execute(Channel::unpause);
                    if (wasDraining) {
                        pendingSeekTargetMs = -1;
                    }
                } else {
                    // 通道丢失：只能从冻结位置重新解码。排空中优先重建（保留残余 PCM）
                    clock.onResume();
                    decoder.setPaused(false);
                    if (wasDraining) {
                        pendingSeekTargetMs = -1;
                        channelEpoch.incrementAndGet();
                        channelRequested.set(false);
                        ensureChannel();
                    } else {
                        relocate(0, resumeAt);
                    }
                }
                if (drainPauseStartMs > 0) {
                    if (wasDraining) {
                        // 暂停时长不计入排空计时（否则长暂停后 watchdog 会误判立即结束）
                        drainStartedAt += (System.currentTimeMillis() - drainPauseStartMs);
                    }
                    drainPauseStartMs = 0;
                }
            }
            com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                    "[audio] {} session={} pos={}ms started={}",
                    value ? "PAUSE" : "RESUME", id, decoder.positionMs(), started);
            report();
        }

        com.mineaudio.client.decode.SeekStatus seek(long requestId, long positionMs) {
            com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                    "[audio] SEEK session={} id={} -> {}ms (was {}ms, seekable={})",
                    id, requestId, positionMs, decoder.positionMs(), decoder.seekable());
            com.mineaudio.client.decode.SeekStatus status = relocate(requestId, positionMs);
            if (status != com.mineaudio.client.decode.SeekStatus.APPLIED) {
                com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.warn(
                        "[audio] SEEK 被解码器拒绝 session={} status={}", id, status);
            }
            return status;
        }

        long knownDurationMs() {
            long duration = decoder.durationMs();
            duration = duration > 0 && duration <= MAX_REASONABLE_DURATION_MS ? duration : durationHintMs;
            clock.setDuration(duration);
            return duration;
        }

        boolean isPresentationPlaying() {
            return clock.playing();
        }

        boolean isBuffering() {
            return !clock.paused() && !clock.ended()
                    && (clock.state() == com.mineaudio.client.playback.PlaybackClock.State.LOADING
                    || clock.state() == com.mineaudio.client.playback.PlaybackClock.State.BUFFERING
                    || pendingSeekTargetMs >= 0);
        }

        float currentVolume() {
            return volume;
        }

        boolean hasAudibleContent() {
            return !clock.ended();
        }

        void setVolume(float value) {
            volume = Math.max(0f, Math.min(1f, value));
            ChannelAccess.ChannelHandle current = handle;
            if (current != null) {
                float effective = effectiveVolume();
                current.execute(channel -> channel.setVolume(effective));
            }
        }

        void stop() {
            if (closed) return;
            closed = true;
            decoder.close();
            stream.markEnded();
            unregisterGateway();
            ChannelAccess.ChannelHandle current = handle;
            handle = null;
            if (current != null) {
                // 与原版一致：只 stop，release 由 ChannelAccess.clear()（断线/重载）统一处理，
                // 手动 release 会让 handle 残留在内部集合里导致断线时 "unknown channel" 崩溃
                current.execute(Channel::stop);
            }
        }

        private void applySpatial(Channel channel) {
            Packets.Play.Spatial position = spatial;
            if (position == null) {
                channel.setRelative(true);
                channel.disableAttenuation();
                return;
            }
            channel.setRelative(false);
            channel.setSelfPosition(new Vec3(position.x(), position.y(), position.z()));
            if (position.radius() > 0) {
                channel.linearAttenuation((float) position.radius());
            } else {
                channel.disableAttenuation();
            }
        }

        private float effectiveVolume() {
            float category = Minecraft.getInstance().options.getFinalSoundSourceVolume(category());
            return Math.max(0f, Math.min(1f, volume * category));
        }

        /** Bus → 游戏声音设置档位（总音量由 getFinalSoundSourceVolume 一并计入）。 */
        private SoundSource category() {
            String name = bus == null ? "" : bus.toLowerCase(java.util.Locale.ROOT);
            return switch (name) {
                case "ambient" -> SoundSource.AMBIENT;
                case "sfx" -> SoundSource.BLOCKS;
                case "ui" -> SoundSource.UI;
                default -> SoundSource.MUSIC;
            };
        }

        private void report() {
            if (closed) return;
            if (local) return; // 本地试听不上报服务端
            // 状态只由播放时钟决定（DRAINING 映射为 PLAYING）；
            // 有暂停意图的 LOADING/BUFFERING 对外展示 PAUSED
            String state = clock.stateName();
            if (clock.paused()
                    && (clock.state() == com.mineaudio.client.playback.PlaybackClock.State.LOADING
                    || clock.state() == com.mineaudio.client.playback.PlaybackClock.State.BUFFERING
                    || clock.state() == com.mineaudio.client.playback.PlaybackClock.State.DRAINING)) {
                state = "PAUSED";
            }
            long decoderDuration = decoder.durationMs();
            long duration = decoderDuration > 0 && decoderDuration <= MAX_REASONABLE_DURATION_MS
                    ? decoderDuration : durationHintMs;
            // 上报听感位置（播放时钟），不再用解码位置或环形缓冲推算
            int availableBytes = ring.available();
            long position = clock.positionMs();
            if (duration > 0 && position > duration) {
                position = duration;
            }
            Packets.State.Error error = clock.errorCode() == null
                    ? null : new Packets.State.Error(clock.errorCode(), errorMessage);
            Packets.State snapshot = new Packets.State(
                    ++seq, state, position, duration,
                    availableBytes / BYTES_PER_MS,
                    availableBytes / (double) ring.capacity(),
                    ProtocolClient.get().clock().rttMs(), 0, lastCommandId, error);
            ProtocolClient.get().sendState(id, revision, snapshot);
        }

        private void fail(String code, String message) {
            if (clock.errorCode() == null) {
                errorMessage = message;
                clock.onError(code);
                ProtocolClient.get().sendError(id, revision, code, message);
            }
            report();
        }

        // ---------- AudioDecoder.Sink ----------

        @Override
        public void onPcm(long frameGeneration, byte[] data, int length, long timecodeMs) {
            if (frameGeneration != decoder.generation()) {
                return; // 该帧属于 seek 前的解码任务
            }
            long target = pendingSeekTargetMs;
            if (target >= 0) {
                if (Math.abs(timecodeMs - target) <= SEEK_FILTER_TOLERANCE_MS) {
                    pendingSeekTargetMs = -1;
                    // 音频确实在目标位置：直接锚定目标，避免重连后首帧 timecode 相对化导致进度归零
                    if (!clock.paused()) {
                        clock.onSeekApplied(target);
                        lastCommandId = pendingSeekRequestId;
                        report();
                    }
                } else if (System.nanoTime() / 1_000_000 <= pendingSeekDeadlineAt) {
                    // seek 后 LavaPlayer 内部仍在吐旧位置帧：丢弃，等目标附近的帧
                    return;
                } else {
                    // 超时：不做成功确认（不更新 lastCommandId），只按该帧自身时间戳起锚，
                    // 让 UI 显示实际位置；服务端超时后回滚“定位未生效”
                    pendingSeekTargetMs = -1;
                    if (!clock.paused()) {
                        clock.onSeekApplied(timecodeMs);
                        report();
                    }
                }
            }
            boolean first = firstPcm.compareAndSet(false, true);
            if (first) {
                int peak = 0;
                for (int i = 0; i + 1 < length; i += 2) {
                    int sample = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
                    peak = Math.max(peak, Math.abs(sample));
                }
                com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                        "[audio] 收到首批 PCM session={} bytes={} timecode={}ms peak={} loadMs={}",
                        id, length, timecodeMs, peak, System.currentTimeMillis() - createdAtMs);
            }
            int offset = 0;
            while (offset < length && !closed) {
                int written;
                synchronized (ioLock) {
                    if (frameGeneration != decoder.generation()) {
                        return;
                    }
                    written = ring.write(data, offset, length - offset);
                }
                if (written == 0) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                offset += written;
            }
        }

        @Override
        public void onEnded() {
            // 解码结束但输出缓冲还有音频：先进入 DRAINING，等 OpenAL 真正播完再 FINISHED
            if (clock.ended() || draining) return;
            draining = true;
            drainStartedAt = System.currentTimeMillis();
            drainChannelStopped.set(false);
            // 让 PcmAudioStream 把环形缓冲剩余 PCM 喂完后返回真实 EOF，而不是无限补静音
            stream.markInputEnded();
            clock.onDecoderEnded();
            report();
        }

        /** 输出耗尽后的最终收尾：冻结时钟、释放网关资源、上报 FINISHED。 */
        private void finishNow() {
            if (clock.finished()) return;
            draining = false;
            ChannelAccess.ChannelHandle currentHandle = handle;
            com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                    "[audio] FINISHED session={} pos={}ms ring={}B eof={} channelStopped={} watchdog={}",
                    id, clock.positionMs(), ring.available(), stream.eofReached(),
                    currentHandle != null && (currentHandle.isStopped() || drainChannelStopped.get()),
                    System.currentTimeMillis() - drainStartedAt >= 60_000L);
            clock.onDrained();
            if (current == this) {
                reselectCurrent();
            }
            stream.markEnded();
            unregisterGateway();
            report();
        }

        private void unregisterGateway() {
            String local = localUrl;
            localUrl = null;
            SecureMediaGateway gw = gateway;
            if (local != null && gw != null) {
                gw.unregister(local);
            }
        }

        @Override
        public void onError(String code, String message) {
            stream.markEnded();
            fail(code, message);
        }
    }
}
