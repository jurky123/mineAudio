package com.mineaudio.stream;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackState;
import com.mineaudio.playback.NoopPlaybackHandle;

/**
 * MoeMusic Legacy：通过控制台命令桥驱动 MoeMusic 的共享队列。
 * 能力有限（GLOBAL only、无 per-player、无 seek/volume），仅作为降级路径保留。
 */
public final class MoeMusicLegacyProvider implements StreamProvider {

    private static final String PLUGIN_NAME = "MoeMusic";
    private static final AudioCapabilities CAPABILITIES = new AudioCapabilities(
            false, false, true, false, false, false,
            false, true, false, false, false, false);

    private final MineAudioPlugin plugin;

    public MoeMusicLegacyProvider(MineAudioPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "moemusic";
    }

    @Override
    public boolean available(Player player) {
        Plugin moeMusic = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        return moeMusic != null && moeMusic.isEnabled();
    }

    @Override
    public AudioCapabilities capabilities(Player player) {
        return CAPABILITIES;
    }

    @Override
    public PlaybackHandle play(Player player, StreamPlaybackRequest request) {
        Optional<String> command = playCommand(request.source(),
                plugin.getConfig().getBoolean("stream.http-enabled", false),
                plugin.getConfig().getStringList("stream.allowed-hosts"));
        if (command.isEmpty()) {
            plugin.getLogger().warning("[stream] 已拒绝流媒体曲目（检查 stream.http-enabled / allowed-hosts）："
                    + describe(request.source()));
            return NoopPlaybackHandle.stopped();
        }
        dispatch(command.get());
        return new LegacyHandle();
    }

    /** 构建控制台命令；uri 直链默认禁用并受主机白名单约束，返回空表示拒绝。 */
    static Optional<String> playCommand(AudioSource.Stream source, boolean httpEnabled, List<String> allowedHosts) {
        if (source.uri() != null) {
            if (!httpEnabled) return Optional.empty();
            String uri = source.uri().trim();
            if (!uri.startsWith("http://") && !uri.startsWith("https://")) return Optional.empty();
            if (uri.chars().anyMatch(Character::isWhitespace)) return Optional.empty();
            String host;
            try {
                host = URI.create(uri).getHost();
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            if (host == null) return Optional.empty();
            if (allowedHosts != null && !allowedHosts.isEmpty()) {
                String lowerHost = host.toLowerCase(Locale.ROOT);
                boolean allowed = allowedHosts.stream()
                        .filter(allowedHost -> allowedHost != null && !allowedHost.isBlank())
                        .map(allowedHost -> allowedHost.trim().toLowerCase(Locale.ROOT))
                        .anyMatch(allowedHost -> lowerHost.equals(allowedHost)
                                || lowerHost.endsWith("." + allowedHost));
                if (!allowed) return Optional.empty();
            }
            return Optional.of("music add --now " + uri);
        }
        return Optional.of("music addById " + source.source() + " " + source.id() + " --now");
    }

    private void dispatch(String command) {
        if (plugin.debug()) {
            plugin.getLogger().info("[stream] 执行控制台命令 /" + command);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private static String describe(AudioSource.Stream source) {
        if (source.uri() != null) return source.provider() + " " + source.uri();
        return source.provider() + " " + source.source() + ":" + source.id();
    }

    /** 共享队列句柄：停止/暂停/继续都作用于 MoeMusic 全服队列。 */
    private final class LegacyHandle implements PlaybackHandle {

        private final UUID id = UUID.randomUUID();
        private PlaybackState state = PlaybackState.PLAYING;

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
            if (state != PlaybackState.PLAYING && state != PlaybackState.PAUSED) return false;
            dispatch("music stop");
            state = PlaybackState.STOPPED;
            return true;
        }

        @Override
        public boolean pause() {
            if (state != PlaybackState.PLAYING) return false;
            dispatch("music pause");
            state = PlaybackState.PAUSED;
            return true;
        }

        @Override
        public boolean resume() {
            if (state != PlaybackState.PAUSED) return false;
            dispatch("music resume");
            state = PlaybackState.PLAYING;
            return true;
        }

        @Override
        public boolean seek(Duration position) {
            return false;
        }
    }
}
