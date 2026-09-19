package com.mineaudio.stream.resolve;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 网易歌曲元数据缓存（标题/歌手/封面）：搜索与 URL 解析互相预热，
 * 让详情请求不必阻塞播放链路（命中时 URL 完成即带封面）。
 */
public final class SongMetaCache {

    public record Meta(String title, String artist, String coverUrl) {
    }

    private static final long TTL_MS = 30 * 60_000L;

    private record Entry(Meta meta, long expiresAtMs) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public void put(String songKey, String title, String artist, String coverUrl) {
        entries.put(songKey, new Entry(new Meta(title, artist, coverUrl),
                System.currentTimeMillis() + TTL_MS));
    }

    /** 命中返回元数据（任意字段可为 null），未命中返回 null。 */
    public Meta get(String songKey) {
        Entry entry = entries.get(songKey);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAtMs()) {
            entries.remove(songKey, entry);
            return null;
        }
        return entry.meta();
    }
}
