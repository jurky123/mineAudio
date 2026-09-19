package com.mineaudio.playback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.AudioCue;
import com.mineaudio.api.AudioMetadata;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.Audience;
import com.mineaudio.api.MineAudio;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackOptions;
import com.mineaudio.api.PlaybackState;
import com.mineaudio.api.event.AudioPlayEvent;
import com.mineaudio.api.event.AudioStopEvent;
import com.mineaudio.api.event.TrackFinishedEvent;
import com.mineaudio.api.event.TrackStartedEvent;
import com.mineaudio.backend.AudioBackend;
import com.mineaudio.backend.BackendRegistry;
import com.mineaudio.backend.StreamBackend;
import com.mineaudio.profile.PlayerPackStatus;
import com.mineaudio.profile.PlayerStreamStatus;
import com.mineaudio.track.CueRegistry;
import com.mineaudio.track.TrackRegistry;

import net.kyori.adventure.key.Key;

/**
 * 播放总控：解析曲目/Cue、按玩家能力与 Backend 可用性选源、维护会话与 Bus 仲裁，
 * 并把 Audience 会话传播给后进服/换世界的玩家。
 */
public final class AudioOrchestrator implements MineAudio {

    private final MineAudioPlugin plugin;
    private final TrackRegistry tracks;
    private final CueRegistry cues;
    private final BackendRegistry backends;
    private final PlayerPackStatus packStatus;
    private final PlayerStreamStatus streamStatus;
    private final Map<UUID, PlayerAudioState> states = new HashMap<>();
    private final Map<UUID, ActiveSession> activeSessions = new LinkedHashMap<>();
    private final Map<UUID, BukkitTask> finishTasks = new HashMap<>();

    public AudioOrchestrator(MineAudioPlugin plugin, TrackRegistry tracks, CueRegistry cues,
                             BackendRegistry backends, PlayerPackStatus packStatus,
                             PlayerStreamStatus streamStatus) {
        this.plugin = plugin;
        this.tracks = tracks;
        this.cues = cues;
        this.backends = backends;
        this.packStatus = packStatus;
        this.streamStatus = streamStatus;
    }

    // ---------- MineAudio API ----------

    @Override
    public PlaybackHandle play(Audience audience, Key track) {
        return play(audience, track, null);
    }

    @Override
    public PlaybackHandle play(Audience audience, Key trackKey, PlaybackOptions override) {
        AudioTrack track = tracks.get(trackKey).orElse(null);
        if (track == null) {
            debug("未知曲目 " + trackKey);
            return NoopPlaybackHandle.stopped();
        }
        PlaybackOptions options = override != null ? override : track.options();
        long leadMs = Math.max(0, plugin.getConfig().getLong("stream-client.sync.initial-lead-ms", 1200));
        ActiveSession session = new ActiveSession(track, options, audience,
                new Timeline(System.nanoTime() / 1_000_000 + leadMs, 0));
        for (Player player : audience.players()) {
            startFor(session, player);
        }
        activeSessions.put(session.id(), session);
        return session;
    }

    @Override
    public void playSfx(Player player, Key cueKey) {
        AudioCue cue = cues.get(cueKey).orElse(null);
        if (cue == null) {
            debug("未知音效 " + cueKey);
            return;
        }
        AudioTrack track = trackOf(cue);
        AudioSource source = resolve(track, player);
        if (source == null) return;
        AudioBackend backend = backends.find(source).orElse(null);
        if (backend != null) {
            backend.play(player, track, source, cue.options());
        }
    }

    @Override
    public void playSfxAt(Location location, Key cueKey) {
        AudioCue cue = cues.get(cueKey).orElse(null);
        if (cue == null) {
            debug("未知音效 " + cueKey);
            return;
        }
        AudioTrack track = trackOf(cue);
        Player nearest = location.getWorld() == null ? null : nearbyPlayer(location);
        AudioSource source = resolve(track, nearest);
        if (source == null) return;
        AudioBackend backend = backends.find(source).orElse(null);
        if (backend != null) {
            backend.playAt(location, track, source, cue.options(), 0);
        }
    }

    /** 在指定位置播放曲目（Emitter 用），radius <= 0 时用 Backend 默认距离。 */
    public PlaybackHandle playTrackAt(Location location, AudioTrack track, double radius) {
        AudioSource source = resolve(track, nearbyPlayer(location));
        if (source == null) return NoopPlaybackHandle.stopped();
        AudioBackend backend = backends.find(source).orElse(null);
        if (backend == null) return NoopPlaybackHandle.stopped();
        return backend.playAt(location, track, source, track.options(), radius);
    }

    @Override
    public void stop(Audience audience, AudioBus bus) {
        for (Player player : audience.players()) {
            PlayerAudioState state = states.get(player.getUniqueId());
            if (state == null) continue;
            for (PlaybackSession playback : state.sessionsOn(bus)) {
                state.remove(playback);
                removeFromActiveSessions(player.getUniqueId(), playback);
                stopPlayback(player, playback);
            }
        }
    }

    @Override
    public void stopAll(Audience audience) {
        for (Player player : audience.players()) {
            PlayerAudioState state = states.remove(player.getUniqueId());
            if (state == null) continue;
            for (PlaybackSession playback : state.sessions()) {
                removeFromActiveSessions(player.getUniqueId(), playback);
                stopPlayback(player, playback);
            }
        }
    }

    @Override
    public boolean hasTrack(Key track) {
        return tracks.contains(track);
    }

    @Override
    public boolean hasCue(Key cue) {
        return cues.contains(cue);
    }

    @Override
    public AudioCapabilities capabilities(Player player) {
        boolean vanillaClient = packStatus.packAvailable(player);
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
        for (AudioBackend backend : backends.all()) {
            if (!backend.available()) continue;
            AudioCapabilities caps = backend.capabilities();
            vanillaClient |= caps.vanillaClient();
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
        return new AudioCapabilities(vanillaClient, seek, pause, volume, fade, loop,
                positional, sync, perPlayer, multi, cache, lyrics);
    }

    @Override
    public void registerCue(Plugin owner, AudioCue cue) {
        cues.register(owner, cue);
    }

    @Override
    public void unregisterCues(Plugin owner) {
        cues.unregister(owner);
    }

    // ---------- Region / World 层 ----------

    /** 播放受管会话（REGION / WORLD）：不覆盖业务 API 的显式点播。 */
    public PlaybackSession playManaged(Player player, AudioTrack track, PlaybackOrigin origin) {
        if (origin == PlaybackOrigin.API) {
            throw new IllegalArgumentException("playManaged 不接受 API 来源");
        }
        return startPlayer(player, track, track.options(), origin);
    }

    public PlaybackSession playManagedAmbient(Player player, AudioTrack track) {
        return startPlayer(player, track, track.options(), PlaybackOrigin.REGION);
    }

    public PlaybackSession currentMusic(Player player) {
        PlayerAudioState state = states.get(player.getUniqueId());
        return state == null ? null : state.music();
    }

    /** 该玩家是否有可用的流媒体客户端（MineAudio Client）。 */
    public boolean streamAvailable(Player player) {
        return streamStatus.streamAvailable(player);
    }

    /** 暂停该玩家当前 MUSIC（Backend 不支持时返回 false）。 */
    public boolean pauseMusic(Player player) {
        PlayerAudioState state = states.get(player.getUniqueId());
        return state != null && state.music() != null && state.music().handle().pause();
    }

    /** 继续该玩家当前 MUSIC（Backend 不支持时返回 false）。 */
    public boolean resumeMusic(Player player) {
        PlayerAudioState state = states.get(player.getUniqueId());
        return state != null && state.music() != null && state.music().handle().resume();
    }

    public List<PlaybackSession> sessions(Player player) {
        PlayerAudioState state = states.get(player.getUniqueId());
        return state == null ? List.of() : state.sessions();
    }

    /** 停止玩家当前受管 MUSIC（区域/世界层），不动 API 点播。 */
    public boolean stopManagedMusic(Player player) {
        PlayerAudioState state = states.get(player.getUniqueId());
        if (state == null || state.music() == null || state.music().origin() == PlaybackOrigin.API) {
            return false;
        }
        stopOne(player, state, state.music());
        return true;
    }

    /** 停止玩家某条受管 AMBIENT（区域层），不动 API 点播。 */
    public boolean stopManagedAmbient(Player player, Key trackId) {
        PlayerAudioState state = states.get(player.getUniqueId());
        if (state == null) return false;
        PlaybackSession session = state.ambient(trackId);
        if (session == null || session.origin() == PlaybackOrigin.API) return false;
        stopOne(player, state, session);
        return true;
    }

    // ---------- 生命周期 ----------

    public void onJoin(Player player) {
        refresh(player);
    }

    public void onChangedWorld(Player player) {
        refresh(player);
    }

    public void onQuit(Player player) {
        PlayerAudioState state = states.remove(player.getUniqueId());
        if (state != null) {
            for (PlaybackSession playback : state.sessions()) {
                cancelFinish(playback.id());
                playback.handle().stop();
            }
        }
        for (ActiveSession session : activeSessions.values()) {
            session.players.remove(player.getUniqueId());
        }
    }

    public void shutdown() {
        for (BukkitTask task : finishTasks.values()) {
            task.cancel();
        }
        // 停掉所有玩家会话（含区域/世界层），再清空状态
        for (PlayerAudioState state : states.values()) {
            for (PlaybackSession playback : state.sessions()) {
                cancelFinish(playback.id());
                playback.handle().stop();
            }
        }
        finishTasks.clear();
        for (ActiveSession session : activeSessions.values()) {
            for (PlaybackSession playback : session.players.values()) {
                playback.handle().stop();
            }
            session.players.clear();
        }
        activeSessions.clear();
        states.clear();
    }

    /** /mineaudio debug：列出该玩家当前会话。 */
    public List<String> describe(Player player) {
        List<String> lines = new ArrayList<>();
        for (PlaybackSession playback : sessions(player)) {
            lines.add(playback.track().bus() + " " + playback.track().id()
                    + " via " + playback.backend()
                    + " [" + playback.origin() + "]"
                    + (playback.options().loop() ? " (loop)" : ""));
        }
        return lines;
    }

    // ---------- 内部 ----------

    private static AudioTrack trackOf(AudioCue cue) {
        return new AudioTrack(cue.id(), cue.bus(), cue.primary(), cue.fallback(),
                cue.options(), AudioMetadata.EMPTY);
    }

    private AudioSource resolve(AudioTrack track, Player player) {
        boolean packAvailable = player == null || packStatus.packAvailable(player);
        boolean streamAvailable = player == null || streamStatus.streamAvailable(player);
        Optional<AudioSource> source = SourceResolver.resolve(track, packAvailable, streamAvailable,
                backends::canPlay);
        if (source.isEmpty()) {
            debug("无法播放 " + track.id() + "（Backend 或资源包不可用且无 fallback）");
            return null;
        }
        return source.get();
    }

    private Player nearbyPlayer(Location location) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : location.getWorld().getPlayers()) {
            double distance = player.getLocation().distanceSquared(location);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private void refresh(Player player) {
        for (ActiveSession session : List.copyOf(activeSessions.values())) {
            boolean shouldPlay = containsPlayer(session.audience, player);
            boolean playing = session.players.containsKey(player.getUniqueId());
            if (shouldPlay && !playing) {
                startFor(session, player);
            } else if (!shouldPlay && playing) {
                PlaybackSession playback = session.players.remove(player.getUniqueId());
                PlayerAudioState state = states.get(player.getUniqueId());
                if (state != null) state.remove(playback);
                stopPlayback(player, playback);
            }
        }
    }

    private static boolean containsPlayer(Audience audience, Player player) {
        for (Player member : audience.players()) {
            if (member.getUniqueId().equals(player.getUniqueId())) return true;
        }
        return false;
    }

    private void startFor(ActiveSession session, Player player) {
        UUID playerId = player.getUniqueId();
        if (session.players.containsKey(playerId)) return;
        PlaybackSession playback = startPlayer(player, session.track, session.options, PlaybackOrigin.API, session);
        if (playback != null) {
            session.players.put(playerId, playback);
        }
    }

    /** 客户端 HELLO 完成后调用：补做因握手未就绪而失败的受众会话（含晚加入对齐）。 */
    public void onClientReady(Player player) {
        refresh(player);
    }

    /** 启动一次单玩家播放；被更高优先级会话拒绝或无法选源时返回 null。 */
    private PlaybackSession startPlayer(Player player, AudioTrack track, PlaybackOptions options,
                                        PlaybackOrigin origin) {
        return startPlayer(player, track, options, origin, null);
    }

    private PlaybackSession startPlayer(Player player, AudioTrack track, PlaybackOptions options,
                                        PlaybackOrigin origin, ActiveSession session) {
        Optional<AudioSource> resolved = SourceResolver.resolve(track,
                packStatus.packAvailable(player), streamStatus.streamAvailable(player), backends::canPlay);
        if (resolved.isEmpty()) {
            debug(player.getName() + " 无法播放 " + track.id() + "（Backend 或资源包不可用且无 fallback）");
            return null;
        }
        PlayerAudioState state = states.computeIfAbsent(player.getUniqueId(), PlayerAudioState::new);
        PlaybackSession candidate = new PlaybackSession(UUID.randomUUID(), track, resolved.get(),
                options, NoopPlaybackHandle.stopped(), null, origin);
        if (!state.accepts(candidate)) {
            return null;
        }
        AudioPlayEvent playEvent = new AudioPlayEvent(player, track);
        Bukkit.getPluginManager().callEvent(playEvent);
        if (playEvent.isCancelled()) return null;
        AudioBackend backend = backends.find(resolved.get()).orElse(null);
        if (backend == null) return null;

        PlaybackHandle handle;
        if (session != null && backend instanceof StreamBackend streamBackend
                && resolved.get() instanceof AudioSource.Stream) {
            // 共享时间轴：晚加入者按“届时应处进度”起播
            long leadMs = Math.max(0, plugin.getConfig().getLong("stream-client.sync.initial-lead-ms", 1200));
            long startAt = System.nanoTime() / 1_000_000 + leadMs;
            handle = streamBackend.play(player, track, resolved.get(), options,
                    startAt, session.timeline().positionAt(startAt));
        } else {
            handle = backend.play(player, track, resolved.get(), options);
        }
        PlaybackSession playback = new PlaybackSession(candidate.id(), track, resolved.get(),
                options, handle, backend.id(), origin);
        PlaybackSession previous = state.replace(playback);
        if (previous != null) {
            removeFromActiveSessions(player.getUniqueId(), previous);
            stopPlayback(player, previous);
        }
        scheduleFinish(player, playback);
        Bukkit.getPluginManager().callEvent(new TrackStartedEvent(player, track));
        return playback;
    }

    /** 客户端上报终态：幂等收尾会话；FINISHED 走 TrackFinishedEvent，ERROR 走 AudioStopEvent。 */
    public void onClientTerminal(Player player, String sessionId, boolean finished,
                                 String errorCode, String message) {
        if (sessionId == null) return;
        PlayerAudioState state = states.get(player.getUniqueId());
        if (state == null) return;
        PlaybackSession session = state.sessions().stream()
                .filter(s -> s.handle().id().toString().equals(sessionId))
                .findFirst().orElse(null);
        if (session == null) return;
        cancelFinish(session.id());
        state.remove(session);
        removeFromActiveSessions(player.getUniqueId(), session);
        session.handle().stop();
        if (errorCode != null) {
            plugin.getLogger().warning("[client] " + player.getName() + " 会话 " + sessionId
                    + " 播放失败：" + errorCode + (message == null ? "" : " " + message));
            Bukkit.getPluginManager().callEvent(new AudioStopEvent(player, session.track()));
        } else if (finished) {
            Bukkit.getPluginManager().callEvent(new TrackFinishedEvent(player, session.track()));
        }
    }

    private void scheduleFinish(Player player, PlaybackSession playback) {
        if (playback.options().loop()) return;
        long duration = playback.track().metadata().durationMs();
        if (duration <= 0) {
            AudioBackend backend = backends.byId(playback.backend()).orElse(null);
            if (backend != null) {
                duration = backend.durationMs(playback.track(), playback.source());
            }
        }
        if (duration <= 0) return;
        // STREAM 以客户端输出耗尽的终态为准，这里只做断联/状态丢失的兜底
        long ticks = "stream".equals(playback.backend())
                ? Math.max(1, (duration + 10 * 60_000L) / 50)
                : Math.max(1, duration / 50);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            finishTasks.remove(playback.id());
            PlayerAudioState state = states.get(player.getUniqueId());
            if (state != null) state.remove(playback);
            removeFromActiveSessions(player.getUniqueId(), playback);
            Bukkit.getPluginManager().callEvent(new TrackFinishedEvent(player, playback.track()));
        }, ticks);
        finishTasks.put(playback.id(), task);
    }

    private void stopOne(Player player, PlayerAudioState state, PlaybackSession playback) {
        state.remove(playback);
        removeFromActiveSessions(player.getUniqueId(), playback);
        stopPlayback(player, playback);
    }

    private void stopPlayback(Player player, PlaybackSession playback) {
        cancelFinish(playback.id());
        playback.handle().stop();
        Bukkit.getPluginManager().callEvent(new AudioStopEvent(player, playback.track()));
    }

    private void removeFromActiveSessions(UUID playerId, PlaybackSession playback) {
        for (ActiveSession session : activeSessions.values()) {
            if (session.players.get(playerId) == playback) {
                session.players.remove(playerId);
                return;
            }
        }
    }

    private void cancelFinish(UUID playbackId) {
        BukkitTask task = finishTasks.remove(playbackId);
        if (task != null) task.cancel();
    }

    private void debug(String message) {
        if (plugin.debug()) {
            plugin.getLogger().info("[debug] " + message);
        }
    }

    /** 一次 Audience 播放：包含各玩家实际会话，整体停止。 */
    public final class ActiveSession implements PlaybackHandle {

        private final UUID id = UUID.randomUUID();
        private final AudioTrack track;
        private final PlaybackOptions options;
        private final Audience audience;
        private final Timeline timeline;
        private final Map<UUID, PlaybackSession> players = new LinkedHashMap<>();
        private PlaybackState state = PlaybackState.PLAYING;

        private ActiveSession(AudioTrack track, PlaybackOptions options, Audience audience, Timeline timeline) {
            this.track = track;
            this.options = options;
            this.audience = audience;
            this.timeline = timeline;
        }

        /** 共享播放时间轴（晚加入对齐依据）。 */
        public Timeline timeline() {
            return timeline;
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
            if (state != PlaybackState.PLAYING && state != PlaybackState.PAUSED) return false;
            state = PlaybackState.STOPPED;
            activeSessions.remove(id);
            for (Map.Entry<UUID, PlaybackSession> entry : List.copyOf(players.entrySet())) {
                Player player = Bukkit.getPlayer(entry.getKey());
                PlayerAudioState playerState = states.get(entry.getKey());
                if (playerState != null) playerState.remove(entry.getValue());
                if (player != null && player.isOnline()) {
                    stopPlayback(player, entry.getValue());
                } else {
                    cancelFinish(entry.getValue().id());
                    entry.getValue().handle().stop();
                }
            }
            players.clear();
            return true;
        }

        @Override
        public boolean pause() {
            return false;
        }

        @Override
        public boolean resume() {
            return false;
        }

        @Override
        public boolean seek(Duration position) {
            return false;
        }
    }
}
