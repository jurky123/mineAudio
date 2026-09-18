package com.mineaudio.client.decode;

/**
 * PCM 环形缓冲：解码线程写、OpenAL 线程读；容量固定，读写互不阻塞（设计文档 §14）。
 */
public final class PcmRingBuffer {

    private final byte[] buffer;
    private int readIndex;
    private int writeIndex;
    private int available;

    public PcmRingBuffer(int capacityBytes) {
        this.buffer = new byte[Math.max(1, capacityBytes)];
    }

    public synchronized int capacity() {
        return buffer.length;
    }

    public synchronized int available() {
        return available;
    }

    public synchronized int free() {
        return buffer.length - available;
    }

    public synchronized void clear() {
        readIndex = 0;
        writeIndex = 0;
        available = 0;
    }

    /** 写入数据，返回实际写入字节数（空间不足时部分写入）。 */
    public synchronized int write(byte[] data, int offset, int length) {
        int toWrite = Math.min(length, buffer.length - available);
        for (int i = 0; i < toWrite; i++) {
            buffer[writeIndex] = data[offset + i];
            writeIndex = (writeIndex + 1) % buffer.length;
        }
        available += toWrite;
        return toWrite;
    }

    /** 读取数据，返回实际读取字节数。 */
    public synchronized int read(byte[] out, int offset, int length) {
        int toRead = Math.min(length, available);
        for (int i = 0; i < toRead; i++) {
            out[offset + i] = buffer[readIndex];
            readIndex = (readIndex + 1) % buffer.length;
        }
        available -= toRead;
        return toRead;
    }

    public synchronized void skip(int length) {
        int toSkip = Math.min(length, available);
        readIndex = (readIndex + toSkip) % buffer.length;
        available -= toSkip;
    }
}
