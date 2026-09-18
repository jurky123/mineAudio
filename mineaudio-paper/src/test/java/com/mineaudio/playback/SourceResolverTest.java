package com.mineaudio.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioMetadata;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackOptions;

import net.kyori.adventure.key.Key;

class SourceResolverTest {

    private static final AudioSource PACK = new AudioSource.PackSound(Key.key("mineaudio:music.spawn"));
    private static final AudioSource VANILLA = new AudioSource.VanillaSound(Key.key("minecraft:music.overworld.forest"));

    private static AudioTrack track(AudioSource primary, AudioSource fallback) {
        return new AudioTrack(Key.key("mineaudio:test"), AudioBus.MUSIC, primary, fallback,
                PlaybackOptions.DEFAULT, AudioMetadata.EMPTY);
    }

    @Test
    void prefersPrimaryWhenPackLoaded() {
        Optional<AudioSource> result = SourceResolver.resolve(track(PACK, VANILLA), true, source -> true);
        assertEquals(PACK, result.orElseThrow());
    }

    @Test
    void fallsBackWhenPackUnavailable() {
        Optional<AudioSource> result = SourceResolver.resolve(track(PACK, VANILLA), false, source -> true);
        assertEquals(VANILLA, result.orElseThrow());
    }

    @Test
    void emptyWhenNoFallbackAndPackUnavailable() {
        assertTrue(SourceResolver.resolve(track(PACK, null), false, source -> true).isEmpty());
    }

    @Test
    void fallsBackWhenBackendMissing() {
        Optional<AudioSource> result = SourceResolver.resolve(track(PACK, VANILLA), true,
                source -> source instanceof AudioSource.VanillaSound);
        assertEquals(VANILLA, result.orElseThrow());
    }

    @Test
    void vanillaAlwaysPlayable() {
        Optional<AudioSource> result = SourceResolver.resolve(track(VANILLA, null), false, source -> true);
        assertEquals(VANILLA, result.orElseThrow());
    }
}
