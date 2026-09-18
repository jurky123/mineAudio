package com.mineaudio.playback;

/** 句柄的可选说明（供 UI/PAPI 展示解析与播放状态），实现类不必暴露内部细节。 */
public interface StatusAware {

    /** 展示用状态说明；没有可说的返回 null 或空串。 */
    String statusNote();
}
