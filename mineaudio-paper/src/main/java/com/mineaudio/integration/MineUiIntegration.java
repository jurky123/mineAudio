package com.mineaudio.integration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.Audience;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackState;
import com.mineaudio.client.ClientPlaybackStateCache;
import com.mineaudio.playback.PlaybackSession;
import com.mineaudio.playback.StatusAware;
import com.mineaudio.ui.AudioUi;
import com.mineui.api.MineUi;
import com.mineui.api.MineUiProvider;
import com.mineui.api.MineUiSession;
import com.mineui.protocol.msg.HudLayout;
import com.mineui.protocol.msg.Toast;

import net.kyori.adventure.key.Key;

/**
 * MineAudio 播放界面（MineUI 声明式 JSON）。
 * 服务端权威状态：打开时下发 snapshot，之后每秒与每次操作后推送增量。
 * 另提供“正在播放”HUD（可切换）与 1/2 槽键位（打开界面 / 切换 HUD）。
 */
public final class MineUiIntegration implements AudioUi {

    private static final String APP = "mineaudio";
    private static final int TRACK_SLOTS = 6;
    private static final int AMBIENT_SLOTS = 3;
    private static final long SEEK_STEP_MS = 15_000L;

    private final MineAudioPlugin plugin;
    private final MineUi api;
    private final JsonObject page;
    private final JsonObject hudPage;
    private final Map<UUID, MineUiSession> sessions = new HashMap<>();
    private final Map<UUID, MineUiSession> hudSessions = new HashMap<>();
    private final Map<UUID, BukkitTask> refreshers = new HashMap<>();
    private final Map<UUID, BukkitTask> hudRefreshers = new HashMap<>();
    private final Map<UUID, List<Key>> trackOrder = new HashMap<>();
    private final Map<UUID, Float> volumes = new HashMap<>();
    private final Map<UUID, String> lastStatus = new HashMap<>();
    private boolean broken;

    public MineUiIntegration(MineAudioPlugin plugin) {
        this.plugin = plugin;
        this.api = MineUiProvider.get();
        this.page = load("player");
        this.hudPage = load("hud");
        if (api != null) {
            api.onAction(plugin, "open_ui", action -> open(action.player()));
            api.onAction(plugin, "toggle_hud", action -> toggleHud(action.player()));
        }
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent event) {
                declareKeybinds(event.getPlayer());
            }

            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                quit(event.getPlayer());
            }
        }, plugin);
    }

    @Override
    public boolean available() {
        return !broken && api != null && page != null;
    }

    @Override
    public boolean hudSupported(Player player) {
        return available() && hudPage != null && api.supportsHud(player);
    }

    @Override
    public boolean toggleHud(Player player) {
        if (!hudSupported(player)) return false;
        UUID playerId = player.getUniqueId();
        MineUiSession existing = hudSessions.remove(playerId);
        if (existing != null) {
            stopHudRefresher(playerId);
            if (!existing.closed()) existing.close();
            plugin.getLogger().info("[ui] " + player.getName() + " HUD 已关闭");
            return false;
        }
        MineUiSession hud = api.openHud(plugin, player, APP, "hud", hudPage,
                new HudLayout("top_right", 6f, 6f, 1f));
        hudSessions.put(playerId, hud);
        pushHud(player);
        hud.snapshot();
        startHudRefresher(player);
        plugin.getLogger().info("[ui] " + player.getName() + " HUD 已开启");
        return true;
    }

    @Override
    public boolean open(Player player) {
        if (!available()) return false;
        try {
            if (!api.hasClient(player) || !api.supportsServerUi(player)) return false;
            close(player);
            MineUiSession session = api.open(plugin, player, APP, "player", page);
            sessions.put(player.getUniqueId(), session);
            session.onClose(() -> sessions.remove(player.getUniqueId()));
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
            session.on("seek_back", action -> seekBy(player, -SEEK_STEP_MS, session));
            session.on("seek_fwd", action -> seekBy(player, SEEK_STEP_MS, session));
            session.on("seek_to", action -> seekTo(player, action.number("value", -1), session));
            session.on("vol_down", action -> adjustVolume(player, -0.1f, session));
            session.on("vol_up", action -> adjustVolume(player, 0.1f, session));
            session.on("toggle_hud", action -> {
                session.state("note", toggleHud(player) ? "已开启 HUD（关闭界面后可见）" : "已关闭 HUD");
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
            declareKeybinds(player);
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

    private void quit(Player player) {
        close(player);
        UUID playerId = player.getUniqueId();
        stopHudRefresher(playerId);
        MineUiSession hud = hudSessions.remove(playerId);
        if (hud != null && !hud.closed()) {
            hud.close();
        }
        volumes.remove(playerId);
        lastStatus.remove(playerId);
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
        for (UUID playerId : List.copyOf(hudSessions.keySet())) {
            stopHudRefresher(playerId);
            MineUiSession hud = hudSessions.remove(playerId);
            if (hud != null && !hud.closed()) {
                hud.close();
            }
        }
        trackOrder.clear();
        volumes.clear();
        lastStatus.clear();
    }

    // ---------- 状态推送 ----------

    private void push(Player player, MineUiSession session) {
        PlaybackSession music = plugin.orchestrator().currentMusic(player);
        ClientPlaybackStateCache.Snapshot progress = music == null ? null : snapshotOf(player, music);
        String status = "";
        if (music == null) {
            lastStatus.remove(player.getUniqueId());
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
            status = statusNote(music.handle());
            notifyStatus(player, status);
        }
        session.state("status", status);
        session.state("status_visible", !status.isBlank());
        pushProgress(session, progress);
        session.state("volume", Math.round(volumeOf(player) * 100) + "%");
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
        pushHud(player);
    }

    private void pushProgress(MineUiSession session, ClientPlaybackStateCache.Snapshot progress) {
        boolean hasProgress = progress != null && progress.durationMs() > 0;
        session.state("progress_visible", hasProgress);
        if (!hasProgress) {
            session.state("percent", 0);
            session.state("playing", false);
            session.state("time", "");
            return;
        }
        long position = Math.min(Math.max(0, progress.displayPositionMs()), progress.durationMs());
        session.state("percent", Math.round(1000.0 * position / progress.durationMs()) / 10.0);
        session.state("playing", progress.playing());
        session.state("time", formatMs(position) + " / " + formatMs(progress.durationMs()));
    }

    private void pushHud(Player player) {
        MineUiSession hud = hudSessions.get(player.getUniqueId());
        if (hud == null || hud.closed()) return;
        PlaybackSession music = plugin.orchestrator().currentMusic(player);
        ClientPlaybackStateCache.Snapshot progress = music == null ? null : snapshotOf(player, music);
        if (music != null) {
            hud.state("active", true);
            AudioTrack track = music.track();
            hud.state("title", track.metadata().title().isBlank()
                    ? track.id().asString() : track.metadata().title());
            hud.state("subtitle", track.metadata().author().isBlank()
                    ? track.id().asString() : track.metadata().author());
            hud.state("state", music.handle().state().name());
            String status = statusNote(music.handle());
            hud.state("status", status);
            hud.state("status_visible", !status.isBlank());
        } else {
            hud.state("active", false);
            hud.state("title", "未在播放");
            hud.state("subtitle", "");
            hud.state("state", "IDLE");
            hud.state("status", "");
            hud.state("status_visible", false);
        }
        pushProgress(hud, progress);
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
            toast(player, "正在播放：" + key.asString());
        }
        push(player, session);
    }

    private void seekBy(Player player, long deltaMs, MineUiSession session) {
        PlaybackSession music = plugin.orchestrator().currentMusic(player);
        if (music == null) {
            session.state("note", "当前没有可定位的音乐");
            return;
        }
        ClientPlaybackStateCache.Snapshot progress = snapshotOf(player, music);
        long current = progress == null ? 0 : progress.displayPositionMs();
        long target = Math.max(0, current + deltaMs);
        if (progress != null && progress.durationMs() > 0) {
            target = Math.min(target, progress.durationMs());
        }
        boolean ok = music.handle().seek(Duration.ofMillis(target));
        plugin.getLogger().info("[ui] " + player.getName() + " seek "
                + (deltaMs >= 0 ? "+" : "") + deltaMs + "ms -> " + target + "ms ok=" + ok);
        session.state("note", ok ? "已定位到 " + formatMs(target) : "当前 Backend 不支持定位");
        push(player, session);
    }

    /** 进度条拖动提交（payload value 为 0-100 百分比）。 */
    private void seekTo(Player player, double percent, MineUiSession session) {
        PlaybackSession music = plugin.orchestrator().currentMusic(player);
        if (music == null) {
            session.state("note", "当前没有可定位的音乐");
            return;
        }
        ClientPlaybackStateCache.Snapshot progress = snapshotOf(player, music);
        if (progress == null || progress.durationMs() <= 0) {
            session.state("note", "当前没有可定位的进度（等客户端上报后可用）");
            return;
        }
        if (percent < 0) return;
        long target = Math.round(progress.durationMs() * Math.min(100.0, percent) / 100.0);
        boolean ok = music.handle().seek(Duration.ofMillis(target));
        plugin.getLogger().info("[ui] " + player.getName() + " seek -> " + target + "ms ok=" + ok);
        session.state("note", ok ? "已定位到 " + formatMs(target) : "当前 Backend 不支持定位");
        push(player, session);
    }

    private void adjustVolume(Player player, float delta, MineUiSession session) {
        PlaybackSession music = plugin.orchestrator().currentMusic(player);
        if (music == null) {
            session.state("note", "当前没有可调音量的音乐");
            return;
        }
        float volume = Math.max(0f, Math.min(1f, volumeOf(player) + delta));
        boolean ok = music.handle().setVolume(volume);
        if (ok) volumes.put(player.getUniqueId(), volume);
        session.state("note", ok ? "音量 " + Math.round(volume * 100) + "%" : "当前 Backend 不支持音量");
        push(player, session);
    }

    private ClientPlaybackStateCache.Snapshot snapshotOf(Player player, PlaybackSession music) {
        if (plugin.clientProtocol() == null) return null;
        ClientPlaybackStateCache cache = plugin.clientProtocol().stateCache();
        ClientPlaybackStateCache.Snapshot snapshot = cache.snapshot(player, music.handle().id().toString());
        if (snapshot == null && music.track().primary() instanceof AudioSource.Stream) {
            snapshot = cache.latest(player);
        }
        return snapshot;
    }

    private void notifyStatus(Player player, String status) {
        UUID playerId = player.getUniqueId();
        String previous = lastStatus.put(playerId, status == null ? "" : status);
        String current = status == null ? "" : status;
        if (current.equals(previous) || current.isBlank() || !api.supportsToast(player)) return;
        if (current.startsWith("解析失败") || current.startsWith("播放错误")) {
            toast(player, current);
        }
    }

    private void toast(Player player, String text) {
        if (!api.supportsToast(player)) return;
        api.toast(player, new Toast(text, "minecraft:music_disc_cat", "", 3000, 0xFFFFFFFF));
    }

    private float volumeOf(Player player) {
        return volumes.computeIfAbsent(player.getUniqueId(), ignored -> 1f);
    }

    private static String statusNote(PlaybackHandle handle) {
        return handle instanceof StatusAware aware && aware.statusNote() != null
                ? aware.statusNote() : "";
    }

    private void declareKeybinds(Player player) {
        if (api == null || !api.supportsKeybind(player)) return;
        api.keybind(plugin, player, "1", "open_ui", "音乐界面");
        api.keybind(plugin, player, "2", "toggle_hud", "音乐 HUD");
    }

    private static String formatMs(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
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

    private void startHudRefresher(Player player) {
        UUID playerId = player.getUniqueId();
        stopHudRefresher(playerId);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            MineUiSession hud = hudSessions.get(playerId);
            if (hud == null || hud.closed() || !player.isOnline()) {
                stopHudRefresher(playerId);
                return;
            }
            pushHud(player);
        }, 20L, 20L);
        hudRefreshers.put(playerId, task);
    }

    private void stopHudRefresher(UUID playerId) {
        BukkitTask task = hudRefreshers.remove(playerId);
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
            case AudioSource.PackSound ignored -> "PACK";
            case AudioSource.VanillaSound ignored -> "VANILLA";
            case AudioSource.Nbs ignored -> "NBS";
            case AudioSource.Stream ignored -> "STREAM";
        };
    }
}
