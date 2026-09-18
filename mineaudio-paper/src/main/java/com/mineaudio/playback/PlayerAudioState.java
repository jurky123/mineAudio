package com.mineaudio.playback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mineaudio.api.AudioBus;

import net.kyori.adventure.key.Key;

/**
 * 每玩家音频状态：MUSIC 单会话，AMBIENT 可多层（按曲目 ID 去重），
 * SFX / UI 短音效不落表。API 显式点播的会话不会被区域/世界层顶替。
 */
public final class PlayerAudioState {

    private final UUID playerId;
    private PlaybackSession music;
    private final Map<Key, PlaybackSession> ambient = new LinkedHashMap<>();

    public PlayerAudioState(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return playerId;
    }

    /** 新会话能否顶替当前同 Bus 会话。 */
    public boolean accepts(PlaybackSession incoming) {
        return switch (incoming.track().bus()) {
            case MUSIC -> music == null || music.origin() != PlaybackOrigin.API
                    || incoming.origin() == PlaybackOrigin.API;
            case AMBIENT -> {
                PlaybackSession existing = ambient.get(incoming.track().id());
                yield existing == null || existing.origin() != PlaybackOrigin.API
                        || incoming.origin() == PlaybackOrigin.API;
            }
            case SFX, UI -> true;
        };
    }

    /** 放入会话，返回被顶替的同 Bus 会话（用于停止与事件）。 */
    public PlaybackSession replace(PlaybackSession session) {
        return switch (session.track().bus()) {
            case MUSIC -> {
                PlaybackSession previous = music;
                music = session;
                yield previous;
            }
            case AMBIENT -> ambient.put(session.track().id(), session);
            case SFX, UI -> null;
        };
    }

    public boolean remove(PlaybackSession session) {
        return switch (session.track().bus()) {
            case MUSIC -> {
                boolean removed = music == session;
                if (removed) music = null;
                yield removed;
            }
            case AMBIENT -> ambient.remove(session.track().id(), session);
            case SFX, UI -> false;
        };
    }

    public List<PlaybackSession> sessionsOn(AudioBus bus) {
        return sessions().stream().filter(session -> session.track().bus() == bus).toList();
    }

    public List<PlaybackSession> sessions() {
        List<PlaybackSession> sessions = new java.util.ArrayList<>();
        if (music != null) sessions.add(music);
        sessions.addAll(ambient.values());
        return sessions;
    }

    public PlaybackSession music() {
        return music;
    }

    public PlaybackSession ambient(Key trackId) {
        return ambient.get(trackId);
    }

    public int ambientCount() {
        return ambient.size();
    }
}
