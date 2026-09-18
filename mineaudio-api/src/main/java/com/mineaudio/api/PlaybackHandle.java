package com.mineaudio.api;

import java.time.Duration;
import java.util.UUID;

/**
 * 一次播放的句柄。操作是否被支持以 {@link AudioCapabilities} 为准：
 * 不支持时方法返回 false 且状态保持不撒谎（例如 PACK 的 seek 恒为 false）。
 */
public interface PlaybackHandle {

    UUID id();

    PlaybackState state();

    boolean stop();

    boolean pause();

    boolean resume();

    boolean seek(Duration position);

    /** 运行时音量（0~1），Backend 不支持时返回 false。 */
    default boolean setVolume(float volume) {
        return false;
    }
}
