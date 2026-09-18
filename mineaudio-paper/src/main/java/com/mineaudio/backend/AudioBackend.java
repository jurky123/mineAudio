package com.mineaudio.backend;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackOptions;

/** 一种播放方式（资源包/原版声音、NBS、流媒体）。Backend 只负责 HOW。 */
public interface AudioBackend {

    String id();

    boolean supports(AudioSource source);

    boolean available();

    AudioCapabilities capabilities();

    /** 对单个玩家播放。 */
    PlaybackHandle play(Player player, AudioTrack track, AudioSource source, PlaybackOptions options);

    /** 在位置播放，附近玩家按距离衰减可听；radius <= 0 时使用 Backend 默认距离。 */
    PlaybackHandle playAt(Location location, AudioTrack track, AudioSource source, PlaybackOptions options,
                          double radius);

    /** 该 Backend 已知的实际曲目时长（毫秒），未知返回 -1；Orchestrator 用于安排结束事件。 */
    default long durationMs(AudioTrack track, AudioSource source) {
        return -1;
    }
}
