package com.mineaudio.api;

/**
 * 播放参数。fade 为 Backend best effort：PACK / Vanilla 无法对已播放的声音连续调音量，
 * 实际会退化为立即停止，可通过 {@link AudioCapabilities} 判断。
 */
public final class PlaybackOptions {

    public static final PlaybackOptions DEFAULT = new PlaybackOptions(1f, 1f, false, 0, 0);

    private final float volume;
    private final float pitch;
    private final boolean loop;
    private final int fadeInMs;
    private final int fadeOutMs;

    private PlaybackOptions(float volume, float pitch, boolean loop, int fadeInMs, int fadeOutMs) {
        this.volume = volume;
        this.pitch = pitch;
        this.loop = loop;
        this.fadeInMs = fadeInMs;
        this.fadeOutMs = fadeOutMs;
    }

    public float volume() {
        return volume;
    }

    public float pitch() {
        return pitch;
    }

    public boolean loop() {
        return loop;
    }

    public int fadeInMs() {
        return fadeInMs;
    }

    public int fadeOutMs() {
        return fadeOutMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().volume(volume).pitch(pitch).loop(loop)
                .fadeInMs(fadeInMs).fadeOutMs(fadeOutMs);
    }

    public static final class Builder {

        private float volume = 1f;
        private float pitch = 1f;
        private boolean loop;
        private int fadeInMs;
        private int fadeOutMs;

        /** 音量，通常 0~1；大于 1 会扩大可听范围（用于位置声半径近似）。 */
        public Builder volume(float volume) {
            if (volume < 0) throw new IllegalArgumentException("volume < 0");
            this.volume = volume;
            return this;
        }

        /** 音调，0.5~2 为常见范围。 */
        public Builder pitch(float pitch) {
            if (pitch <= 0) throw new IllegalArgumentException("pitch <= 0");
            this.pitch = pitch;
            return this;
        }

        public Builder loop(boolean loop) {
            this.loop = loop;
            return this;
        }

        public Builder fadeInMs(int fadeInMs) {
            if (fadeInMs < 0) throw new IllegalArgumentException("fadeInMs < 0");
            this.fadeInMs = fadeInMs;
            return this;
        }

        public Builder fadeOutMs(int fadeOutMs) {
            if (fadeOutMs < 0) throw new IllegalArgumentException("fadeOutMs < 0");
            this.fadeOutMs = fadeOutMs;
            return this;
        }

        public PlaybackOptions build() {
            return new PlaybackOptions(volume, pitch, loop, fadeInMs, fadeOutMs);
        }
    }
}
