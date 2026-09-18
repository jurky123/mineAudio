package com.mineaudio.client.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaCacheTest {

    @Test
    void commitsPartToMedia(@TempDir Path dir) throws Exception {
        MediaCache cache = new MediaCache(dir, 1024 * 1024, 1024 * 1024);
        cache.init();

        assertFalse(cache.has("netease:song:1"));
        Path part = cache.partFor("netease:song:1");
        Files.write(part, "data".getBytes(StandardCharsets.UTF_8));
        cache.commit(part, "netease:song:1");

        assertTrue(cache.has("netease:song:1"));
        assertTrue(Files.exists(cache.fileFor("netease:song:1")));
    }

    @Test
    void cleansUpLeftoverParts(@TempDir Path dir) throws Exception {
        MediaCache cache = new MediaCache(dir, 1024 * 1024, 1024 * 1024);
        cache.init();
        Path part = cache.partFor("track");
        Files.write(part, new byte[10]);
        cache.cleanupParts();
        assertFalse(Files.exists(part));
    }

    @Test
    void evictsOldestWhenOverLimit(@TempDir Path dir) throws Exception {
        MediaCache cache = new MediaCache(dir, 10, 1024);
        cache.init();
        for (int i = 0; i < 3; i++) {
            String key = "track" + i;
            Path part = cache.partFor(key);
            Files.write(part, new byte[6]);
            Files.setLastModifiedTime(part, java.nio.file.attribute.FileTime.fromMillis(1000L + i));
            cache.commit(part, key);
        }
        assertTrue(cache.totalSize() <= 10, "cache size " + cache.totalSize());
        assertFalse(cache.has("track0"));
    }

    @Test
    void hashIsStable() {
        assertEquals(MediaCache.hash("a"), MediaCache.hash("a"));
        assertFalse(MediaCache.hash("a").equals(MediaCache.hash("b")));
    }
}
