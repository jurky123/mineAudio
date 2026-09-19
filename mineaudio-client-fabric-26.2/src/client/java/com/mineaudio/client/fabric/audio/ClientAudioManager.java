package com.mineaudio.client.fabric.audio;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mineaudio.client.ProtocolClient;
import com.mineaudio.client.decode.AudioDecoder;
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
    /** 最近一次播放的会话：MineUI 本地绑定（{local.mineaudio.*}）读取的“当前曲目”。 */
    private volatile Session current;
    private ChannelAccess channelAccess;
    private volatile boolean available;
    private MediaFirewall.Policy serverPolicy;
    private MediaFirewall firewall;
    private SecureMediaGateway gateway;
    private boolean gatewayTried;

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
        // 本地控件只针对 MUSIC；环境音/音效不得抢占“当前播放器”
        if (current == null || "MUSIC".equalsIgnoreCase(play.bus())) {
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
                current = sessions.values().stream()
                        .filter(s -> "MUSIC".equalsIgnoreCase(s.bus))
                        .findFirst()
                        .orElseGet(() -> sessions.isEmpty() ? null : sessions.values().iterator().next());
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
        if (session != null) session.schedule(seek.executeAtServerTime(), () -> session.seek(seek.positionMs()));
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

    /** 本地状态键：position / duration / percent / time / playing / buffering / volume / visible。 */
    public Object localState(String key) {
        Session session = current;
        if (session == null) return null;
        long duration = session.knownDurationMs();
        long position = session.presentationPositionMs();
        return switch (key) {
            case "position" -> position;
            case "duration" -> duration;
            case "percent" -> duration > 0
                    ? Math.round(1000.0 * Math.min(position, duration) / duration) / 10.0 : 0.0;
            case "time" -> formatTime(position) + (duration > 0 ? " / " + formatTime(duration) : "");
            case "playing" -> session.isPresentationPlaying();
            case "buffering" -> session.isBuffering();
            case "volume" -> Math.round(session.currentVolume() * 100);
            case "visible" -> duration > 0 && session.hasAudibleContent();
            default -> null;
        };
    }

    /** 本地动作：pause / resume / seek / seek_back / seek_fwd / volume_up / volume_down。 */
    public boolean localAction(String action, Map<String, Object> payload) {
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
                session.seek(Math.round(duration * percent / 100.0));
                return true;
            }
            case "seek_back" -> {
                session.seek(Math.max(0, session.presentationPositionMs() - 15_000));
                return true;
            }
            case "seek_fwd" -> {
                long target = session.presentationPositionMs() + 15_000;
                session.seek(duration > 0 ? Math.min(target, duration) : target);
                return true;
            }
            case "volume_up" -> {
                session.setVolume(session.currentVolume() + 0.1f);
                return true;
            }
            case "volume_down" -> {
                session.setVolume(session.currentVolume() - 0.1f);
                return true;
            }
            default -> {
                return false;
            }
        }
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
        private final Packets.Play.Spatial spatial;
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
        private volatile long drainDeadlineAt;
        private static final long SEEK_FILTER_TOLERANCE_MS = 1500;
        private static final long SEEK_FILTER_TIMEOUT_MS = 3000;

        private volatile ChannelAccess.ChannelHandle handle;
        private volatile String localUrl;
        private volatile float volume;
        private volatile float lastAppliedVolume = -1f;
        private volatile boolean paused;
        private volatile boolean started;
        private volatile boolean finished;
        private volatile boolean closed;
        private volatile String errorCode;
        private volatile String errorMessage;
        private volatile int seq;
        private volatile long pendingActionAt;
        private volatile Runnable pendingAction;
        private volatile long pendingSeekTargetMs = -1;
        private volatile long pendingSeekDeadlineAt;
        // 播放时钟（呈现位置）：以真实起播/seek/恢复时刻为锚点，按本地单调时钟推进；
        // 暂停/缓冲时冻结。上报它才与耳朵听到的位置一致（解码位置会领先环形缓冲+OpenAL 队列）。
        private volatile long presentAnchorMs;
        private volatile long presentAnchorAtNanos;
        private volatile boolean presentRunning;
        /** 起播时真正会先被听到的媒体位置（startPosition，或迟到跳播后的位置）。 */
        private volatile long startAnchorMs;
        /** 是否已做过首次起播锚定；seek/恢复后的锚定由 onPcm 首帧负责，通道启动不得覆盖。 */
        private volatile boolean playbackAnchored;
        private final long createdAtMs = System.currentTimeMillis();

        Session(String sessionId, Packets.Play play) {
            this.id = sessionId;
            this.revision = play.resourceVersion();
            this.url = play.url();
            this.cacheKey = buildCacheKey(play);
            this.startPositionMs = play.positionMs();
            this.serverStartTimeMs = play.serverStartTime();
            this.bus = play.bus();
            this.durationHintMs = play.durationHintMs();
            this.spatial = play.spatial();
            this.volume = Math.max(0f, Math.min(1f, play.volume()));
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
            return !closed && !finished && errorCode == null && "MUSIC".equalsIgnoreCase(bus);
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
                    channel.play();
                    // OpenAL 起播会立刻预取缓冲，不能再用“解码位置-环形缓冲”推算；
                    // 只在“首次起播”时用 startAnchorMs 锚定；seek/恢复后的锚点由 onPcm 首帧负责，避免被覆盖成 0
                    if (!playbackAnchored && pendingSeekTargetMs < 0) {
                        anchorPresentation(startAnchorMs);
                        playbackAnchored = true;
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

        /** 丢弃旧通道与旧 stream，从指定位置重新解码并重建播放通道（seek / 恢复共用）。 */
        private void relocate(long positionMs) {
            pendingSeekTargetMs = positionMs;
            pendingSeekDeadlineAt = System.nanoTime() / 1_000_000 + SEEK_FILTER_TIMEOUT_MS;
            presentAnchorMs = Math.max(0, positionMs);
            presentRunning = false;
            PcmAudioStream oldStream = stream;
            oldStream.markEnded();
            synchronized (ioLock) {
                decoder.seek(positionMs); // 内部自增代际，旧帧回调作废
                ring.clear();
            }
            stream = new PcmAudioStream(ring);
            ChannelAccess.ChannelHandle old = handle;
            handle = null;
            channelEpoch.incrementAndGet();
            channelRequested.set(false);
            if (old != null) {
                old.execute(Channel::stop); // 清掉旧通道已排队的旧音频
            }
            if (started && !closed && !paused) {
                ensureChannel();
            }
            report();
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
            if (!paused && draining && System.currentTimeMillis() >= drainDeadlineAt) {
                finishNow();
                return;
            }
            if (paused) {
                // 兜底：若通道仍处于播放状态（例如外部引擎重建），立即停止
                current.execute(channel -> {
                    if (channel.playing()) {
                        channel.stop();
                    }
                });
                return;
            }
            if (finished || errorCode != null) return;
            current.execute(channel -> {
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
            if (paused == value) return; // 幂等：重复 resume/pause 不重置锚点
            if (value) {
                freezePresentation();
                paused = true;
                decoder.setPaused(true);
                // 直接 stop 而不是 pause：关闭界面会触发 SoundEngine.resume() 无条件 unpause；
                // STOPPED 通道不受影响，彻底消除“暂停中开关 UI 瞬响”
                ChannelAccess.ChannelHandle current = handle;
                if (current != null) {
                    current.execute(Channel::stop);
                }
            } else {
                paused = false;
                decoder.setPaused(false);
                // 从冻结位置精确重定位（重连一次，换取不跳帧、无残留队列）
                relocate(presentAnchorMs);
            }
            com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                    "[audio] {} session={} pos={}ms started={}",
                    value ? "PAUSE" : "RESUME", id, decoder.positionMs(), started);
            report();
        }

        void seek(long positionMs) {
            com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                    "[audio] SEEK session={} -> {}ms (was {}ms, seekable={})",
                    id, positionMs, decoder.positionMs(), decoder.seekable());
            relocate(positionMs);
        }

        /** 播放时钟当前位置（毫秒）；未起播时返回起始位置/0。 */
        private long presentationPositionMs() {
            long anchor = presentAnchorMs;
            if (!presentRunning) {
                return Math.max(0, anchor);
            }
            long elapsed = (System.nanoTime() - presentAnchorAtNanos) / 1_000_000;
            long position = anchor + Math.max(0, elapsed);
            long duration = decoder.durationMs();
            if (duration > 0 && position > duration) {
                position = duration;
            }
            return Math.max(0, position);
        }

        private void anchorPresentation(long mediaPositionMs) {
            presentAnchorMs = Math.max(0, mediaPositionMs);
            presentAnchorAtNanos = System.nanoTime();
            presentRunning = true;
        }

        private void freezePresentation() {
            long current = presentationPositionMs();
            presentAnchorMs = current;
            presentRunning = false;
        }

        long knownDurationMs() {
            long duration = decoder.durationMs();
            return duration > 0 && duration <= MAX_REASONABLE_DURATION_MS ? duration : durationHintMs;
        }

        boolean isPresentationPlaying() {
            return started && !paused && !finished && errorCode == null;
        }

        boolean isBuffering() {
            return !paused && !finished && errorCode == null && (!started || pendingSeekTargetMs >= 0);
        }

        float currentVolume() {
            return volume;
        }

        boolean hasAudibleContent() {
            return errorCode == null && !finished;
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
            String state;
            if (errorCode != null) {
                state = "ERROR";
            } else if (finished) {
                state = "FINISHED";
            } else if (paused) {
                state = "PAUSED";
            } else if (draining) {
                state = "PLAYING"; // 解码已结束，输出缓冲仍在播放
            } else if (!started) {
                state = "BUFFERING";
            } else if (decoder.durationMs() > 0 || decoder.positionMs() > 0) {
                state = "PLAYING";
            } else {
                state = "BUFFERING";
            }
            long decoderDuration = decoder.durationMs();
            long duration = decoderDuration > 0 && decoderDuration <= MAX_REASONABLE_DURATION_MS
                    ? decoderDuration : durationHintMs;
            // 上报听感位置（播放时钟），不再用解码位置或环形缓冲推算
            int availableBytes = ring.available();
            long position = presentationPositionMs();
            if (duration > 0 && position > duration) {
                position = duration;
            }
            Packets.State.Error error = errorCode == null
                    ? null : new Packets.State.Error(errorCode, errorMessage);
            Packets.State snapshot = new Packets.State(
                    ++seq, state, position, duration,
                    availableBytes / BYTES_PER_MS,
                    availableBytes / (double) ring.capacity(),
                    ProtocolClient.get().clock().rttMs(), 0, error);
            ProtocolClient.get().sendState(id, revision, snapshot);
        }

        private void fail(String code, String message) {
            if (errorCode == null) {
                errorCode = code;
                errorMessage = message;
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
                    if (!paused) {
                        anchorPresentation(target);
                        playbackAnchored = true;
                        report();
                    }
                } else if (System.nanoTime() / 1_000_000 <= pendingSeekDeadlineAt) {
                    // seek 后 LavaPlayer 内部仍在吐旧位置帧：丢弃，等目标附近的帧
                    return;
                } else {
                    pendingSeekTargetMs = -1;
                    if (!paused) {
                        anchorPresentation(target);
                        playbackAnchored = true;
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
            // 解码结束但输出缓冲还有音频：先进入 DRAINING，等播放时钟走完再 FINISHED
            if (finished || draining) return;
            draining = true;
            drainDeadlineAt = System.currentTimeMillis() + ring.available() / BYTES_PER_MS + 800;
            report();
        }

        /** 输出耗尽后的最终收尾：冻结时钟、释放网关资源、上报 FINISHED。 */
        private void finishNow() {
            if (finished) return;
            draining = false;
            freezePresentation();
            finished = true;
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
