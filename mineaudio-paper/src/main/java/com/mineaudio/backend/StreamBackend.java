package com.mineaudio.backend;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackOptions;
import com.mineaudio.api.PlaybackState;
import com.mineaudio.playback.NoopPlaybackHandle;
import com.mineaudio.stream.StreamProvider;

import net.kyori.adventure.key.Key;

/**
 * 流媒体 Backend：委托 StreamProvider（V1 为 MoeMusic 命令桥）。
 * 服务端队列为全服共享，因此按曲目去重，同一曲目的多个玩家会话复用同一个句柄。
 */
public final class StreamBackend implements AudioBackend {

    private final Map<String, StreamProvider> providers = new LinkedHashMap<>();
    private final Map<Key, StreamHandle> active = new LinkedHashMap<>();

    public void register(StreamProvider provider) {
        providers.put(provider.id(), provider);
    }

    @Override
    public String id() {
        return "stream";
    }

    @Override
    public boolean supports(AudioSource source) {
        return source instanceof AudioSource.Stream stream && providers.containsKey(stream.provider());
    }

    @Override
    public boolean available() {
        return providers.values().stream().anyMatch(StreamProvider::available);
    }

    @Override
    public AudioCapabilities capabilities() {
        boolean seek = false;
        boolean pause = false;
        boolean loop = false;
        boolean positional = false;
        boolean sync = false;
        boolean multi = false;
        boolean lyrics = false;
        for (StreamProvider provider : providers.values()) {
            if (!provider.available()) continue;
            AudioCapabilities caps = provider.capabilities();
            seek |= caps.seek();
            pause |= caps.pause();
            loop |= caps.loop();
            positional |= caps.positional();
            sync |= caps.synchronizedPlayback();
            multi |= caps.multiSession();
            lyrics |= caps.lyrics();
        }
        return new AudioCapabilities(false, seek, pause, loop, positional, sync, multi, lyrics);
    }

    @Override
    public PlaybackHandle play(Player player, AudioTrack track, AudioSource source, PlaybackOptions options) {
        if (!(source instanceof AudioSource.Stream stream)) return NoopPlaybackHandle.stopped();
        StreamProvider provider = providers.get(stream.provider());
        if (provider == null || !provider.available()) return NoopPlaybackHandle.stopped();
        StreamHandle existing = active.get(track.id());
        if (existing != null && existing.isLive()) {
            return existing;
        }
        provider.play(stream);
        StreamHandle handle = new StreamHandle(track.id(), provider);
        active.put(track.id(), handle);
        return handle;
    }

    @Override
    public PlaybackHandle playAt(Location location, AudioTrack track, AudioSource source, PlaybackOptions options,
                                 double radius) {
        return NoopPlaybackHandle.unsupported();
    }

    /** 共享队列句柄：停止只影响当前仍活跃的曲目，避免替换时误停新曲。 */
    private final class StreamHandle implements PlaybackHandle {

        private final UUID id = UUID.randomUUID();
        private final Key trackId;
        private final StreamProvider provider;
        private PlaybackState state = PlaybackState.PLAYING;

        private StreamHandle(Key trackId, StreamProvider provider) {
            this.trackId = trackId;
            this.provider = provider;
        }

        private boolean isLive() {
            return state == PlaybackState.PLAYING || state == PlaybackState.PAUSED;
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public PlaybackState state() {
            return state;
        }

        @Override
        public boolean stop() {
            if (!isLive()) return false;
            state = PlaybackState.STOPPED;
            if (active.get(trackId) == this) {
                active.remove(trackId);
                provider.stop();
            }
            return true;
        }

        @Override
        public boolean pause() {
            if (state != PlaybackState.PLAYING) return false;
            if (!provider.pause()) return false;
            state = PlaybackState.PAUSED;
            return true;
        }

        @Override
        public boolean resume() {
            if (state != PlaybackState.PAUSED) return false;
            if (!provider.resume()) return false;
            state = PlaybackState.PLAYING;
            return true;
        }

        @Override
        public boolean seek(Duration position) {
            return false;
        }
    }

    // 便于 /mineaudio debug 展示
    public List<String> describeProviders() {
        return providers.values().stream()
                .map(provider -> provider.id() + (provider.available() ? " (可用)" : " (不可用)"))
                .toList();
    }
}
