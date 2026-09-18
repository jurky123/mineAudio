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
import com.mineaudio.profile.PlayerPackStatus;
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
    private final Map<UUID, PlayerAudioState> states = new HashMap<>();
    private final Map<UUID, ActiveSession> activeSessions = new LinkedHashMap<>();
    private final Map<UUID, BukkitTask> finishTasks = new HashMap<>();

    public AudioOrchestrator(MineAudioPlugin plugin, TrackRegistry tracks, CueRegistry cues,
                             BackendRegistry backends, PlayerPackStatus packStatus) {
        this.plugin = plugin;
        this.tracks = tracks;
        this.cues = cues;
        this.backends = backends;
        this.packStatus = packStatus;
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
        ActiveSession session = new ActiveSession(track, options, audience);
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
        AudioSource source = resolve(trackOf(cue), player);
        if (source == null) return;
        AudioBackend backend = backends.find(source).orElse(null);
        if (backend != null) {
            backend.play(player, trackOf(cue), source, cue.options());
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
        // 位置音效面向附近玩家播放，资源包可用性按"任一附近玩家"无法统一，默认走 fallback 逻辑
        AudioSource source = resolve(track, location.getWorld() == null ? null : nearbyPlayer(location));
        if (source == null) return;
        AudioBackend backend = backends.find(source).orElse(null);
        if (backend != null) {
            backend.playAt(location, track, source, cue.options());
        }
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
        boolean loop = false;
        boolean positional = false;
        boolean sync = false;
        boolean multi = false;
        boolean lyrics = false;
        for (AudioBackend backend : backends.all()) {
            if (!backend.available()) continue;
            AudioCapabilities caps = backend.capabilities();
            vanillaClient |= caps.vanillaClient();
            seek |= caps.seek();
            pause |= caps.pause();
            loop |= caps.loop();
            positional |= caps.positional();
            sync |= caps.synchronizedPlayback();
            multi |= caps.multiSession();
            lyrics |= caps.lyrics();
        }
        return new AudioCapabilities(vanillaClient, seek, pause, loop, positional, sync, multi, lyrics);
    }

    @Override
    public void registerCue(Plugin owner, AudioCue cue) {
        cues.register(owner, cue);
    }

    @Override
    public void unregisterCues(Plugin owner) {
        cues.unregister(owner);
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

    /** /audio debug：列出该玩家当前会话。 */
    public List<String> describe(Player player) {
        PlayerAudioState state = states.get(player.getUniqueId());
        if (state == null) return List.of();
        List<String> lines = new ArrayList<>();
        for (PlaybackSession playback : state.sessions()) {
            lines.add(playback.track().bus() + " " + playback.track().id()
                    + " via " + playback.backend()
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
        Optional<AudioSource> source = SourceResolver.resolve(track, packAvailable, backends::canPlay);
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
        AudioTrack track = session.track;
        Optional<AudioSource> resolved = SourceResolver.resolve(track,
                packStatus.packAvailable(player), backends::canPlay);
        if (resolved.isEmpty()) {
            debug(player.getName() + " 无法播放 " + track.id() + "（Backend 或资源包不可用且无 fallback）");
            return;
        }
        AudioPlayEvent playEvent = new AudioPlayEvent(player, track);
        Bukkit.getPluginManager().callEvent(playEvent);
        if (playEvent.isCancelled()) return;
        AudioBackend backend = backends.find(resolved.get()).orElse(null);
        if (backend == null) return;

        PlaybackHandle handle = backend.play(player, track, resolved.get(), session.options);
        PlaybackSession playback = new PlaybackSession(UUID.randomUUID(), track, resolved.get(),
                session.options, handle, backend.id());
        PlayerAudioState state = states.computeIfAbsent(playerId, PlayerAudioState::new);
        PlaybackSession previous = state.replace(playback);
        if (previous != null && previous != playback) {
            removeFromActiveSessions(playerId, previous);
            stopPlayback(player, previous);
        }
        session.players.put(playerId, playback);
        scheduleFinish(player, session, playback);
        Bukkit.getPluginManager().callEvent(new TrackStartedEvent(player, track));
    }

    private void scheduleFinish(Player player, ActiveSession session, PlaybackSession playback) {
        if (playback.options().loop()) return;
        long duration = playback.track().metadata().durationMs();
        if (duration <= 0) return;
        long ticks = Math.max(1, duration / 50);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            finishTasks.remove(playback.id());
            session.players.remove(player.getUniqueId());
            PlayerAudioState state = states.get(player.getUniqueId());
            if (state != null) state.remove(playback);
            if (session.players.isEmpty() && !session.options.loop()) {
                session.state = PlaybackState.FINISHED;
                activeSessions.remove(session.id());
            }
            Bukkit.getPluginManager().callEvent(new TrackFinishedEvent(player, playback.track()));
        }, ticks);
        finishTasks.put(playback.id(), task);
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
        private final Map<UUID, PlaybackSession> players = new LinkedHashMap<>();
        private PlaybackState state = PlaybackState.PLAYING;

        private ActiveSession(AudioTrack track, PlaybackOptions options, Audience audience) {
            this.track = track;
            this.options = options;
            this.audience = audience;
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
