package com.mineaudio.client.decode;

/**
 * 音频解码器：把流媒体链接解码为 48kHz/16bit/立体声 PCM，经 {@link Sink} 交给播放层。
 * 实现需自行管理解码线程；所有回调都可能在解码线程触发。
 */
public interface AudioDecoder extends AutoCloseable {

    interface Sink {
        void onPcm(byte[] data, int length, long timecodeMs);

        void onEnded();

        void onError(String code, String message);
    }

    void start(String url, long startPositionMs, Sink sink);

    void setPaused(boolean paused);

    void seek(long positionMs);

    /** 当前音轨是否支持 seek（诊断用）。 */
    default boolean seekable() {
        return false;
    }

    long positionMs();

    long durationMs();

    boolean finished();

    @Override
    void close();
}
