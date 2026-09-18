package com.mineaudio.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MoeMusicNowPlayingTest {

    @Test
    void parsesChineseNowPlayingLine() {
        List<String> lines = List.of(
                "  §6正在播放§8: §6稻香",
                "  §7周杰伦 §83:51 §8@jzk",
                "  §e1. §b晴天");
        MoeMusicNowPlaying.NowPlaying now = MoeMusicNowPlaying.parse(lines).orElseThrow();
        assertEquals("稻香", now.title());
        assertEquals("周杰伦", now.artist());
    }

    @Test
    void parsesEnglishNowPlayingLine() {
        List<String> lines = List.of(
                "  Now playing: Example Song",
                "  Example Artist 4:12 @player");
        MoeMusicNowPlaying.NowPlaying now = MoeMusicNowPlaying.parse(lines).orElseThrow();
        assertEquals("Example Song", now.title());
        assertEquals("Example Artist", now.artist());
    }

    @Test
    void parsesWithoutMetaLine() {
        List<String> lines = List.of("  §6正在播放§8: §6只有标题");
        MoeMusicNowPlaying.NowPlaying now = MoeMusicNowPlaying.parse(lines).orElseThrow();
        assertEquals("只有标题", now.title());
        assertEquals("", now.artist());
    }

    @Test
    void emptyWhenQueueHasOnlyEntries() {
        List<String> lines = List.of(
                "  §e1. §b稻香",
                "  §7周杰伦 §83:51");
        assertTrue(MoeMusicNowPlaying.parse(lines).isEmpty());
    }

    @Test
    void emptyForEmptyQueue() {
        assertTrue(MoeMusicNowPlaying.parse(List.of()).isEmpty());
    }
}
