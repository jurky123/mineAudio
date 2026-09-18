package com.mineaudio.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackState;
import com.mineaudio.playback.StatusAware;

class ResolvingPlaybackHandleTest {

    @Test
    void pendingNoteBeforeAttach() {
        ResolvingPlaybackHandle handle = new ResolvingPlaybackHandle();
        assertEquals(PlaybackState.PENDING, handle.state());
        assertEquals("正在解析音源…", handle.statusNote());
    }

    @Test
    void failedNoteCarriesKindAndMessage() {
        ResolvingPlaybackHandle handle = new ResolvingPlaybackHandle();
        handle.fail("NOT_PLAYABLE", "无版权");
        assertEquals(PlaybackState.ERROR, handle.state());
        assertTrue(handle.statusNote().contains("NOT_PLAYABLE"));
        assertTrue(handle.statusNote().contains("无版权"));
    }

    @Test
    void delegatesNoteAndPendingOperations() {
        ResolvingPlaybackHandle handle = new ResolvingPlaybackHandle();
        handle.pause();
        handle.seek(Duration.ofSeconds(30));
        handle.setVolume(0.5f);

        FakeHandle fake = new FakeHandle();
        handle.attach(fake);

        assertEquals(PlaybackState.PLAYING, handle.state());
        assertEquals("缓冲中…", handle.statusNote());
        assertTrue(fake.paused);
        assertEquals(Duration.ofSeconds(30), fake.seeked);
        assertEquals(0.5f, fake.volume);
    }

    @Test
    void cancelledBeforeAttachStopsDelegate() {
        ResolvingPlaybackHandle handle = new ResolvingPlaybackHandle();
        handle.stop();
        FakeHandle fake = new FakeHandle();
        handle.attach(fake);
        assertTrue(fake.stopped);
    }

    private static final class FakeHandle implements PlaybackHandle, StatusAware {

        private boolean paused;
        private boolean stopped;
        private Duration seeked;
        private float volume = 1f;

        @Override
        public UUID id() {
            return UUID.randomUUID();
        }

        @Override
        public PlaybackState state() {
            return PlaybackState.PLAYING;
        }

        @Override
        public boolean stop() {
            stopped = true;
            return true;
        }

        @Override
        public boolean pause() {
            paused = true;
            return true;
        }

        @Override
        public boolean resume() {
            return true;
        }

        @Override
        public boolean seek(Duration position) {
            seeked = position;
            return true;
        }

        @Override
        public boolean setVolume(float volume) {
            this.volume = volume;
            return true;
        }

        @Override
        public String statusNote() {
            return "缓冲中…";
        }
    }
}
