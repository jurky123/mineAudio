package com.mineaudio.playback;

/**
 * 共享播放时间轴：{@code startAtMs} 为“进度等于 offsetMs”的服务端单调时刻，
 * 任意时刻的进度 = offset + max(0, now - startAt)。晚加入者传入“未来起播时刻”即可得到届时应处进度。
 */
public record Timeline(long startAtMs, long offsetMs) {

    public long positionAt(long serverTimeMs) {
        return offsetMs + Math.max(0, serverTimeMs - startAtMs);
    }
}
