package com.mineaudio.api;

public enum PlaybackState {
    PENDING,
    PLAYING,
    PAUSED,
    STOPPED,
    FINISHED,
    /** 当前 Backend 不支持该操作。 */
    UNSUPPORTED
}
