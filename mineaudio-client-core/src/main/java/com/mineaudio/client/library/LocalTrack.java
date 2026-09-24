package com.mineaudio.client.library;

import java.nio.file.Path;

/** 本地曲库中的一首曲目（文件在客户端本机，不上传）。 */
public record LocalTrack(
        String id,
        Path file,
        long sizeBytes,
        long modifiedMs,
        long durationMs,
        String title,
        String artist,
        String album,
        String coverFile) {

    /** 时长的显示文本 mm:ss（未知为 --:--）。 */
    public String timeText() {
        if (durationMs <= 0) return "--:--";
        long totalSeconds = durationMs / 1000;
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
