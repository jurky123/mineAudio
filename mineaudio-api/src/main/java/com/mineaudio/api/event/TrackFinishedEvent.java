package com.mineaudio.api.event;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import com.mineaudio.api.AudioTrack;

/** 曲目自然播放结束（未循环或循环已停止）。 */
public final class TrackFinishedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final AudioTrack track;

    public TrackFinishedEvent(Player player, AudioTrack track) {
        this.player = Objects.requireNonNull(player, "player");
        this.track = Objects.requireNonNull(track, "track");
    }

    public Player player() {
        return player;
    }

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
