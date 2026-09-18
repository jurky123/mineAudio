package com.mineaudio.backend;

import java.time.Duration;
import java.util.UUID;

import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackState;

import com.xxmicloxx.NoteBlockAPI.songplayer.SongPlayer;

/** NBS 句柄：支持暂停 / 继续 / Seek（按 50ms 一 tick 换算）。 */
final class NbsPlaybackHandle implements PlaybackHandle {

    private final UUID id = UUID.randomUUID();
    private final SongPlayer songPlayer;
    private PlaybackState state = PlaybackState.PLAYING;

    NbsPlaybackHandle(SongPlayer songPlayer) {
        this.songPlayer = songPlayer;
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
        songPlayer.setPlaying(false);
        songPlayer.destroy();
        state = PlaybackState.STOPPED;
        return true;
    }

    @Override
    public boolean pause() {
        if (state != PlaybackState.PLAYING) return false;
        songPlayer.setPlaying(false);
        state = PlaybackState.PAUSED;
        return true;
    }

    @Override
    public boolean resume() {
        if (state != PlaybackState.PAUSED) return false;
        songPlayer.setPlaying(true);
        state = PlaybackState.PLAYING;
        return true;
    }

    @Override
    public boolean seek(Duration position) {
        if (state != PlaybackState.PLAYING && state != PlaybackState.PAUSED) return false;
        songPlayer.setTick((short) Math.max(0, position.toMillis() / 50));
        return true;
    }
}
