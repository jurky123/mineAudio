package com.mineaudio.client.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalLibraryTest {

    private static TrackMeta meta(String title, String artist, long durationMs, byte[] cover, String ext) {
        return new TrackMeta(durationMs, title, artist, "album", cover, ext);
    }

    @Test
    void scanParsesMetadataAndWritesCover(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("song.mp3"), new byte[] {1, 2, 3, 4});
        MetadataProbe probe = file -> meta("稻香", "周杰伦", 223_000, new byte[] {(byte) 0xFF, (byte) 0xD8}, "jpg");
        LocalLibrary library = new LocalLibrary(dir, probe);

        library.scan();

        assertEquals(1, library.size());
        LocalTrack track = library.track(0);
        assertEquals("稻香", track.title());
        assertEquals("周杰伦", track.artist());
        assertEquals("3:43", track.timeText());
        assertNotNull(track.coverFile());
        assertTrue(Files.isRegularFile(dir.resolve(track.coverFile())));
        assertNotNull(track.id());
    }

    @Test
    void rescanReusesCacheAndSkipsProbe(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("song.mp3"), new byte[] {1, 2, 3, 4});
        AtomicInteger calls = new AtomicInteger();
        MetadataProbe probe = file -> {
            calls.incrementAndGet();
            return meta("t", "a", 1000, null, null);
        };
        LocalLibrary library = new LocalLibrary(dir, probe);

        library.scan();
        library.scan(); // 内容未变 → 复用索引，不再解析

        assertEquals(1, calls.get());
        assertEquals(1, library.size());
    }

    @Test
    void fallbackTitleFromFileNameWhenNoTag(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("My Song.mp3"), new byte[] {9});
        LocalLibrary library = new LocalLibrary(dir, file -> meta(null, null, -1, null, null));

        library.scan();

        assertEquals("My Song", library.track(0).title());
        assertEquals("--:--", library.track(0).timeText());
    }

    @Test
    void deleteRemovesFileAndEntry(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("gone.mp3");
        Files.write(file, new byte[] {7});
        LocalLibrary library = new LocalLibrary(dir, f -> meta("x", "y", 500, null, null));
        library.scan();
        assertTrue(library.delete(0));
        assertEquals(0, library.size());
        assertTrue(Files.notExists(file));
    }

    @Test
    void nonAudioFilesIgnored(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("readme.txt"), new byte[] {1});
        LocalLibrary library = new LocalLibrary(dir, f -> meta("t", "a", 1, null, null));
        library.scan();
        assertEquals(0, library.size());
        assertNull(library.track(0));
    }
}
