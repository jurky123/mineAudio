package com.mineaudio.api.event;

import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import com.mineaudio.api.AudioTrack;

/** Emitter 开始播放（红石开启 / 命令 / 交互触发）。 */
public final class AudioEmitterStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String emitterId;
    private final AudioTrack track;
    private final Location location;

    public AudioEmitterStartEvent(String emitterId, AudioTrack track, Location location) {
        this.emitterId = Objects.requireNonNull(emitterId, "emitterId");
        this.track = Objects.requireNonNull(track, "track");
        this.location = Objects.requireNonNull(location, "location").clone();
    }

    public String emitterId() {
        return emitterId;
    }

    public AudioTrack track() {
        return track;
    }

    public Location location() {
        return location.clone();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
