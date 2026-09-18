package com.mineaudio.stream;

import java.util.UUID;

import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackOptions;

/** 一次流媒体播放请求（per-player session）。 */
public record StreamPlaybackRequest(
        UUID sessionId,
        AudioTrack track,
        AudioSource.Stream source,
        PlaybackOptions options,
        StreamTiming timing) {

    /** 服务端单调时钟毫秒；客户端按校时结果对齐后播放。 */
    public record StreamTiming(long serverStartTimeMs, long positionMs, int revision) {
    }
}
