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
import com.mineaudio.playback.CoverArt;
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
    private static final long SEEK_APPLY_TOLERANCE_MS = 2_000L;
    private static final long SEEK_TIMEOUT_MS = 5_000L;

    private record PendingSeek(String sessionId, long targetMs, boolean playing, long sentAtMs) {
    }

    private final MineAudioPlugin plugin;
    private final MineUi api;
    private final JsonObject page;
    private final JsonObject pageLocal;
    private final JsonObject hudPage;
    private final JsonObject hudPageLocal;
    private final Map<UUID, MineUiSession> sessions = new HashMap<>();
    private final Map<UUID, MineUiSession> hudSessions = new HashMap<>();
    private final Map<UUID, BukkitTask> refreshers = new HashMap<>();
    private final Map<UUID, BukkitTask> hudRefreshers = new HashMap<>();
    private final Map<UUID, List<Key>> trackOrder = new HashMap<>();
    private final Map<UUID, Float> volumes = new HashMap<>();
    private final Map<UUID, PendingSeek> pendingSeeks = new HashMap<>();
    private final Map<UUID, Boolean> pageLocalModes = new HashMap<>();
    private final Map<UUID, Boolean> hudLocalModes = new HashMap<>();
    private final Map<UUID, String> lastStatus = new HashMap<>();
    private boolean broken;

    public MineUiIntegration(MineAudioPlugin plugin) {
        this.plugin = plugin;
        this.api = MineUiProvider.get();
        this.page = load("player");
        this.pageLocal = load("player-local");
        this.hudPage = load("hud");
        this.hudPageLocal = load("hud-local");
        if (api != null) {
            api.onAction(plugin, "mineaudio:open_ui", action -> open(action.player()));
            api.onAction(plugin, "mineaudio:toggle_hud", action -> toggleHud(action.player()));
        }
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent event) {
                Player player = event.getPlayer();
                declareKeybinds(player);
                autoOpenHud(player, 0);
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
        return available() && (hudPage != null || hudPageLocal != null) && api.supportsHud(player);
    }

    /** 客户端是否支持 MineAudio 本地控制（MineUI 本地绑定 + MineAudio 客户端声明业务能力位）。 */
    private boolean hasLocalState(Player player) {
        if (api == null) return false;
        var caps = api.capabilities(player);
        return caps.contains("local_state") && caps.contains("mineaudio_local_v1");
    }

    /** 有本地能力位且（空闲或自研客户端播放）时用本地绑定页面；NBS/PACK 等走服务端控制。 */
    private boolean useLocalPage(Player player) {
        if (!hasLocalState(player)) return false;
        PlaybackSession music = plugin.orchestrator().currentMusic(player);
        return music == null || "stream".equals(music.backend());
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
        boolean local = hasLocalState(player);
        JsonObject definition = local && hudPageLocal != null ? hudPageLocal : hudPage;
        String view = local && hudPageLocal != null ? "hud-local" : "hud";
        MineUiSession hud = api.openHud(plugin, player, APP, view, definition,
                new HudLayout("top_left", 4f, 4f, 1f));
        hudSessions.put(playerId, hud);
        hudLocalModes.put(playerId, local && hudPageLocal != null);
        pushHud(player);
        hud.snapshot();
        startHudRefresher(player);
        plugin.getLogger().info("[ui] " + player.getName() + " HUD 已开启（"
                + (local ? "本地绑定" : "服务端推送") + "）");
        return true;
    }

    /** 进服自动开启 HUD（可配置关闭）；客户端握手有延迟，未就绪时重试。 */
    private void autoOpenHud(Player player, int attempt) {
        if (!plugin.getConfig().getBoolean("stream-client.hud-auto", true)) return;
        if (hudSessions.containsKey(player.getUniqueId())) return;
        if (hudSupported(player)) {
            toggleHud(player);
            return;
        }
        if (attempt >= 4 || !player.isOnline()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> autoOpenHud(player, attempt + 1), 40L);
    }

    @Override
    public boolean open(Player player) {
        if (!available()) return false;
        try {
            if (!api.hasClient(player) || !api.supportsServerUi(player)) return false;
            close(player);
            boolean local = useLocalPage(player);
            if (plugin.debug()) {
                PlaybackSession current = plugin.orchestrator().currentMusic(player);
                plugin.getLogger().info("[ui] " + player.getName() + " 打开页面 local=" + local
                        + " backend=" + (current == null ? "-" : current.backend())
                        + " caps=" + api.capabilities(player));
            }
            JsonObject definition = local && pageLocal != null ? pageLocal : page;
            String view = local && pageLocal != null ? "player-local" : "player";
            MineUiSession session = api.open(plugin, player, APP, view, definition);
            sessions.put(player.getUniqueId(), session);
            pageLocalModes.put(player.getUniqueId(), local && pageLocal != null);
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
            plugin.getLogger().info("[ui] 已向 " + player.getName() + " 下发音乐界面（"
                    + (local ? "本地绑定" : "服务端推送") + "）");
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
        pendingSeeks.remove(playerId);
        pageLocalModes.remove(playerId);
        hudLocalModes.remove(playerId);
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
        pendingSeeks.clear();
        pageLocalModes.clear();
        hudLocalModes.clear();
        lastStatus.clear();
    }

    // ---------- 状态推送 ----------

    private void push(Player player, MineUiSession session) {
        PlaybackSession music = plugin.orchestrator().currentMusic(player);
        // 播放后端变化时切换“本地绑定 / 服务端推送”页面；空闲（无曲目）时不抖
        Boolean mode = pageLocalModes.get(player.getUniqueId());
        boolean wantLocal = useLocalPage(player);
        if (mode != null && music != null && mode != wantLocal) {
            pageLocalModes.remove(player.getUniqueId());
            if (plugin.debug()) {
                plugin.getLogger().info("[ui] " + player.getName() + " 页面模式切换："
                        + (Boolean.TRUE.equals(mode) ? "本地绑定" : "服务端推送") + " -> "
                        + (wantLocal ? "本地绑定" : "服务端推送") + "（backend=" + music.backend() + "）");
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) open(player);
            });
            return;
        }
        boolean localPage = Boolean.TRUE.equals(mode);
        ClientPlaybackStateCache.Snapshot progress = music == null ? null : snapshotOf(player, music);
        String status = "";
        if (music == null) {
            lastStatus.remove(player.getUniqueId());
            session.state("cover", "");
            session.state("cover_visible", false);
            session.state("cover_fallback", true);
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
            String cover = coverOf(music.handle());
            session.state("cover", cover == null ? "" : cover);
            session.state("cover_visible", cover != null && !cover.isBlank());
            session.state("cover_fallback", cover == null || cover.isBlank());
            session.state("state", displayState(music, progress));
            session.state("backend", music.backend() == null ? "-" : music.backend());
            session.state("origin", music.origin().name());
            status = statusNote(music.handle());
            notifyStatus(player, status);
            if (plugin.debug()) {
                plugin.getLogger().info("[ui] " + player.getName() + " handle=" + music.handle().id()
                        + " handleState=" + music.handle().state()
                        + " snapshotSession=" + (progress == null ? "-" : progress.sessionId())
                        + " snapshotState=" + (progress == null ? "-" : progress.state()));
            }
        }
        session.state("status", status);
        session.state("status_visible", !status.isBlank());
        if (music != null) {
            resolvePendingSeek(player, session, music, progress);
        }
        pushProgress(player, session, music == null ? null : progress, localPage);
        if (!localPage) {
            session.state("volume", Math.round(volumeOf(player) * 100) + "%");
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

    private void pushProgress(Player player, MineUiSession session,
                              ClientPlaybackStateCache.Snapshot progress, boolean localPage) {
        if (localPage) {
            // 本地绑定页面自行逐帧读取 position/percent/time/playing，服务端不再重复推送
            session.state("progress_visible", progress != null && progress.durationMs() > 0);
            return;
        }
        long duration = progress == null ? 0 : progress.durationMs();
        PendingSeek pending = pendingSeeks.get(player.getUniqueId());
        boolean hasProgress = duration > 0 && (progress != null || pending != null);
        session.state("progress_visible", hasProgress);
        if (!hasProgress) {
            session.state("percent", 0);
            session.state("playing", false);
            session.state("time", "");
            return;
        }
        long position;
        boolean playing;
        if (pending != null) {
            // 定位中：展示目标位置，保留定位前的播放/暂停状态，等客户端 STATE 确认或超时回退
            position = pending.targetMs();
            playing = pending.playing();
        } else {
            position = progress.displayPositionMs();
            playing = progress.playing();
        }
        position = Math.min(Math.max(0, position), duration);
        session.state("percent", Math.round(1000.0 * position / duration) / 10.0);
        session.state("playing", playing);
        session.state("time", formatMs(position) + " / " + formatMs(duration));
    }

    private void pushHud(Player player) {
        MineUiSession hud = hudSessions.get(player.getUniqueId());
        if (hud == null || hud.closed()) return;
        PlaybackSession music = plugin.orchestrator().currentMusic(player);
        ClientPlaybackStateCache.Snapshot progress = music == null ? null : snapshotOf(player, music);
        if (music != null) {
            hud.state("active", true);
            String cover = coverOf(music.handle());
            hud.state("cover", cover == null ? "" : cover);
            hud.state("cover_visible", cover != null && !cover.isBlank());
            hud.state("cover_fallback", cover == null || cover.isBlank());
            AudioTrack track = music.track();
            hud.state("title", track.metadata().title().isBlank()
                    ? track.id().asString() : track.metadata().title());
            hud.state("subtitle", track.metadata().author().isBlank()
                    ? track.id().asString() : track.metadata().author());
            hud.state("state", displayState(music, progress));
            String status = statusNote(music.handle());
            hud.state("status", status);
            hud.state("status_visible", !status.isBlank());
        } else {
            hud.state("active", false);
            hud.state("cover", "");
            hud.state("cover_visible", false);
            hud.state("cover_fallback", true);
            hud.state("title", "未在播放");
            hud.state("subtitle", "");
            hud.state("state", "IDLE");
            hud.state("status", "");
            hud.state("status_visible", false);
        }
        pushProgress(player, hud, progress, Boolean.TRUE.equals(hudLocalModes.get(player.getUniqueId())));
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
        long duration = progress == null ? 0 : progress.durationMs();
        long current = pendingTarget(player, progress == null ? 0 : progress.displayPositionMs());
        long target = Math.max(0, current + deltaMs);
        if (duration > 0) {
            target = Math.min(target, duration);
        }
        boolean ok = music.handle().seek(Duration.ofMillis(target));
        plugin.getLogger().info("[ui] " + player.getName() + " seek "
                + (deltaMs >= 0 ? "+" : "") + deltaMs + "ms -> " + target + "ms ok=" + ok);
        session.state("note", ok ? "正在定位到 " + formatMs(target) + "…" : "当前 Backend 不支持定位");
        markPendingSeek(player, target, ok);
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
        session.state("note", ok ? "正在定位到 " + formatMs(target) + "…" : "当前 Backend 不支持定位");
        markPendingSeek(player, target, ok);
        push(player, session);
    }

    /** 相对定位时以“待生效目标”为基准，连续点按可以叠加。 */
    private long pendingTarget(Player player, long fallback) {
        PendingSeek pending = pendingSeeks.get(player.getUniqueId());
        return pending == null ? fallback : pending.targetMs();
    }

    /** 记录待确认的 seek：等客户端上报落在目标附近才算生效，超时回退显示实际进度。 */
    private void markPendingSeek(Player player, long targetMs, boolean ok) {
        if (!ok) return;
        PlaybackSession music = plugin.orchestrator().currentMusic(player);
        ClientPlaybackStateCache.Snapshot snapshot = music == null ? null : snapshotOf(player, music);
        boolean playing = snapshot != null && snapshot.playing();
        pendingSeeks.put(player.getUniqueId(), new PendingSeek(
                music == null ? "" : music.handle().id().toString(),
                targetMs, playing, System.currentTimeMillis()));
    }

    private void resolvePendingSeek(Player player, MineUiSession session, PlaybackSession music,
                                    ClientPlaybackStateCache.Snapshot progress) {
        PendingSeek pending = pendingSeeks.get(player.getUniqueId());
        if (pending == null) return;
        // 换曲后旧 seek 不得用新会话的位置确认
        if (!pending.sessionId().equals(music.handle().id().toString())) {
            pendingSeeks.remove(player.getUniqueId());
            return;
        }
        long position = progress == null ? -1 : progress.displayPositionMs();
        if (position >= 0 && Math.abs(position - pending.targetMs()) <= SEEK_APPLY_TOLERANCE_MS) {
            pendingSeeks.remove(player.getUniqueId());
            session.state("note", "已定位到 " + formatMs(pending.targetMs()));
            return;
        }
        if (System.currentTimeMillis() - pending.sentAtMs() > SEEK_TIMEOUT_MS) {
            pendingSeeks.remove(player.getUniqueId());
            session.state("note", "定位未生效，已显示实际进度");
        }
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
        // 句柄 ID 与 PLAY/STATE 的 sessionId 一致，按会话精确查询；不再回退 latest（可能取到环境音）
        return plugin.clientProtocol().stateCache().snapshot(player, music.handle().id().toString());
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

    private static String coverOf(PlaybackHandle handle) {
        return handle instanceof CoverArt art ? art.coverUrl() : null;
    }

    private static String statusNote(PlaybackHandle handle) {
        return handle instanceof StatusAware aware && aware.statusNote() != null
                ? aware.statusNote() : "";
    }

    /** 展示用状态：优先客户端上报（与进度同一份数据），拿不到再退回句柄本地状态。 */
    private static String displayState(PlaybackSession music, ClientPlaybackStateCache.Snapshot progress) {
        if (progress != null && progress.state() != null && !progress.state().isBlank()) {
            return progress.state();
        }
        return music.handle().state().name();
    }

    private void declareKeybinds(Player player) {
        if (api == null || !api.supportsKeybind(player)) return;
        api.keybind(plugin, player, "1", "mineaudio:open_ui", "音乐界面");
        api.keybind(plugin, player, "2", "mineaudio:toggle_hud", "音乐 HUD");
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
