package com.mineaudio.client.fabric.audio;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.sound.sampled.AudioFormat;

import com.mineaudio.client.decode.PcmRingBuffer;

import net.minecraft.client.sounds.AudioStream;

/**
 * 把解码器产出的 PCM 环形缓冲包装成 Minecraft 音频通道可消费的 {@link AudioStream}。
 * 读取在声音引擎线程执行：不能长阻塞，短暂饥饿时补一小段静音，流结束后返回 null。
 */
public final class PcmAudioStream implements AudioStream {

    private static final int BYTES_PER_MS = 48000 * 2 * 2 / 1000;
    private static final int WAIT_MS = 10;
    private static final int MAX_STARVE_MS = 60;
    private static final int SILENCE_MS = 20;

    private final PcmRingBuffer ring;
    private final AudioFormat format = new AudioFormat(48000f, 16, 2, true, false);
    private final Object lock = new Object();
    private volatile boolean ended;

    public PcmAudioStream(PcmRingBuffer ring) {
        this.ring = ring;
    }

    public void markEnded() {
        ended = true;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public void reset() {
        ended = false;
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        if (size <= 0) return null;
        byte[] chunk = new byte[size];
        int read = 0;
        long deadline = System.currentTimeMillis() + MAX_STARVE_MS;
        while (read == 0) {
            read = ring.read(chunk, 0, size);
            if (read > 0) break;
            if (ended) return null;
            synchronized (lock) {
                try {
                    lock.wait(WAIT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (System.currentTimeMillis() >= deadline) break;
        }

        ByteBuffer out = ByteBuffer.allocateDirect(size);
        if (read > 0) {
            out.put(chunk, 0, read);
        } else {
            // 解码暂未跟上：补 20ms 静音维持通道，避免被判定为播放结束
            out.put(new byte[Math.min(size, BYTES_PER_MS * SILENCE_MS)]);
        }
        out.flip();
        return out;
    }

    @Override
    public void close() {
        markEnded();
    }
}
