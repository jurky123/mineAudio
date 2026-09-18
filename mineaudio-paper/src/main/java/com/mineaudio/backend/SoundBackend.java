package com.mineaudio.backend;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackOptions;
import com.mineaudio.playback.NoopPlaybackHandle;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

/**
 * 资源包 / 原版声音 Backend。两者播放机制一致，区别只在来源语义与可用性判断
 * （PACK 需要玩家资源包已加载，由 Orchestrator 在选源时处理）。
 */
public final class SoundBackend implements AudioBackend {

    private static final AudioCapabilities CAPABILITIES =
            new AudioCapabilities(true, false, false, false, false, true,
                    true, false, true, true, false, false);

    private final Plugin plugin;

    public SoundBackend(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "sound";
    }

    @Override
    public boolean supports(AudioSource source) {
        return source instanceof AudioSource.PackSound || source instanceof AudioSource.VanillaSound;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public AudioCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public PlaybackHandle play(Player player, AudioTrack track, AudioSource source, PlaybackOptions options) {
        Sound sound = soundFor(track, source, options);
        player.playSound(sound);
        return SoundPlaybackHandle.toPlayer(plugin, player, sound, loopPeriod(track, options));
    }

    @Override
    public PlaybackHandle playAt(Location location, AudioTrack track, AudioSource source, PlaybackOptions options,
                                 double radius) {
        World world = location.getWorld();
        if (world == null) return NoopPlaybackHandle.stopped();
        Sound sound = soundFor(track, volumeFor(options, radius), options.pitch(), source);
        world.playSound(sound, location.x(), location.y(), location.z());
        return SoundPlaybackHandle.at(plugin, world, location.x(), location.y(), location.z(),
                sound, loopPeriod(track, options));
    }

    private static Sound soundFor(AudioTrack track, AudioSource source, PlaybackOptions options) {
        return soundFor(track, options.volume(), options.pitch(), source);
    }

    private static Sound soundFor(AudioTrack track, float volume, float pitch, AudioSource source) {
        Key key = switch (source) {
            case AudioSource.PackSound pack -> pack.sound();
            case AudioSource.VanillaSound vanilla -> vanilla.sound();
            case AudioSource.Nbs ignored -> throw new IllegalArgumentException("SoundBackend 不支持 NBS");
            case AudioSource.Stream ignored -> throw new IllegalArgumentException("SoundBackend 不支持流媒体");
        };
        return Sound.sound(key, sourceOf(track.bus()), volume, pitch);
    }

    /**
     * 资源包声音没有 radius 参数，用音量近似可听范围（衰减距离默认 16 格）：
     * radius / 16 作为音量倍数，clamp 到 [0.1, 4]。
     */
    private static float volumeFor(PlaybackOptions options, double radius) {
        if (radius <= 0) return options.volume();
        float scale = (float) Math.max(0.1, Math.min(4.0, radius / 16.0));
        return options.volume() * scale;
    }

    /** 用原版音量分类承载四种 Bus，便于玩家用声音设置单独调节。 */
    private static Sound.Source sourceOf(AudioBus bus) {
        return switch (bus) {
            case MUSIC -> Sound.Source.MUSIC;
            case AMBIENT -> Sound.Source.AMBIENT;
            case SFX, UI -> Sound.Source.MASTER;
        };
    }

    /** 资源包/原版声音无法真正循环，按时长重播；时长未知则不循环。 */
    private static long loopPeriod(AudioTrack track, PlaybackOptions options) {
        if (!options.loop()) return 0;
        long duration = track.metadata().durationMs();
        return duration <= 0 ? 0 : Math.max(1, duration / 50);
    }
}
