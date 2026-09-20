package com.mineaudio.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioMetadata;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackOptions;

import net.kyori.adventure.key.Key;

class MusicArbiterTest {

    private static AudioTrack track(String id) {
        return new AudioTrack(Key.key(id), AudioBus.MUSIC,
                new AudioSource.VanillaSound(Key.key("minecraft:music.menu")),
                PlaybackOptions.DEFAULT, AudioMetadata.EMPTY);
    }

    private static MusicIntent intent(String sourceId, MusicLayer layer, long sequence) {
        return new MusicIntent(sourceId, layer, sequence, track("mineaudio:test"),
                PlaybackOptions.DEFAULT, PlaybackOrigin.API, null);
    }

    @Test
    void higherLayerWins() {
        MusicArbiter arbiter = new MusicArbiter();
        arbiter.upsert(intent("world", MusicLayer.WORLD, 1));
        assertEquals("world", arbiter.selectWinner().sourceId());

        arbiter.upsert(intent("region", MusicLayer.REGION, 2));
        assertEquals("region", arbiter.selectWinner().sourceId());

        arbiter.upsert(intent("audience:a", MusicLayer.AUDIENCE, 3));
        assertEquals("audience:a", arbiter.selectWinner().sourceId());

        arbiter.upsert(intent("personal", MusicLayer.PERSONAL, 4));
        assertEquals("personal", arbiter.selectWinner().sourceId());
    }

    @Test
    void personalPreemptsEvenWhileAudienceStillPresent() {
        MusicArbiter arbiter = new MusicArbiter();
        arbiter.upsert(intent("audience:a", MusicLayer.AUDIENCE, 10));
        arbiter.upsert(intent("personal", MusicLayer.PERSONAL, 11));
        assertEquals("personal", arbiter.selectWinner().sourceId());
        // audience 仍存在，只是被遮蔽
        assertEquals(2, arbiter.intents().size());
    }

    @Test
    void removingPersonalRestoresAudience() {
        MusicArbiter arbiter = new MusicArbiter();
        arbiter.upsert(intent("audience:a", MusicLayer.AUDIENCE, 10));
        arbiter.upsert(intent("personal", MusicLayer.PERSONAL, 11));
        arbiter.remove("personal");
        assertEquals("audience:a", arbiter.selectWinner().sourceId());
    }

    @Test
    void upsertSameContentKeepsOriginalSequenceAndIntent() {
        MusicArbiter arbiter = new MusicArbiter();
        MusicIntent first = intent("audience:a", MusicLayer.AUDIENCE, 10);
        arbiter.upsert(first);
        // 相同内容、不同 sequence：refresh 不应反抢
        MusicIntent refreshed = intent("audience:a", MusicLayer.AUDIENCE, 99);
        MusicIntent previous = arbiter.upsert(refreshed);
        assertEquals(first, previous);
        assertEquals(10, arbiter.get("audience:a").sequence());
    }

    @Test
    void sameLayerLaterSequenceWins() {
        MusicArbiter arbiter = new MusicArbiter();
        arbiter.upsert(intent("audience:a", MusicLayer.AUDIENCE, 10));
        arbiter.upsert(intent("audience:b", MusicLayer.AUDIENCE, 11));
        assertEquals("audience:b", arbiter.selectWinner().sourceId());
    }

    @Test
    void removingNewerAudienceRestoresOlderAudience() {
        MusicArbiter arbiter = new MusicArbiter();
        arbiter.upsert(intent("audience:a", MusicLayer.AUDIENCE, 10));
        arbiter.upsert(intent("audience:b", MusicLayer.AUDIENCE, 11));
        arbiter.remove("audience:b");
        assertEquals("audience:a", arbiter.selectWinner().sourceId());
    }

    @Test
    void emptySelectionReturnsNull() {
        MusicArbiter arbiter = new MusicArbiter();
        assertNull(arbiter.selectWinner());
        arbiter.upsert(intent("personal", MusicLayer.PERSONAL, 1));
        arbiter.remove("personal");
        assertNull(arbiter.selectWinner());
    }

    @Test
    void intentComparisonIsNullSafe() {
        MusicIntent intent = intent("personal", MusicLayer.PERSONAL, 1);
        assertFalse(intent.sameContent(null));
        assertTrue(intent.compare(null) > 0);
    }
}
