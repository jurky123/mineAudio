package com.mineaudio.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProtocolCodecTest {

    @Test
    void roundTripsPlayPacket() throws Exception {
        Packets.Play play = new Packets.Play(
                "mineaudio:night_song", "netease", "123456", "https://cdn.example.com/a.mp3",
                Map.of("Referer", "https://music.163.com/"), 1, 1790000000000L,
                183748372L, 0L, 0.8f, "MUSIC", 500, 243000L,
                "夜曲", "周杰伦", null);
        Envelope envelope = Envelope.session(PacketType.PLAY, "a4d8", 1, ProtocolCodec.data(play));
        Envelope decoded = ProtocolCodec.decode(ProtocolCodec.encode(envelope));

        assertEquals(PacketType.PLAY, decoded.type());
        assertEquals("a4d8", decoded.session());
        assertEquals(1, decoded.revision());
        Packets.Play parsed = ProtocolCodec.data(decoded, Packets.Play.class);
        assertEquals(play, parsed);
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        String json = "{\"protocol\":1,\"type\":\"PING\",\"future\":123,"
                + "\"data\":{\"t0\":42,\"extra\":\"ignored\"}}";
        Envelope decoded = ProtocolCodec.decode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Packets.Ping ping = ProtocolCodec.data(decoded, Packets.Ping.class);
        assertEquals(42, ping.t0());
    }

    @Test
    void unknownTypeBecomesUnknown() throws Exception {
        Envelope decoded = ProtocolCodec.decode(
                "{\"protocol\":1,\"type\":\"FUTURE_PACKET\",\"data\":{}}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(PacketType.UNKNOWN, decoded.type());
    }

    @Test
    void rejectsUnsupportedProtocol() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(
                "{\"protocol\":99,\"type\":\"PING\",\"data\":{}}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsOversizedPacket() {
        byte[] big = new byte[ProtocolLimits.MAX_PACKET_BYTES + 1];
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(big));
    }

    @Test
    void validatesPlayLimits() throws Exception {
        Packets.Play ok = new Packets.Play("id", "netease", "1", "https://cdn.example.com/a.mp3",
                Map.of(), 1, 0, 0, 0, 1f, "MUSIC", 0, 0, null, null, null);
        ProtocolCodec.validatePlay(ok);

        Packets.Play tooLongUrl = new Packets.Play("id", "netease", "1",
                "https://cdn.example.com/" + "a".repeat(ProtocolLimits.MAX_URL_LENGTH),
                Map.of(), 1, 0, 0, 0, 1f, "MUSIC", 0, 0, null, null, null);
        assertThrows(ProtocolException.class, () -> ProtocolCodec.validatePlay(tooLongUrl));

        Packets.Play noUrl = new Packets.Play("id", "netease", "1", null,
                Map.of(), 1, 0, 0, 0, 1f, "MUSIC", 0, 0, null, null, null);
        assertThrows(ProtocolException.class, () -> ProtocolCodec.validatePlay(noUrl));
    }

    @Test
    void staleRevisionDetection() {
        Envelope control = Envelope.session(PacketType.SEEK, "s", 3, new com.google.gson.JsonObject());
        assertTrue(control.isStale(3));
        assertTrue(control.isStale(5));
        assertTrue(!control.isStale(2));
    }

    @Test
    void helloRoundTrip() throws Exception {
        Packets.Hello hello = new Packets.Hello("0.1.0", "26.2", "zh_cn",
                List.of("stream_playback", "seek", "pause"), List.of("mp3", "ogg-vorbis"));
        Envelope decoded = ProtocolCodec.decode(ProtocolCodec.encode(
                Envelope.of(PacketType.HELLO, ProtocolCodec.data(hello))));
        Packets.Hello parsed = ProtocolCodec.data(decoded, Packets.Hello.class);
        assertEquals(hello, parsed);
    }
}
