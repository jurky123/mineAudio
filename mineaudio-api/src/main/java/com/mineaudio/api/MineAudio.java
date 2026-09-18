package com.mineaudio.api;

import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.key.Key;

/**
 * MineAudio 对外入口（稳定的业务插件 API）。
 * <p>
 * 用法（未安装 MineAudio 时 {@link MineAudioProvider#get()} 为 null，按既有模式回退原版实现）：
 * <pre>{@code
 * MineAudio audio = MineAudioProvider.get();
 * if (audio != null) {
 *     audio.playSfx(player, Key.key("mineuno", "card.play"));
 * }
 * }</pre>
 * 所有方法必须在主线程调用。
 */
public interface MineAudio {

    /** 向受众播放曲目。 */
    PlaybackHandle play(Audience audience, Key track);

    /** 向受众播放曲目，并覆盖播放参数。 */
    PlaybackHandle play(Audience audience, Key track, PlaybackOptions options);

    /** 对单个玩家播放语义音效。 */
    void playSfx(Player player, Key cue);

    /** 在位置播放语义音效，附近玩家按距离衰减可听。 */
    void playSfxAt(Location location, Key cue);

    /** 停止受众在指定 Bus 上的播放。 */
    void stop(Audience audience, AudioBus bus);

    /** 停止受众的所有播放（MUSIC / AMBIENT / SFX / UI）。 */
    void stopAll(Audience audience);

    boolean hasTrack(Key track);

    boolean hasCue(Key cue);

    /** 玩家当前可用的音频能力。 */
    AudioCapabilities capabilities(Player player);

    /**
     * 注册业务插件自带的 Cue，插件 disable 时应调用 {@link #unregisterCues(Plugin)}，
     * 重复注册同一 key 时以最后一次为准。
     */
    void registerCue(Plugin owner, AudioCue cue);

    /** 注销该插件注册的全部 Cue。 */
    void unregisterCues(Plugin owner);
}
