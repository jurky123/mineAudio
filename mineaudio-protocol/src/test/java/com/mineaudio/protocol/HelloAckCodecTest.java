package com.mineaudio.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class HelloAckCodecTest {

    @Test
    void helloAckRoundTrip() throws Exception {
        Packets.HelloAck ack = new Packets.HelloAck(
                "0.1.15", 1000, 3,
                new Packets.HelloAck.Sync(true, 10000, 150),
                new Packets.HelloAck.Firewall(true, true, 5),
                new Packets.HelloAck.Library("http://host:8767", "http://host:8767/mineaudio/upload", "tok"));
        byte[] bytes = ProtocolCodec.encode(Envelope.of(
                PacketType.HELLO_ACK, ProtocolCodec.data(ack)));
        Envelope decoded = ProtocolCodec.decode(bytes);
        Packets.HelloAck back = ProtocolCodec.data(decoded, Packets.HelloAck.class);
        assertNotNull(back);
        assertEquals(ack, back);
        assertNotNull(back.sync());
        assertEquals(10000, back.sync().pingIntervalMs());
        assertNotNull(back.firewall());
        assertEquals(5, back.firewall().maxRedirects());
        assertNotNull(back.library());
        assertEquals("tok", back.library().token());
    }
}
