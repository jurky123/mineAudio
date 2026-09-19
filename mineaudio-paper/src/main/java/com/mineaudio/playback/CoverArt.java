package com.mineaudio.playback;

/** 句柄可选的封面地址（网易等音源解析结果），供 UI/HUD 展示。 */
public interface CoverArt {

    /** HTTPS 封面地址；无封面返回 null。 */
    String coverUrl();
}
