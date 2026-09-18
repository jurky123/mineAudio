package com.mineaudio.integration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.Audience;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackState;
import com.mineaudio.playback.PlaybackSession;
import com.mineaudio.ui.AudioUi;
import com.mineui.api.MineUi;
import com.mineui.api.MineUiProvider;
import com.mineui.api.MineUiSession;

import net.kyori.adventure.key.Key;

/**
 * MineAudio 播放界面（MineUI 声明式 JSON）。
 * 服务端权威状态：打开时下发 snapshot，之后每秒与每次操作后推送增量。
 */
public final class MineUiIntegration implements AudioUi {

    private static final String APP = "mineaudio";
    private static final int TRACK_SLOTS = 6;
    private static final int AMBIENT_SLOTS = 3;

    private final MineAudioPlugin plugin;
    private final MineUi api;
    private final JsonObject page;
    private final Map<UUID, MineUiSession> sessions = new HashMap<>();
    private final Map<UUID, BukkitTask> refreshers = new HashMap<>();
    private final Map<UUID, List<Key>> trackOrder = new HashMap<>();
    private boolean broken;

    public MineUiIntegration(MineAudioPlugin plugin) {
        this.plugin = plugin;
        this.api = MineUiProvider.get();
        this.page = load("player");
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                close(event.getPlayer());
            }
        }, plugin);
    }

    @Override
    public boolean available() {
        return !broken && api != null && page != null;
    }

    @Override
    public boolean open(Player player) {
        if (!available()) return false;
        try {
            if (!api.hasClient(player) || !api.supportsServerUi(player)) return false;
            close(player);
            MineUiSession session = api.open(plugin, player, APP, "player", page);
            sessions.put(player.getUniqueId(), session);
            session.on("close", action -> close(player));
            session.on("refresh", action -> push(player, session));
            session.on("pause", action -> {
                if (!plugin.orchestrator().pauseMusic(player)) {
                    session.state("note", "当前 Backend 不支持暂停（资源包/原版声音只能停止）");
                } else {
                    session.state("note", "已暂停");
                }
                push(player, session);
            });
            session.on("resume", action -> {
                if (!plugin.orchestrator().resumeMusic(player)) {
                    session.state("note", "当前没有可继续的会话");
                } else {
                    session.state("note", "已继续");
                }
                push(player, session);
            });
            session.on("stop", action -> {
                plugin.orchestrator().stop(Audience.player(player), AudioBus.MUSIC);
                session.state("note", "已停止音乐");
                push(player, session);
            });
            session.on("stop_ambient", action -> {
                plugin.orchestrator().stop(Audience.player(player), AudioBus.AMBIENT);
                session.state("note", "已停止环境音");
                push(player, session);
            });
            for (int i = 0; i < TRACK_SLOTS; i++) {
                final int index = i;
                session.on("play_self" + i, action -> playTrack(player, index, false));
                session.on("play_global" + i, action -> playTrack(player, index, true));
            }
            push(player, session);
            session.state("note", "");
            session.snapshot();
            startRefresher(player);
            plugin.getLogger().info("[ui] 已向 " + player.getName() + " 下发音乐界面");
            return true;
        } catch (Throwable t) {
            broken = true;
            plugin.getLogger().warning("MineUI 界面不可用，退回聊天状态：" + t);
            return false;
        }
    }

    @Override
    public void close(Player player) {
        UUID playerId = player.getUniqueId();
        stopRefresher(playerId);
        trackOrder.remove(playerId);
        MineUiSession session = sessions.remove(playerId);
        if (session != null && !session.closed()) {
            session.close();
        }
    }

    @Override
    public void shutdown() {
        for (UUID playerId : List.copyOf(sessions.keySet())) {
            stopRefresher(playerId);
            MineUiSession session = sessions.remove(playerId);
            if (session != null && !session.closed()) {
                session.close();
            }
        }
        trackOrder.clear();
    }

    // ---------- 状态推送 ----------

    private void push(Player player, MineUiSession session) {
        PlaybackSession music = plugin.orchestrator().currentMusic(player);
        if (music == null) {
            session.state("title", "暂无音乐");
            session.state("subtitle", "在下方列表点播，或使用 /mineaudio play");
            session.state("state", "IDLE");
            session.state("backend", "-");
            session.state("origin", "-");
        } else {
            AudioTrack track = music.track();
            session.state("title", track.metadata().title().isBlank()
                    ? track.id().asString() : track.metadata().title());
            session.state("subtitle", track.metadata().author().isBlank()
                    ? track.id().asString() : track.metadata().author());
            session.state("state", music.handle().state().name());
            session.state("backend", music.backend() == null ? "-" : music.backend());
            session.state("origin", music.origin().name());
        }
        session.state("stream", plugin.orchestrator().streamAvailable(player)
                ? "流媒体客户端：已连接" : "流媒体客户端：未安装（流媒体将走 fallback）");

        List<AudioTrack> tracks = new ArrayList<>(plugin.trackRegistry().all());
        trackOrder.put(player.getUniqueId(), tracks.stream().map(AudioTrack::id).toList());
        for (int i = 0; i < TRACK_SLOTS; i++) {
            boolean visible = i < tracks.size();
            session.state("t" + i + "_visible", visible);
            if (!visible) continue;
            AudioTrack track = tracks.get(i);
            session.state("t" + i + "_name", track.metadata().title().isBlank()
                    ? track.id().asString() : track.metadata().title());
            session.state("t" + i + "_bus", track.bus().name());
            session.state("t" + i + "_src", sourceName(track));
        }

        List<PlaybackSession> ambient = plugin.orchestrator().sessions(player).stream()
                .filter(playing -> playing.track().bus() == AudioBus.AMBIENT)
                .toList();
        for (int i = 0; i < AMBIENT_SLOTS; i++) {
            boolean visible = i < ambient.size();
            session.state("a" + i + "_visible", visible);
            if (visible) {
                session.state("a" + i + "_name", ambient.get(i).track().id().asString());
            }
        }
    }

    private void playTrack(Player player, int index, boolean global) {
        MineUiSession session = sessions.get(player.getUniqueId());
        List<Key> ids = trackOrder.get(player.getUniqueId());
        if (session == null || ids == null || index >= ids.size()) return;
        Key key = ids.get(index);
        PlaybackHandle handle = plugin.orchestrator().play(
                global ? Audience.global() : Audience.player(player), key);
        if (handle.state() == PlaybackState.STOPPED) {
            session.state("note", "无法播放：流媒体只支持“全服”，且需要客户端或 fallback");
        } else {
            session.state("note", "已提交播放：" + key.asString() + (global ? "（全服）" : ""));
        }
        push(player, session);
    }

    private void startRefresher(Player player) {
        UUID playerId = player.getUniqueId();
        stopRefresher(playerId);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            MineUiSession session = sessions.get(playerId);
            if (session == null || session.closed() || !player.isOnline()) {
                close(player);
                return;
            }
            push(player, session);
        }, 20L, 20L);
        refreshers.put(playerId, task);
    }

    private void stopRefresher(UUID playerId) {
        BukkitTask task = refreshers.remove(playerId);
        if (task != null) task.cancel();
    }

    private JsonObject load(String view) {
        String resource = "assets/mineaudio/ui/mineaudio/" + view + ".json";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return null;
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            plugin.getLogger().warning("读取 MineUI 界面定义失败 " + resource + "：" + e);
            return null;
        }
    }

    private static String sourceName(AudioTrack track) {
        return switch (track.primary()) {
            case com.mineaudio.api.AudioSource.PackSound ignored -> "PACK";
            case com.mineaudio.api.AudioSource.VanillaSound ignored -> "VANILLA";
            case com.mineaudio.api.AudioSource.Nbs ignored -> "NBS";
            case com.mineaudio.api.AudioSource.Stream ignored -> "STREAM";
        };
    }
}
