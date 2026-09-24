package com.mineaudio.client.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 用同算法的编码器构造 .ncm 再解密，验证容器解析/AES/base64/keyBox/音频异或链路。
 * 真实网易文件的常量一致性仍需实机验证。
 */
class NcmDecoderTest {

    @Test
    void decodesContainer(@TempDir Path dir) throws Exception {
        byte[] audio = "MP3-AUDIO-BYTES-0123456789".getBytes(StandardCharsets.US_ASCII);
        byte[] cover = new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4};
        String metaJson = "{\"musicName\":\"测试歌\",\"artist\":[[\"歌手A\",1]],\"album\":\"专辑X\","
                + "\"duration\":123456,\"format\":\"mp3\"}";
        Path ncm = dir.resolve("song.ncm");
        Files.write(ncm, NcmFixtures.buildNcm(metaJson, cover, audio));

        NcmDecoder.Decoded decoded = new NcmDecoder().decode(ncm, dir.resolve(".decoded"));

        assertNotNull(decoded.audio());
        assertArrayEquals(audio, Files.readAllBytes(decoded.audio()));
        assertEquals("测试歌", decoded.title());
        assertEquals("歌手A", decoded.artist());
        assertEquals("专辑X", decoded.album());
        assertEquals(123456, decoded.durationMs());
        assertArrayEquals(cover, decoded.cover());
        assertEquals("jpg", decoded.coverExt());
        assertTrue(decoded.audio().getFileName().toString().endsWith(".mp3"));
    }
}
