package com.mineaudio.playback;

import java.util.UUID;

import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackState;

/** 占位句柄：曲目/音效无法解析或不可用时返回，API 不返回 null。 */
public final class NoopPlaybackHandle implements PlaybackHandle {

    private static final NoopPlaybackHandle STOPPED = new NoopPlaybackHandle(PlaybackState.STOPPED);
    private static final NoopPlaybackHandle UNSUPPORTED = new NoopPlaybackHandle(PlaybackState.UNSUPPORTED);

    private final UUID id = UUID.randomUUID();
    private final PlaybackState state;

    private NoopPlaybackHandle(PlaybackState state) {
        this.state = state;
    }

    public static NoopPlaybackHandle stopped() {
        return STOPPED;
    }

    public static NoopPlaybackHandle unsupported() {
        return UNSUPPORTED;
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
        return false;
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
    public boolean seek(java.time.Duration position) {
        return false;
    }
}
