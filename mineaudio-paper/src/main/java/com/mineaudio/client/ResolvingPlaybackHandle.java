package com.mineaudio.client;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackState;

/**
 * 异步解析期间占位句柄：解析完成后把操作转发给真正的句柄（客户端会话或 Legacy）。
 * 解析完成前的暂停/定位/音量会被记住并在挂接时补发。
 */
final class ResolvingPlaybackHandle implements PlaybackHandle {

    private final UUID id = UUID.randomUUID();
    private final AtomicReference<PlaybackHandle> delegate = new AtomicReference<>();
    private volatile boolean cancelled;
    private volatile boolean failed;
    private volatile Boolean pendingPaused;
    private volatile Duration pendingSeek;
    private volatile Float pendingVolume;

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public PlaybackState state() {
        PlaybackHandle current = delegate.get();
        if (current != null) return current.state();
        return failed ? PlaybackState.ERROR : PlaybackState.PENDING;
    }

    @Override
    public boolean stop() {
        cancelled = true;
        PlaybackHandle current = delegate.get();
        return current == null || current.stop();
    }

    @Override
    public boolean pause() {
        PlaybackHandle current = delegate.get();
        if (current != null) return current.pause();
        pendingPaused = true;
        return true;
    }

    @Override
    public boolean resume() {
        PlaybackHandle current = delegate.get();
        if (current != null) return current.resume();
        pendingPaused = false;
        return true;
    }

    @Override
    public boolean seek(Duration position) {
        PlaybackHandle current = delegate.get();
        if (current != null) return current.seek(position);
        pendingSeek = position;
        return true;
    }

    @Override
    public boolean setVolume(float volume) {
        PlaybackHandle current = delegate.get();
        if (current != null) return current.setVolume(volume);
        pendingVolume = volume;
        return true;
    }

    boolean cancelled() {
        return cancelled;
    }

    void fail() {
        failed = true;
    }

    void attach(PlaybackHandle handle) {
        if (!delegate.compareAndSet(null, handle)) return;
        if (cancelled) {
            handle.stop();
            return;
        }
        Boolean paused = pendingPaused;
        if (paused != null) {
            if (paused) handle.pause();
            else handle.resume();
        }
        Duration seek = pendingSeek;
        if (seek != null) handle.seek(seek);
        Float volume = pendingVolume;
        if (volume != null) handle.setVolume(volume);
    }
}
