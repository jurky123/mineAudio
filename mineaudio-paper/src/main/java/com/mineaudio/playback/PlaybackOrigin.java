package com.mineaudio.playback;

/** 会话来源：世界层 < 区域层 < 业务 API 显式点播；API 会话不被区域自动切换覆盖。 */
public enum PlaybackOrigin {
    WORLD,
    REGION,
    API
}
