package com.mineaudio.api.event;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.Nullable;

import com.mineaudio.api.AudioTrack;

/** 玩家离开音频区域。 */
public final class AudioRegionLeaveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String regionId;
    private final AudioTrack track;

    public AudioRegionLeaveEvent(Player player, String regionId, @Nullable AudioTrack track) {
        this.player = Objects.requireNonNull(player, "player");
        this.regionId = Objects.requireNonNull(regionId, "regionId");
        this.track = track;
    }

    public Player player() {
        return player;
    }

    public String regionId() {
        return regionId;
    }

    /** 区域当前生效的音乐曲目，可能为 null（仅环境音区域）。 */
    @Nullable
    public AudioTrack track() {
        return track;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
