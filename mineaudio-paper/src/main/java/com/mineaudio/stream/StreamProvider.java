package com.mineaudio.stream;

import org.bukkit.entity.Player;

import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.PlaybackHandle;

/**
 * 流媒体播放器接入：MineAudio 只发播放控制与曲目引用，音频流由客户端直连音源。
 * 每个玩家一次播放对应一个独立 session/handle。
 */
public interface StreamProvider {

    String id();

    /** 该玩家是否可用；player 为 null 时表示查询全局可用性。 */
    boolean available(Player player);

    AudioCapabilities capabilities(Player player);

    PlaybackHandle play(Player player, StreamPlaybackRequest request);
}
