package com.mineaudio.playback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mineaudio.api.AudioBus;

import net.kyori.adventure.key.Key;

/**
 * 每玩家音频状态：MUSIC 由 {@link MusicArbiter} 选曲后只保留唯一实际播放会话，
 * AMBIENT 可多层（按曲目 ID 去重），SFX / UI 短音效不落表。
 */
public final class PlayerAudioState {

    private final UUID playerId;
    private final MusicArbiter musicArbiter = new MusicArbiter();
    private PlaybackSession playingMusic;
    private MusicIntent playingMusicIntent;
    private final Map<Key, PlaybackSession> ambient = new LinkedHashMap<>();

    public PlayerAudioState(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return playerId;
    }

    public MusicArbiter musicArbiter() {
        return musicArbiter;
    }

    /** 当前实际在播的 MUSIC 会话（可能为 null）。 */
    public PlaybackSession playingMusic() {
        return playingMusic;
    }

    /** 当前实际在播的 MUSIC 对应的 intent（可能为 null）。 */
    public MusicIntent playingMusicIntent() {
        return playingMusicIntent;
    }

    public void setPlayingMusic(PlaybackSession session, MusicIntent intent) {
        this.playingMusic = session;
        this.playingMusicIntent = intent;
    }

    // ---------- AMBIENT ----------

    /** 放入 AMBIENT 会话，返回被同曲目顶替的旧会话；非 AMBIENT 不入表。 */
    public PlaybackSession putAmbient(PlaybackSession session) {
        if (session.track().bus() != AudioBus.AMBIENT) return null;
        return ambient.put(session.track().id(), session);
    }

    public boolean removeAmbient(PlaybackSession session) {
        return ambient.remove(session.track().id(), session);
    }

    public PlaybackSession ambient(Key trackId) {
        return ambient.get(trackId);
    }

    public int ambientCount() {
        return ambient.size();
    }

    public List<PlaybackSession> ambientSessions() {
        return List.copyOf(ambient.values());
    }

    // ---------- 通用查询 ----------

    public List<PlaybackSession> sessions() {
        List<PlaybackSession> sessions = new ArrayList<>();
        if (playingMusic != null) sessions.add(playingMusic);
        sessions.addAll(ambient.values());
        return sessions;
    }

    public List<PlaybackSession> sessionsOn(AudioBus bus) {
        return sessions().stream().filter(session -> session.track().bus() == bus).toList();
    }

    /** 移除任意会话（MUSIC 或 AMBIENT），用于 stop / 断线清理。 */
    public boolean remove(PlaybackSession session) {
        if (playingMusic == session) {
            playingMusic = null;
            playingMusicIntent = null;
            return true;
        }
        return ambient.remove(session.track().id(), session);
    }
}
