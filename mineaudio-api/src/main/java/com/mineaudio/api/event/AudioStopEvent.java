package com.mineaudio.api.event;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import com.mineaudio.api.AudioTrack;

/** 某玩家的曲目被主动停止（自然播完见 {@link TrackFinishedEvent}）。 */
public final class AudioStopEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final AudioTrack track;

    public AudioStopEvent(Player player, AudioTrack track) {
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
