package com.mineaudio.client.library;

import java.nio.file.Path;

/**
 * 本地曲库中的一首曲目。
 *
 * <p>{@code file} 是玩家放入的源文件（可能是 .ncm）；{@code playableFile} 是实际播放文件
 * （普通音频等于 {@code file}，.ncm 则为解密后的缓存文件）。</p>
 */
public record LocalTrack(
        String id,
        Path file,
        Path playableFile,
        long sizeBytes,
        long modifiedMs,
        long durationMs,
        String title,
        String artist,
        String album,
        String coverFile,
        String lyricsFile) {

    /** 时长的显示文本 mm:ss（未知为 --:--）。 */
    public String timeText() {
        if (durationMs <= 0) return "--:--";
        long totalSeconds = durationMs / 1000;
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
