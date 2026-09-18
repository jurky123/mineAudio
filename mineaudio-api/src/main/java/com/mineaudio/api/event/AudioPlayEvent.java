package com.mineaudio.api.event;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import com.mineaudio.api.AudioTrack;

/** 某玩家即将开始播放曲目，可取消。 */
public final class AudioPlayEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final AudioTrack track;
    private boolean cancelled;

    public AudioPlayEvent(Player player, AudioTrack track) {
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
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
