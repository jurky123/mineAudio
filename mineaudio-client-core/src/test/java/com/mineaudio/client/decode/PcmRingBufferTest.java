package com.mineaudio.client.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PcmRingBufferTest {

    @Test
    void writeAndRead() {
        PcmRingBuffer buffer = new PcmRingBuffer(8);
        assertEquals(4, buffer.write(new byte[]{1, 2, 3, 4}, 0, 4));
        assertEquals(4, buffer.available());

        byte[] out = new byte[4];
        assertEquals(4, buffer.read(out, 0, 4));
        assertEquals(1, out[0]);
        assertEquals(4, out[3]);
        assertEquals(0, buffer.available());
    }

    @Test
    void wrapsAround() {
        PcmRingBuffer buffer = new PcmRingBuffer(4);
        buffer.write(new byte[]{1, 2, 3}, 0, 3);
        byte[] first = new byte[3];
        buffer.read(first, 0, 3);

        buffer.write(new byte[]{4, 5, 6, 7}, 0, 4);
        byte[] out = new byte[4];
        assertEquals(4, buffer.read(out, 0, 4));
        assertEquals(4, out[0]);
        assertEquals(7, out[3]);
    }

    @Test
    void partialWriteWhenFull() {
        PcmRingBuffer buffer = new PcmRingBuffer(4);
        assertEquals(4, buffer.write(new byte[]{1, 2, 3, 4}, 0, 4));
        assertEquals(0, buffer.write(new byte[]{5}, 0, 1));
        assertEquals(4, buffer.available());
    }

    @Test
    void clearResets() {
        PcmRingBuffer buffer = new PcmRingBuffer(4);
        buffer.write(new byte[]{1, 2}, 0, 2);
        buffer.clear();
        assertEquals(0, buffer.available());
        assertEquals(4, buffer.free());
    }
}
