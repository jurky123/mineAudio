package com.mineaudio.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mineaudio.api.AudioSource;

class MoeMusicLegacyProviderTest {

    private static final AudioSource.Stream BY_ID =
            new AudioSource.Stream("moemusic", "netease", "1234567890", null);
    private static final AudioSource.Stream BY_URI =
            new AudioSource.Stream("moemusic", null, null, "https://cdn.example.com/a.mp3");

    @Test
    void buildsAddByIdCommand() {
        assertEquals(Optional.of("music addById netease 1234567890 --now"),
                MoeMusicLegacyProvider.playCommand(BY_ID, false, List.of()));
    }

    @Test
    void rejectsUriWhenHttpDisabled() {
        assertTrue(MoeMusicLegacyProvider.playCommand(BY_URI, false, List.of()).isEmpty());
    }

    @Test
    void allowsUriWithHostWhitelist() {
        assertEquals(Optional.of("music add --now https://cdn.example.com/a.mp3"),
                MoeMusicLegacyProvider.playCommand(BY_URI, true, List.of("example.com")));
        assertTrue(MoeMusicLegacyProvider.playCommand(BY_URI, true, List.of("other.com")).isEmpty());
    }

    @Test
    void rejectsNonHttpUri() {
        AudioSource.Stream ftp = new AudioSource.Stream("moemusic", null, null, "ftp://example.com/a.mp3");
        assertTrue(MoeMusicLegacyProvider.playCommand(ftp, true, List.of()).isEmpty());
    }
}
