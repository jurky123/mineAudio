package com.mineaudio.api;

/** 玩家实际可用的音频能力（Backend 能力 + 客户端资源包状态）。 */
public record AudioCapabilities(
        boolean vanillaClient,
        boolean seek,
        boolean pause,
        boolean loop,
        boolean positional,
        boolean synchronizedPlayback,
        boolean multiSession,
        boolean lyrics) {

    public static final AudioCapabilities NONE =
            new AudioCapabilities(false, false, false, false, false, false, false, false);
}
