package com.mineaudio.api;

import java.util.Objects;

import net.kyori.adventure.key.Key;

/**
 * 语义音效事件，如 {@code mineuno:card.play}。
 * 业务插件只认 key，素材与 fallback 由 MineAudio 负责解析。
 */
public record AudioCue(
        Key id,
        AudioBus bus,
        AudioSource primary,
        AudioSource fallback,
        PlaybackOptions options) {

    public AudioCue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bus, "bus");
        Objects.requireNonNull(primary, "primary");
        options = options == null ? PlaybackOptions.DEFAULT : options;
    }

    public AudioCue(Key id, AudioBus bus, AudioSource primary) {
        this(id, bus, primary, null, PlaybackOptions.DEFAULT);
    }
}
