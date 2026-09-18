package com.mineaudio.api;

/** 玩家实际可用的音频能力（Backend 能力 + 客户端状态）。 */
public record AudioCapabilities(
        boolean vanillaClient,
        boolean seek,
        boolean pause,
        boolean volume,
        boolean fade,
        boolean loop,
        boolean positional,
        boolean synchronizedPlayback,
        boolean perPlayer,
        boolean multiSession,
        boolean cache,
        boolean lyrics) {

    public static final AudioCapabilities NONE =
            new AudioCapabilities(false, false, false, false, false, false,
                    false, false, false, false, false, false);
}
