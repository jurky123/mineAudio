package com.mineaudio.client.library;

/**
 * 从本地文件解析出的元数据。
 *
 * @param durationMs 时长毫秒，未知为 &lt;=0
 * @param title      标题（未知时由扫描器用文件名兜底）
 * @param artist     歌手/作者
 * @param album      专辑
 * @param cover      嵌入封面原始字节，可为 null
 * @param coverExt   封面扩展名（无点，如 {@code jpg}/{@code png}），可为 null
 */
public record TrackMeta(
        long durationMs,
        String title,
        String artist,
        String album,
        byte[] cover,
        String coverExt) {
}
