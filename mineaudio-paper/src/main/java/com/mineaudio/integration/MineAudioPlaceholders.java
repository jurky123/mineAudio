package com.mineaudio.integration;

import java.util.Locale;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.client.ClientPlaybackStateCache;
import com.mineaudio.playback.PlaybackSession;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * PlaceholderAPI 扩展：计分板等位置显示当前播放。
 * 流媒体曲目没有服务端歌曲元数据，标题/作者取自 tracks.yml 的 title/author 字段。
 */
public final class MineAudioPlaceholders extends PlaceholderExpansion {

    private static final int MAX_TITLE = 16;

    private final MineAudioPlugin plugin;

    public MineAudioPlaceholders(MineAudioPlugin plugin) {
        this.plugin = plugin;
        register();
    }

    @Override
    public String getIdentifier() {
        return "mineaudio";
    }

    @Override
    public String getAuthor() {
        return "jzk";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || !player.isOnline()) return "";
        Player online = player.getPlayer();
        if (online == null) return "";
        PlaybackSession music = plugin.orchestrator().currentMusic(online);
        ClientPlaybackStateCache.Snapshot client = plugin.clientProtocol() == null
                ? null : plugin.clientProtocol().stateCache().latest(online);
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "playing" -> isPlaying(music, client) ? "yes" : "no";
            case "stream" -> plugin.orchestrator().streamAvailable(online) ? "yes" : "no";
            case "title" -> music != null ? titleOf(music.track()) : "";
            case "artist" -> music != null ? music.track().metadata().author() : "";
            case "nowplaying" -> nowPlaying(music);
            case "state" -> client == null ? "" : client.state();
            case "position" -> client == null ? "" : formatMs(client.displayPositionMs());
            case "duration" -> client == null ? "" : formatMs(client.durationMs());
            case "progress" -> client == null || client.durationMs() <= 0 ? ""
                    : Math.round(100.0 * client.displayPositionMs() / client.durationMs()) + "%";
            default -> null;
        };
    }

    private boolean isPlaying(PlaybackSession music, ClientPlaybackStateCache.Snapshot client) {
        return music != null || client != null;
    }

    private static String formatMs(long ms) {
        if (ms <= 0) return "00:00";
        long totalSeconds = ms / 1000;
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private String nowPlaying(PlaybackSession music) {
        if (music != null) {
            String title = titleOf(music.track());
            String artist = music.track().metadata().author();
            return artist.isBlank() ? "&f" + title : "&f" + title + " &7- &f" + artist;
        }
        return "&8未在播放";
    }

    private static String titleOf(AudioTrack track) {
        String title = track.metadata().title();
        if (title.isBlank()) title = track.id().value();
        return truncate(title);
    }

    private static String truncate(String title) {
        return title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE) + "…" : title;
    }
}
