package com.mineaudio.api;

public enum PlaybackState {
    PENDING,
    /** 已开始取流/解码，尚未达到可播放阈值。 */
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    FINISHED,
    /** 不可恢复错误（由 Backend/客户端上报）。 */
    ERROR,
    /** 当前 Backend 不支持该操作。 */
    UNSUPPORTED
}
