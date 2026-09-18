package com.mineaudio.emitter;

import net.kyori.adventure.key.Key;

/**
 * 发声点：绑定世界坐标的曲目，radius 为可听半径（PACK 用音量近似，NBS 用 distance）。
 * 权威数据在 emitters.yml，方块 PDC 只能作为辅助。
 */
public record AudioEmitter(
        String id,
        String world,
        double x,
        double y,
        double z,
        Key track,
        double radius,
        Trigger trigger,
        boolean loop) {
}
