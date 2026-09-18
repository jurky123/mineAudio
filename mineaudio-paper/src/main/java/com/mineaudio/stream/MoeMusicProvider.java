package com.mineaudio.stream;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.AudioSource;

/**
 * MoeMusic（Spigot/Paper 服务端插件）命令桥：
 * 服务端队列是共享的，MineAudio 通过控制台命令点播/暂停/停止，音频由装有 MoeMusic 客户端的玩家直连音源。
 * 对应 MoeMusic 的命令：{@code /music addById <source> <trackId> --now} 等。
 */
public final class MoeMusicProvider implements StreamProvider {

    private static final String PLUGIN_NAME = "MoeMusic";
    private static final AudioCapabilities CAPABILITIES =
            new AudioCapabilities(false, false, true, false, false, true, false, false);

    private final MineAudioPlugin plugin;

    public MoeMusicProvider(MineAudioPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "moemusic";
    }

    @Override
    public boolean available() {
        Plugin moeMusic = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        return moeMusic != null && moeMusic.isEnabled();
    }

    @Override
    public AudioCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public void play(AudioSource.Stream source) {
        Optional<String> command = playCommand(source,
                plugin.getConfig().getBoolean("stream.http-enabled", false),
                plugin.getConfig().getStringList("stream.allowed-hosts"));
        if (command.isEmpty()) {
            plugin.getLogger().warning("[stream] 已拒绝流媒体曲目（检查 stream.http-enabled / allowed-hosts）："
                    + describe(source));
            return;
        }
        dispatch(command.get());
    }

    @Override
    public void stop() {
        dispatch("music stop");
    }

    @Override
    public boolean pause() {
        dispatch("music pause");
        return true;
    }

    @Override
    public boolean resume() {
        dispatch("music resume");
        return true;
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
}
