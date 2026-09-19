package com.mineaudio.stream.search;

/** 搜索结果条目：展示与点播所需的全部信息。 */
public record SearchResult(
        String source,
        String id,
        String title,
        String artist,
        String coverUrl,
        long durationMs,
        boolean playable,
        String note) {
}
