package com.mineaudio.backend;

import java.time.Duration;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackState;

import net.kyori.adventure.sound.Sound;

/** 资源包/原版声音句柄：循环靠按时长重播；暂停 / Seek 不支持。 */
final class SoundPlaybackHandle implements PlaybackHandle, Runnable {

    private final UUID id = UUID.randomUUID();
    private final Player player;
    private final World world;
    private final double x;
    private final double y;
    private final double z;
    private final Sound sound;
    private PlaybackState state = PlaybackState.PLAYING;
    private BukkitTask task;

    private SoundPlaybackHandle(Plugin plugin, Player player, World world,
                                double x, double y, double z, Sound sound, long periodTicks) {
        this.player = player;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sound = sound;
        if (periodTicks > 0) {
            this.task = Bukkit.getScheduler().runTaskTimer(plugin, this, periodTicks, periodTicks);
        }
    }

    static SoundPlaybackHandle toPlayer(Plugin plugin, Player player, Sound sound, long periodTicks) {
        return new SoundPlaybackHandle(plugin, player, null, 0, 0, 0, sound, periodTicks);
    }

    static SoundPlaybackHandle at(Plugin plugin, World world, double x, double y, double z,
                                  Sound sound, long periodTicks) {
        return new SoundPlaybackHandle(plugin, null, world, x, y, z, sound, periodTicks);
    }

    @Override
    public void run() {
        if (state != PlaybackState.PLAYING) return;
        if (player != null) {
            if (player.isOnline()) {
                player.playSound(sound);
            } else {
                stop();
            }
        } else if (world != null) {
            world.playSound(sound, x, y, z);
        }
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public PlaybackState state() {
        return state;
    }

    @Override
    public boolean stop() {
        if (state != PlaybackState.PLAYING && state != PlaybackState.PAUSED) return false;
        cancel();
        if (player != null && player.isOnline()) {
            player.stopSound(sound);
        } else if (world != null) {
            world.stopSound(sound);
        }
        state = PlaybackState.STOPPED;
        return true;
    }

    @Override
    public boolean pause() {
        return false;
    }

    @Override
    public boolean resume() {
        return false;
    }

    @Override
    public boolean seek(Duration position) {
        return false;
    }

    private void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
