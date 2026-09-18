package com.mineaudio.api;

import java.util.Objects;

import net.kyori.adventure.key.Key;

/**
 * 完整曲目：资源包音乐、原版音乐或 NBS 曲目。
 * 语义音效请使用 {@link AudioCue}。
 */
public record AudioTrack(
        Key id,
        AudioBus bus,
        AudioSource primary,
        AudioSource fallback,
        PlaybackOptions options,
        AudioMetadata metadata) {

    public AudioTrack {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bus, "bus");
        Objects.requireNonNull(primary, "primary");
        options = options == null ? PlaybackOptions.DEFAULT : options;
        metadata = metadata == null ? AudioMetadata.EMPTY : metadata;
    }

    public AudioTrack(Key id, AudioBus bus, AudioSource primary, PlaybackOptions options, AudioMetadata metadata) {
        this(id, bus, primary, null, options, metadata);
    }
}
