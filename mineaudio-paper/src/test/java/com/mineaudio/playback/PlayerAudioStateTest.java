package com.mineaudio.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioMetadata;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackOptions;

import net.kyori.adventure.key.Key;

class PlayerAudioStateTest {

    private final PlayerAudioState state = new PlayerAudioState(UUID.randomUUID());

    private static PlaybackSession session(String id, AudioBus bus) {
        AudioTrack track = new AudioTrack(Key.key(id), bus,
                new AudioSource.VanillaSound(Key.key("minecraft:ui.button.click")),
                PlaybackOptions.DEFAULT, AudioMetadata.EMPTY);
        return new PlaybackSession(UUID.randomUUID(), track, track.primary(),
                PlaybackOptions.DEFAULT, NoopPlaybackHandle.stopped(), "test", PlaybackOrigin.API);
    }

    @Test
    void musicIsSingleSession() {
        PlaybackSession first = session("mineaudio:one", AudioBus.MUSIC);
        PlaybackSession second = session("mineaudio:two", AudioBus.MUSIC);

        assertNull(state.replace(first));
        assertEquals(first, state.replace(second));
        assertEquals(second, state.music());
        assertEquals(1, state.sessions().size());
    }

    @Test
    void ambientAllowsMultipleAndReplacesSameTrack() {
        PlaybackSession first = session("mineaudio:rain", AudioBus.AMBIENT);
        PlaybackSession second = session("mineaudio:fireplace", AudioBus.AMBIENT);
        assertNull(state.replace(first));
        assertNull(state.replace(second));
        assertEquals(2, state.ambientCount());

        PlaybackSession firstAgain = session("mineaudio:rain", AudioBus.AMBIENT);
        assertEquals(first, state.replace(firstAgain));
        assertEquals(2, state.ambientCount());
    }

    @Test
    void sessionsOnFiltersByBus() {
        state.replace(session("mineaudio:music", AudioBus.MUSIC));
        state.replace(session("mineaudio:rain", AudioBus.AMBIENT));
        state.replace(session("mineaudio:fireplace", AudioBus.AMBIENT));

        assertEquals(1, state.sessionsOn(AudioBus.MUSIC).size());
        assertEquals(2, state.sessionsOn(AudioBus.AMBIENT).size());
        assertEquals(0, state.sessionsOn(AudioBus.SFX).size());
    }

    @Test
    void removeDropsSession() {
        PlaybackSession music = session("mineaudio:music", AudioBus.MUSIC);
        state.replace(music);
        assertTrue(state.remove(music));
        assertTrue(state.sessions().isEmpty());
    }

    @Test
    void sfxSessionsAreNotTracked() {
        state.replace(session("mineaudio:ding", AudioBus.SFX));
        assertTrue(state.sessions().isEmpty());
    }
}
