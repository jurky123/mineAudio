package com.mineaudio.backend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackOptions;
import com.mineaudio.playback.NoopPlaybackHandle;
import com.mineaudio.stream.StreamPlaybackRequest;
import com.mineaudio.stream.StreamProvider;

/**
 * 流媒体 Backend：按玩家能力与配置优先级选择 StreamProvider。
 * 每个玩家一次播放 = 一个独立 session/handle（不再按 trackId 全服复用）。
 */
public final class StreamBackend implements AudioBackend {

    private final MineAudioPlugin plugin;
    private final Map<String, StreamProvider> providers = new LinkedHashMap<>();

    public StreamBackend(MineAudioPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(StreamProvider provider) {
        providers.put(provider.id(), provider);
    }

    @Override
    public String id() {
        return "stream";
    }

    @Override
    public boolean supports(AudioSource source) {
        return source instanceof AudioSource.Stream;
    }

    @Override
    public boolean available() {
        return providers.values().stream().anyMatch(provider -> provider.available(null));
    }

    @Override
    public AudioCapabilities capabilities() {
        boolean seek = false;
        boolean pause = false;
        boolean volume = false;
        boolean fade = false;
        boolean loop = false;
        boolean positional = false;
        boolean sync = false;
        boolean perPlayer = false;
        boolean multi = false;
        boolean cache = false;
        boolean lyrics = false;
        for (StreamProvider provider : providers.values()) {
            if (!provider.available(null)) continue;
            AudioCapabilities caps = provider.capabilities(null);
            seek |= caps.seek();
            pause |= caps.pause();
            volume |= caps.volume();
            fade |= caps.fade();
            loop |= caps.loop();
            positional |= caps.positional();
            sync |= caps.synchronizedPlayback();
            perPlayer |= caps.perPlayer();
            multi |= caps.multiSession();
            cache |= caps.cache();
            lyrics |= caps.lyrics();
        }
        return new AudioCapabilities(false, seek, pause, volume, fade, loop,
                positional, sync, perPlayer, multi, cache, lyrics);
    }

    @Override
    public PlaybackHandle play(Player player, AudioTrack track, AudioSource source, PlaybackOptions options) {
        if (!(source instanceof AudioSource.Stream stream)) return NoopPlaybackHandle.stopped();
        StreamProvider provider = select(player, stream);
        if (provider == null) return NoopPlaybackHandle.stopped();
        long leadMs = Math.max(0, plugin.getConfig().getLong("stream-client.sync.initial-lead-ms", 1200));
        long startTime = System.nanoTime() / 1_000_000 + leadMs;
        StreamPlaybackRequest request = new StreamPlaybackRequest(
                UUID.randomUUID(), track, stream, options,
                new StreamPlaybackRequest.StreamTiming(startTime, 0, 1));
        return provider.play(player, request);
    }

    @Override
    public PlaybackHandle playAt(Location location, AudioTrack track, AudioSource source, PlaybackOptions options,
                                 double radius) {
        return NoopPlaybackHandle.unsupported();
    }

    /** 选择 Provider：配置优先级 → 曲目指定 → 任意可用。 */
    private StreamProvider select(Player player, AudioSource.Stream stream) {
        for (String id : plugin.getConfig().getStringList("stream.provider-priority")) {
            StreamProvider provider = providers.get(id);
            if (provider != null && provider.available(player)) return provider;
        }
        StreamProvider bySource = providers.get(stream.provider());
        if (bySource != null && bySource.available(player)) return bySource;
        for (StreamProvider provider : providers.values()) {
            if (provider.available(player)) return provider;
        }
        return null;
    }

    // 便于 /mineaudio debug 展示
    public List<String> describeProviders() {
        return providers.values().stream()
                .map(provider -> provider.id() + (provider.available(null) ? " (可用)" : " (不可用)"))
                .toList();
    }
}
