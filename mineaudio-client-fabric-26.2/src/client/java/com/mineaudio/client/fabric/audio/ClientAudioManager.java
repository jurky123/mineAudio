package com.mineaudio.client.fabric.audio;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mineaudio.client.ProtocolClient;
import com.mineaudio.client.decode.AudioDecoder;
import com.mineaudio.client.decode.PcmRingBuffer;
import com.mineaudio.protocol.Packets;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;

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

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private ChannelAccess channelAccess;
    private volatile boolean available;

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
        session.start();
    }

    @Override
    public void onStop(String sessionId, Packets.Stop stop) {
        Session session = sessions.remove(sessionId);
        if (session != null) session.stop();
    }

    @Override
    public void onPause(String sessionId, Packets.Pause pause) {
        Session session = sessions.get(sessionId);
        if (session != null) session.setPaused(true);
    }

    @Override
    public void onResume(String sessionId, Packets.Resume resume) {
        Session session = sessions.get(sessionId);
        if (session != null) session.setPaused(false);
    }

    @Override
    public void onSeek(String sessionId, Packets.Seek seek) {
        Session session = sessions.get(sessionId);
        if (session != null) session.seek(seek.positionMs());
    }

    @Override
    public void onVolume(String sessionId, Packets.Volume volume) {
        Session session = sessions.get(sessionId);
        if (session != null) session.setVolume(volume.volume());
    }

    @Override
    public void onHelloAck(Packets.HelloAck ack) {
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

    /** 每个客户端 tick 调用：泵送流缓冲，避免 OpenAL 源 underrun 后不再出声。 */
    public void tick() {
        for (Session session : sessions.values()) {
            session.pump();
        }
    }

    public void closeAll() {
        for (Session session : sessions.values()) {
            session.stop();
        }
        sessions.clear();
    }

    // ---------- 会话 ----------

    private final class Session implements AudioDecoder.Sink {

        private final String id;
        private final int revision;
        private final String url;
        private final long startPositionMs;
        private final long durationHintMs;
        private final Packets.Play.Spatial spatial;
        private final PcmRingBuffer ring = new PcmRingBuffer(RING_BYTES);
        private final PcmAudioStream stream = new PcmAudioStream(ring);
        private final LavaPlayerDecoder decoder = new LavaPlayerDecoder();
        private final java.util.concurrent.atomic.AtomicBoolean firstPcm = new java.util.concurrent.atomic.AtomicBoolean();
        private int ticks;

        private volatile ChannelAccess.ChannelHandle handle;
        private volatile float volume;
        private volatile boolean paused;
        private volatile boolean finished;
        private volatile boolean closed;
        private volatile String errorCode;
        private volatile String errorMessage;
        private volatile int seq;

        Session(String sessionId, Packets.Play play) {
            this.id = sessionId;
            this.revision = play.resourceVersion();
            this.url = play.url();
            this.startPositionMs = play.positionMs();
            this.durationHintMs = play.durationHintMs();
            this.spatial = play.spatial();
            this.volume = Math.max(0f, Math.min(1f, play.volume()));
        }

        void start() {
            com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                    "[audio] PLAY session={} url={} startPos={}ms volume={} spatial={}",
                    id, url, startPositionMs, volume, spatial != null);
            decoder.start(url, startPositionMs, this);
            ChannelAccess access = channelAccess;
            if (access == null) {
                fail("NO_CHANNEL", "客户端音频通道不可用");
                return;
            }
            access.createHandle(Library.Pool.STREAMING).thenAccept(created -> {
                if (created == null) {
                    fail("NO_CHANNEL", "声音通道池已满或设备不可用");
                    return;
                }
                if (closed) {
                    created.release();
                    return;
                }
                handle = created;
                created.execute(channel -> {
                    channel.attachBufferStream(stream);
                    channel.setVolume(effectiveVolume());
                    applySpatial(channel);
                    channel.play();
                    com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                            "[audio] 通道已启动 session={} effectiveVolume={} musicVolume={}",
                            id, effectiveVolume(),
                            Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MUSIC));
                });
            }).exceptionally(t -> {
                fail("CHANNEL_ERROR", t.toString());
                return null;
            });
        }

        void pump() {
            if (closed || paused || finished || errorCode != null) return;
            ChannelAccess.ChannelHandle current = handle;
            if (current == null) return;
            boolean diagnose = ticks++ == 40;
            current.execute(channel -> {
                channel.updateStream();
                if (!channel.playing()) {
                    channel.play();
                }
                if (diagnose) {
                    diagnose(channel);
                }
            });
        }

        private void diagnose(Channel channel) {
            try {
                java.lang.reflect.Field field = Channel.class.getDeclaredField("source");
                field.setAccessible(true);
                int source = field.getInt(channel);
                int state = org.lwjgl.openal.AL10.alGetSourcei(source, org.lwjgl.openal.AL10.AL_SOURCE_STATE);
                int queued = org.lwjgl.openal.AL10.alGetSourcei(source, org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED);
                int processed = org.lwjgl.openal.AL10.alGetSourcei(source, org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED);
                float gain = org.lwjgl.openal.AL10.alGetSourcef(source, org.lwjgl.openal.AL10.AL_GAIN);
                com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                        "[audio] 诊断 session={} source={} state={} queued={} processed={} gain={} ring={}B playing={} stopped={}",
                        id, source, state, queued, processed, gain, ring.available(),
                        channel.playing(), channel.stopped());
            } catch (Throwable t) {
                com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.warn(
                        "[audio] 诊断失败 session={}：{}", id, t.toString());
            }
        }

        void setPaused(boolean value) {
            paused = value;
            decoder.setPaused(value);
            ChannelAccess.ChannelHandle current = handle;
            if (current != null) {
                current.execute(channel -> {
                    if (value) {
                        channel.pause();
                    } else {
                        channel.unpause();
                    }
                });
            }
            report();
        }

        void seek(long positionMs) {
            decoder.seek(positionMs);
            ring.clear();
            stream.reset();
            ChannelAccess.ChannelHandle current = handle;
            if (current != null) {
                current.execute(channel -> {
                    channel.stop();
                    channel.attachBufferStream(stream);
                    channel.setVolume(effectiveVolume());
                    applySpatial(channel);
                    channel.play();
                });
            }
            report();
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
            ChannelAccess.ChannelHandle current = handle;
            handle = null;
            if (current != null) {
                current.execute(channel -> {
                    channel.stop();
                    try {
                        current.release();
                    } catch (Throwable t) {
                        com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.warn(
                                "[audio] 释放通道失败 session={}：{}", id, t.toString());
                    }
                });
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
            float category = Minecraft.getInstance().options.getSoundSourceVolume(category());
            return Math.max(0f, Math.min(1f, volume * category));
        }

        private SoundSource category() {
            return SoundSource.MUSIC;
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
            } else if (decoder.durationMs() > 0 || decoder.positionMs() > 0) {
                state = "PLAYING";
            } else {
                state = "BUFFERING";
            }
            long duration = decoder.durationMs() > 0 ? decoder.durationMs() : durationHintMs;
            int availableBytes = ring.available();
            Packets.State.Error error = errorCode == null
                    ? null : new Packets.State.Error(errorCode, errorMessage);
            Packets.State snapshot = new Packets.State(
                    ++seq, state, decoder.positionMs(), duration,
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
        public void onPcm(byte[] data, int length, long timecodeMs) {
            if (firstPcm.compareAndSet(false, true)) {
                int peak = 0;
                for (int i = 0; i + 1 < length; i += 2) {
                    int sample = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
                    peak = Math.max(peak, Math.abs(sample));
                }
                com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                        "[audio] 收到首批 PCM session={} bytes={} timecode={}ms peak={}", id, length, timecodeMs, peak);
            }
            int offset = 0;
            while (offset < length && !closed) {
                int written = ring.write(data, offset, length - offset);
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
            finished = true;
            stream.markEnded();
            report();
        }

        @Override
        public void onError(String code, String message) {
            stream.markEnded();
            fail(code, message);
        }
    }
}
