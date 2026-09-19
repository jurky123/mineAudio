package com.mineaudio.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CoverUrlsTest {

    @Test
    void appendsThumbParamForNetease() {
        assertEquals("https://p2.music.126.net/x/1.jpg?param=64y64",
                CoverUrls.thumb("https://p2.music.126.net/x/1.jpg"));
    }

    @Test
    void keepsExistingQueryAndOtherHosts() {
        assertEquals("https://p2.music.126.net/x/1.jpg?already=1",
                CoverUrls.thumb("https://p2.music.126.net/x/1.jpg?already=1"));
        assertEquals("https://example.com/a.png",
                CoverUrls.thumb("https://example.com/a.png"));
    }

    @Test
    void nullSafe() {
        assertNull(CoverUrls.thumb(null));
        assertEquals("", CoverUrls.thumb(""));
    }
}
