package com.mineaudio.playback;

/**
 * MUSIC 来源优先级：个人点播 &gt; 业务 API 受众 &gt; 区域 &gt; 世界。
 * 数值越大越优先；同层由 {@link MusicIntent#sequence()} 决定（后到者胜）。
 */
public enum MusicLayer {
    WORLD(50),
    REGION(100),
    AUDIENCE(200),
    PERSONAL(300);

    private final int priority;

    MusicLayer(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
