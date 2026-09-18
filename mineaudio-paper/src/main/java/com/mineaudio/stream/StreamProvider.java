package com.mineaudio.stream;

import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.AudioSource;

/**
 * 流媒体播放器接入：MineAudio 只发播放控制与歌曲引用，音频流由客户端直连音源。
 * V1 提供 MoeMusic 命令桥，后续可加 Concerto。
 */
public interface StreamProvider {

    String id();

    boolean available();

    AudioCapabilities capabilities();

    /** 开始播放（MoeMusic 服务端队列为全服共享，无多会话能力）。 */
    void play(AudioSource.Stream source);

    void stop();

    boolean pause();

    boolean resume();
}
