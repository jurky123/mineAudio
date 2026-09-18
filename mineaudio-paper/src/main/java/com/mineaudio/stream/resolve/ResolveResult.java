package com.mineaudio.stream.resolve;

import java.net.URI;
import java.time.Instant;

/**
 * 解析结果：只保留播放真正需要的数据。
 * title/artist/durationMs 为 0/null 时由曲目元数据补齐；expiresAt 为 null 表示无明确过期时间。
 */
public record ResolveResult(
        URI streamUrl,
        String title,
        String artist,
        long durationMs,
        Instant expiresAt) {

    public static ResolveResult url(URI streamUrl) {
        return new ResolveResult(streamUrl, null, null, 0, null);
    }
}
