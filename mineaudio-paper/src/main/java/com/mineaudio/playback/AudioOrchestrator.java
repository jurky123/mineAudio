package com.mineaudio.playback;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
import com.mineaudio.stream.search.SearchResult;
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
 *
 * <p>MUSIC 的唯一实际播放入口是 {@link #reconcileMusic(Player)}：各来源只声明
 * {@link MusicIntent}（“应该存在什么”），由 {@link MusicArbiter} 选出唯一 winner
 * （个人 &gt; 受众 &gt; 区域 &gt; 世界）后再真正播放。</p>
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
    /** 点歌队列（每人一个，天然按玩家隔离）。 */
    private final Map<UUID, Deque<AudioTrack>> playQueues = new HashMap<>();
    /** 播放来源比较序号（同层后到者胜）；只增不减。 */
    private final AtomicLong sequence = new AtomicLong();
    /** 玩家最近一次搜索（关键词/页码/结果，UI 与命令共用）。 */
    private record SearchQuery(String keyword, int page, List<SearchResult> results) {
    }

    private final Map<UUID, SearchQuery> searchQueries = new HashMap<>();
    private final Map<UUID, BukkitTask> finishTasks = new HashMap<>();
    /** 已做过终态收尾（清理/事件/续播）的 playback id：同一终态收到两次只处理一次。 */
    private final Set<UUID> terminalSessions = new java.util.HashSet<>();

    /** 个人点播 intent 的固定 sourceId。 */
    private static final String PERSONAL_SOURCE = "personal";
    /** 区域/世界层受管音乐 intent 的固定 sourceId（同一时刻只有一条）。 */
    private static final String MANAGED_SOURCE = "managed";

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
                new Timeline(System.nanoTime() / 1_000_000 + leadMs, 0), sequence.incrementAndGet());
        activeSessions.put(session.id(), session);
        scheduleActiveFinish(session);
        for (Player player : audience.players()) {
            upsertMusicIntent(player, audienceIntent(session));
            reconcileMusic(player);
        }
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
            if (bus == AudioBus.MUSIC) {
                List<String> sources = new ArrayList<>();
                for (MusicIntent intent : state.musicArbiter().intents()) {
                    sources.add(intent.sourceId());
                }
                if (sources.isEmpty()) continue;
                for (String source : sources) {
                    state.musicArbiter().remove(source);
                }
                reconcileMusic(player);
            } else {
                for (PlaybackSession playback : state.sessionsOn(bus)) {
                    state.remove(playback);
                    stopPlayback(player, playback);
                }
            }
        }
    }

    @Override
    public void stopAll(Audience audience) {
        for (Player player : audience.players()) {
            playQueues.remove(player.getUniqueId());
            searchQueries.remove(player.getUniqueId());
            PlayerAudioState state = states.remove(player.getUniqueId());
            if (state == null) continue;
            for (PlaybackSession playback : state.sessions()) {
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

    /** 设置受管（REGION / WORLD）音乐 intent；不覆盖更高优先级的个人/受众音乐。 */
    public PlaybackSession playManaged(Player player, AudioTrack track, PlaybackOrigin origin) {
        if (origin == PlaybackOrigin.API) {
            throw new IllegalArgumentException("playManaged 不接受 API 来源");
        }
        MusicLayer layer = origin == PlaybackOrigin.WORLD ? MusicLayer.WORLD : MusicLayer.REGION;
        MusicIntent intent = new MusicIntent(MANAGED_SOURCE, layer, sequence.incrementAndGet(),
                track, track.options(), origin, null);
        upsertMusicIntent(player, intent);
        reconcileMusic(player);
        return currentManaged(player);
    }

    public PlaybackSession playManagedAmbient(Player player, AudioTrack track) {
        return startAmbient(player, track, track.options(), PlaybackOrigin.REGION);
    }

    public PlaybackSession currentMusic(Player player) {
        PlayerAudioState state = states.get(player.getUniqueId());
        return state == null ? null : state.playingMusic();
    }

    private PlaybackSession currentManaged(Player player) {
        PlayerAudioState state = states.get(player.getUniqueId());
        if (state == null || state.playingMusicIntent() == null) return null;
        return MANAGED_SOURCE.equals(state.playingMusicIntent().sourceId()) ? state.playingMusic() : null;
    }

    /** 该玩家是否有可用的流媒体客户端（MineAudio Client）。 */
    public boolean streamAvailable(Player player) {
        return streamStatus.streamAvailable(player);
    }

    /** 暂停该玩家当前 MUSIC（Backend 不支持时返回 false）。 */
    public boolean pauseMusic(Player player) {
        PlayerAudioState state = states.get(player.getUniqueId());
        return state != null && state.playingMusic() != null && state.playingMusic().handle().pause();
    }

    /** 继续该玩家当前 MUSIC（Backend 不支持时返回 false）。 */
    public boolean resumeMusic(Player player) {
        PlayerAudioState state = states.get(player.getUniqueId());
        return state != null && state.playingMusic() != null && state.playingMusic().handle().resume();
    }

    public List<PlaybackSession> sessions(Player player) {
        PlayerAudioState state = states.get(player.getUniqueId());
        return state == null ? List.of() : state.sessions();
    }

    /** 清除受管 MUSIC（区域/世界层），不动个人/受众音乐。 */
    public boolean stopManagedMusic(Player player) {
        if (removeMusicIntent(player, MANAGED_SOURCE) == null) return false;
        reconcileMusic(player);
        return true;
    }

    /** 停止玩家某条受管 AMBIENT（区域层），不动 API 点播。 */
    public boolean stopManagedAmbient(Player player, Key trackId) {
        PlayerAudioState state = states.get(player.getUniqueId());
        if (state == null) return false;
        PlaybackSession session = state.ambient(trackId);
        if (session == null || session.origin() == PlaybackOrigin.API) return false;
        state.removeAmbient(session);
        stopPlayback(player, session);
        return true;
    }

    // ---------- 搜索 / 点歌队列 ----------

    public enum EnqueueResult {
        ADDED,
        FULL
    }

    public int queueLimit() {
        return Math.max(1, plugin.getConfig().getInt("queue.max-per-player", 2));
    }

    public EnqueueResult enqueue(Player player, AudioTrack track) {
        Deque<AudioTrack> queue = playQueues.computeIfAbsent(player.getUniqueId(),
                ignored -> new ArrayDeque<>());
        if (queue.size() >= queueLimit()) {
            return EnqueueResult.FULL;
        }
        queue.addLast(track);
        // 没有个人音乐在播时立即起播（抢占全服/区域）；已有则等自然结束续播
        PlayerAudioState state = states.get(player.getUniqueId());
        if (state == null || !state.musicArbiter().has(PERSONAL_SOURCE)) {
            advanceQueueIntent(player);
            reconcileMusic(player);
        }
        return EnqueueResult.ADDED;
    }

    public List<AudioTrack> queue(Player player) {
        Deque<AudioTrack> queue = playQueues.get(player.getUniqueId());
        return queue == null ? List.of() : List.copyOf(queue);
    }

    public boolean removeFromQueue(Player player, int index) {
        Deque<AudioTrack> queue = playQueues.get(player.getUniqueId());
        if (queue == null || index < 0 || index >= queue.size()) {
            return false;
        }
        int current = 0;
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            iterator.next();
            if (current++ == index) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    public void clearQueue(Player player) {
        playQueues.remove(player.getUniqueId());
    }

    public void setSearchQuery(Player player, String keyword, int page, List<SearchResult> results) {
        searchQueries.put(player.getUniqueId(), new SearchQuery(keyword, page, List.copyOf(results)));
    }

    public String searchKeyword(Player player) {
        SearchQuery query = searchQueries.get(player.getUniqueId());
        return query == null ? null : query.keyword();
    }

    public int searchPage(Player player) {
        SearchQuery query = searchQueries.get(player.getUniqueId());
        return query == null ? 0 : query.page();
    }

    public List<SearchResult> searchResults(Player player) {
        SearchQuery query = searchQueries.get(player.getUniqueId());
        return query == null ? List.of() : query.results();
    }

    /** 把搜索结果转换为可播放曲目（动态曲目，不进曲库）。 */
    public static AudioTrack searchTrack(SearchResult result) {
        return new AudioTrack(Key.key("mineaudio", "search_" + result.id()), AudioBus.MUSIC,
                new AudioSource.Stream("mineaudio", result.source(), result.id(), null),
                PlaybackOptions.DEFAULT,
                new AudioMetadata(result.title(), result.artist(), result.durationMs()));
    }

    /** 立即播放（不排队），用于搜索结果的“播放”按钮：作为个人点播抢占其他来源。 */
    public PlaybackSession playNow(Player player, AudioTrack track) {
        upsertMusicIntent(player, personalIntent(track, track.options()));
        reconcileMusic(player);
        PlayerAudioState state = states.get(player.getUniqueId());
        if (state == null || state.playingMusicIntent() == null
                || !PERSONAL_SOURCE.equals(state.playingMusicIntent().sourceId())) {
            return null;
        }
        return state.playingMusic();
    }

    /** 队列推进：只更新 intent，不 reconcile；调用方随后只 reconcile 一次（A→B 不经过其他来源）。 */
    private void advanceQueueIntent(Player player) {
        Deque<AudioTrack> queue = playQueues.get(player.getUniqueId());
        PlayerAudioState state = states.get(player.getUniqueId());
        if (state == null) return;
        if (queue == null || queue.isEmpty() || plugin.isShuttingDown()) {
            state.musicArbiter().remove(PERSONAL_SOURCE);
            return;
        }
        AudioTrack next = queue.pollFirst();
        state.musicArbiter().upsert(personalIntent(next, next.options()));
        debug("队列续播：" + player.getName() + " -> " + next.id().asString());
    }

    // ---------- 生命周期 ----------

    public void onJoin(Player player) {
        refresh(player);
    }

    public void onChangedWorld(Player player) {
        refresh(player);
    }

    public void onQuit(Player player) {
        playQueues.remove(player.getUniqueId());
        searchQueries.remove(player.getUniqueId());
        PlayerAudioState state = states.remove(player.getUniqueId());
        if (state != null) {
            for (PlaybackSession playback : state.sessions()) {
                cancelFinish(playback.id());
                terminalSessions.remove(playback.id());
                playback.handle().stop();
            }
        }
    }

    public void shutdown() {
        for (BukkitTask task : finishTasks.values()) {
            task.cancel();
        }
        for (PlayerAudioState state : states.values()) {
            for (PlaybackSession playback : state.sessions()) {
                cancelFinish(playback.id());
                playback.handle().stop();
            }
        }
        finishTasks.clear();
        activeSessions.clear();
        states.clear();
        playQueues.clear();
        searchQueries.clear();
        terminalSessions.clear();
    }

    /** /mineaudio debug：列出该玩家当前会话。 */
    public List<String> describe(Player player) {
        List<String> lines = new ArrayList<>();
        List<MusicIntent> intents = new ArrayList<>();
        PlayerAudioState state = states.get(player.getUniqueId());
        if (state != null) {
            intents.addAll(state.musicArbiter().intents());
        }
        for (MusicIntent intent : intents) {
            lines.add(intent.track().bus() + " " + intent.track().id() + " intent[" + intent.layer()
                    + "/" + intent.sourceId() + "]");
        }
        for (PlaybackSession playback : sessions(player)) {
            lines.add(playback.track().bus() + " " + playback.track().id()
                    + " via " + playback.backend()
                    + " [" + playback.origin() + "]"
                    + (playback.options().loop() ? " (loop)" : ""));
        }
        return lines;
    }

    // ---------- MUSIC 仲裁 ----------

    /** 客户端 HELLO 完成后调用：补做因握手未就绪而失败的受众会话（含晚加入对齐）。 */
    public void onClientReady(Player player) {
        refresh(player);
    }

    private void refresh(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerAudioState state = states.computeIfAbsent(uuid, PlayerAudioState::new);
        for (ActiveSession session : List.copyOf(activeSessions.values())) {
            String sourceId = audienceSource(session);
            boolean should = containsPlayer(session.audience(), player) && !session.completed(uuid);
            boolean has = state.musicArbiter().has(sourceId);
            if (should && !has) {
                state.musicArbiter().upsert(audienceIntent(session));
            } else if (!should && has) {
                state.musicArbiter().remove(sourceId);
            }
        }
        reconcileMusic(player);
    }

    /**
     * MUSIC 唯一实际播放入口：选出 winner，验证可播，切换旧会话并在失败时降级重选。
     * 任何来源都不得绕过此方法直接播放 MUSIC。
     */
    private void reconcileMusic(Player player) {
        if (plugin.isShuttingDown()) return;
        PlayerAudioState state = states.get(player.getUniqueId());
        if (state == null) return;
        MusicArbiter arbiter = state.musicArbiter();

        while (true) {
            MusicIntent winner = arbiter.selectWinner();
            MusicIntent current = state.playingMusicIntent();
            PlaybackSession currentSession = state.playingMusic();

            if (winner == null) {
                if (currentSession != null) {
                    stopPlayback(player, currentSession);
                    state.setPlayingMusic(null, null);
                }
                return;
            }
            if (isExpired(winner)) {
                arbiter.remove(winner.sourceId());
                continue;
            }
            if (current != null && winner.sameContent(current) && currentSession != null) {
                return; // 已收敛
            }

            Optional<AudioSource> resolved = SourceResolver.resolve(winner.track(),
                    packStatus.packAvailable(player), streamStatus.streamAvailable(player), backends::canPlay);
            if (resolved.isEmpty()) {
                debug(player.getName() + " 无法播放 " + winner.track().id() + "（Backend 或资源包不可用且无 fallback）");
                arbiter.remove(winner.sourceId());
                continue;
            }
            AudioBackend backend = backends.find(resolved.get()).orElse(null);
            if (backend == null) {
                arbiter.remove(winner.sourceId());
                continue;
            }
            AudioPlayEvent playEvent = new AudioPlayEvent(player, winner.track());
            Bukkit.getPluginManager().callEvent(playEvent);
            if (playEvent.isCancelled()) {
                arbiter.remove(winner.sourceId());
                continue;
            }

            if (currentSession != null) {
                stopPlayback(player, currentSession);
                state.setPlayingMusic(null, null);
            }
            PlaybackHandle handle = startHandle(player, winner, resolved.get(), backend);
            PlaybackSession session = new PlaybackSession(UUID.randomUUID(), winner.track(),
                    resolved.get(), winner.options(), handle, backend.id(), winner.origin());
            state.setPlayingMusic(session, winner);
            scheduleFinish(player, session, winner);
            Bukkit.getPluginManager().callEvent(new TrackStartedEvent(player, winner.track()));
            return;
        }
    }

    private PlaybackHandle startHandle(Player player, MusicIntent intent, AudioSource source, AudioBackend backend) {
        if (intent.timeline() != null && backend instanceof StreamBackend streamBackend
                && source instanceof AudioSource.Stream) {
            // 共享时间轴：晚加入/恢复按“届时应处进度”起播
            long leadMs = Math.max(0, plugin.getConfig().getLong("stream-client.sync.initial-lead-ms", 1200));
            long startAt = System.nanoTime() / 1_000_000 + leadMs;
            return streamBackend.play(player, intent.track(), source, intent.options(),
                    startAt, intent.timeline().positionAt(startAt));
        }
        return backend.play(player, intent.track(), source, intent.options());
    }

    private boolean isExpired(MusicIntent intent) {
        if (intent.timeline() == null || intent.options().loop()) return false;
        long duration = intent.track().metadata().durationMs();
        if (duration <= 0) return false;
        return intent.timeline().positionAt(System.nanoTime() / 1_000_000) >= duration + 1000;
    }

    private void upsertMusicIntent(Player player, MusicIntent intent) {
        states.computeIfAbsent(player.getUniqueId(), PlayerAudioState::new).musicArbiter().upsert(intent);
    }

    private MusicIntent removeMusicIntent(Player player, String sourceId) {
        PlayerAudioState state = states.get(player.getUniqueId());
        return state == null ? null : state.musicArbiter().remove(sourceId);
    }

    private MusicIntent personalIntent(AudioTrack track, PlaybackOptions options) {
        return new MusicIntent(PERSONAL_SOURCE, MusicLayer.PERSONAL, sequence.incrementAndGet(),
                track, options, PlaybackOrigin.API, null);
    }

    private static String audienceSource(ActiveSession session) {
        return "audience:" + session.id();
    }

    private static MusicIntent audienceIntent(ActiveSession session) {
        return new MusicIntent(audienceSource(session), MusicLayer.AUDIENCE, session.sequence(),
                session.track(), session.options(), PlaybackOrigin.API, session.timeline());
    }

    // ---------- AMBIENT / 内部 ----------

    private PlaybackSession startAmbient(Player player, AudioTrack track, PlaybackOptions options,
                                         PlaybackOrigin origin) {
        Optional<AudioSource> resolved = SourceResolver.resolve(track,
                packStatus.packAvailable(player), streamStatus.streamAvailable(player), backends::canPlay);
        if (resolved.isEmpty()) return null;
        AudioBackend backend = backends.find(resolved.get()).orElse(null);
        if (backend == null) return null;
        AudioPlayEvent playEvent = new AudioPlayEvent(player, track);
        Bukkit.getPluginManager().callEvent(playEvent);
        if (playEvent.isCancelled()) return null;

        PlaybackHandle handle = backend.play(player, track, resolved.get(), options);
        PlaybackSession playback = new PlaybackSession(UUID.randomUUID(), track, resolved.get(),
                options, handle, backend.id(), origin);
        PlayerAudioState state = states.computeIfAbsent(player.getUniqueId(), PlayerAudioState::new);
        PlaybackSession previous = state.putAmbient(playback);
        if (previous != null) {
            stopPlayback(player, previous);
        }
        scheduleFinish(player, playback, null);
        Bukkit.getPluginManager().callEvent(new TrackStartedEvent(player, track));
        return playback;
    }

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

    private static boolean containsPlayer(Audience audience, Player player) {
        for (Player member : audience.players()) {
            if (member.getUniqueId().equals(player.getUniqueId())) return true;
        }
        return false;
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
        if (session == null || !markTerminal(session)) return;
        cancelFinish(session.id());

        MusicIntent intent = session == state.playingMusic() ? state.playingMusicIntent() : null;
        if (session == state.playingMusic()) {
            state.setPlayingMusic(null, null);
        } else {
            state.removeAmbient(session);
        }
        if (intent != null) {
            state.musicArbiter().remove(intent.sourceId());
            if (intent.sourceId().startsWith("audience:")) {
                markAudienceCompleted(intent.sourceId(), player.getUniqueId());
            }
        }
        session.handle().stop();

        boolean music = session.track().bus() == AudioBus.MUSIC;
        if (errorCode != null) {
            plugin.getLogger().warning("[client] " + player.getName() + " 会话 " + sessionId
                    + " 播放失败：" + errorCode + (message == null ? "" : " " + message));
            if (music) {
                Bukkit.getPluginManager().callEvent(new AudioStopEvent(player, session.track()));
            }
        } else if (finished) {
            if (music) {
                Bukkit.getPluginManager().callEvent(new TrackFinishedEvent(player, session.track()));
            }
        }
        if (music && intent != null && PERSONAL_SOURCE.equals(intent.sourceId())) {
            advanceQueueIntent(player);
        }
        reconcileMusic(player);
    }

    private void markAudienceCompleted(String sourceId, UUID playerId) {
        ActiveSession session = audienceSession(sourceId);
        if (session != null) session.markCompleted(playerId);
    }

    private ActiveSession audienceSession(String sourceId) {
        if (!sourceId.startsWith("audience:")) return null;
        try {
            return activeSessions.get(UUID.fromString(sourceId.substring("audience:".length())));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void scheduleFinish(Player player, PlaybackSession playback, MusicIntent intent) {
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
            if (state == null) return;
            // 身份校验：旧会话的迟到终态不得误伤新会话
            boolean stillPlaying = state.playingMusic() == playback;
            boolean stillAmbient = state.ambient(playback.track().id()) == playback;
            if (!stillPlaying && !stillAmbient) {
                terminalSessions.remove(playback.id());
                return;
            }
            if (!markTerminal(playback)) return;
            if (stillPlaying) {
                state.setPlayingMusic(null, null);
                if (intent != null) state.musicArbiter().remove(intent.sourceId());
            } else {
                state.removeAmbient(playback);
            }
            playback.handle().stop();
            Bukkit.getPluginManager().callEvent(new TrackFinishedEvent(player, playback.track()));
            if (stillPlaying && playback.track().bus() == AudioBus.MUSIC
                    && intent != null && PERSONAL_SOURCE.equals(intent.sourceId())) {
                advanceQueueIntent(player);
            }
            reconcileMusic(player);
        }, ticks);
        finishTasks.put(playback.id(), task);
    }

    /** 全服受众逻辑会话的兜底结束（非循环、有已知时长）：清掉残留 intent。 */
    private void scheduleActiveFinish(ActiveSession session) {
        if (session.options().loop()) return;
        long duration = session.track().metadata().durationMs();
        if (duration <= 0) return;
        long ticks = Math.max(1, (duration + 10 * 60_000L) / 50);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            activeSessions.remove(session.id());
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerAudioState state = states.get(player.getUniqueId());
                if (state == null) continue;
                if (state.musicArbiter().remove(audienceSource(session)) != null) {
                    reconcileMusic(player);
                }
            }
        }, ticks);
        session.setFinishTask(task);
    }

    private void stopPlayback(Player player, PlaybackSession playback) {
        cancelFinish(playback.id());
        playback.handle().stop();
        Bukkit.getPluginManager().callEvent(new AudioStopEvent(player, playback.track()));
    }

    /**
     * 终态只处理一次：清理、事件、队列推进。返回 false 表示此前已收尾过，调用方直接返回。
     */
    private boolean markTerminal(PlaybackSession session) {
        return terminalSessions.add(session.id());
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

    /** 一次 Audience 播放的逻辑会话：不含每玩家实际播放，只描述来源/时间轴/生命周期。 */
    public final class ActiveSession implements PlaybackHandle {

        private final UUID id = UUID.randomUUID();
        private final AudioTrack track;
        private final PlaybackOptions options;
        private final Audience audience;
        private final Timeline timeline;
        private final long sequence;
        private final Set<UUID> completed = ConcurrentHashMap.newKeySet();
        private PlaybackState state = PlaybackState.PLAYING;
        private BukkitTask finishTask;

        private ActiveSession(AudioTrack track, PlaybackOptions options, Audience audience,
                              Timeline timeline, long sequence) {
            this.track = track;
            this.options = options;
            this.audience = audience;
            this.timeline = timeline;
            this.sequence = sequence;
        }

        /** 共享播放时间轴（晚加入/恢复对齐依据）。 */
        public Timeline timeline() {
            return timeline;
        }

        public AudioTrack track() {
            return track;
        }

        public PlaybackOptions options() {
            return options;
        }

        public long sequence() {
            return sequence;
        }

        public Audience audience() {
            return audience;
        }

        public boolean completed(UUID playerId) {
            return completed.contains(playerId);
        }

        public void markCompleted(UUID playerId) {
            completed.add(playerId);
        }

        void setFinishTask(BukkitTask task) {
            this.finishTask = task;
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
            if (finishTask != null) {
                finishTask.cancel();
                finishTask = null;
            }
            for (Player player : List.copyOf(audience.players())) {
                PlayerAudioState playerState = states.get(player.getUniqueId());
                if (playerState == null) continue;
                if (playerState.musicArbiter().remove(audienceSource(this)) != null) {
                    reconcileMusic(player);
                }
            }
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
