package com.mineaudio.region;

import java.util.List;

import net.kyori.adventure.key.Key;

/**
 * 音频区域：世界 + 形状 + 优先级 + 音乐/环境音。
 * 重叠时 MUSIC 取最高优先级，AMBIENT 允许并放但受最大层数限制。
 */
public record AudioRegion(
        String id,
        String world,
        RegionShape shape,
        int priority,
        Key music,
        List<Key> ambient,
        int fadeInMs,
        int fadeOutMs,
        int enterDelayTicks,
        int exitDelayTicks) {

    public AudioRegion {
        ambient = ambient == null ? List.of() : List.copyOf(ambient);
    }
}
