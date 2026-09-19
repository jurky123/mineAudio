package com.mineaudio.client.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaybackClockTest {

    @Test
    void loadingToPlayingAnchorsAtOutput() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(180_000);
        assertEquals(PlaybackClock.State.LOADING, clock.state());
        clock.onOutputStarted(12_000);
        assertEquals(PlaybackClock.State.PLAYING, clock.state());
        assertTrue(clock.positionMs() >= 12_000);
        assertTrue(clock.playing());
    }

    @Test
    void pauseIsIdempotentAndFreezesPosition() throws Exception {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(180_000);
        clock.onOutputStarted(5_000);
        Thread.sleep(30);
        clock.onPause();
        long frozen = clock.positionMs();
        Thread.sleep(30);
        assertEquals(frozen, clock.positionMs());
        clock.onPause(); // 重复暂停不改状态
        assertEquals(PlaybackClock.State.PAUSED, clock.state());
        assertEquals(frozen, clock.positionMs());
    }

    @Test
    void seekWhilePlayingGoesThroughBuffering() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(180_000);
        clock.onOutputStarted(0);
        clock.onSeekRequested(90_000);
        assertEquals(PlaybackClock.State.BUFFERING, clock.state());
        assertEquals(90_000, clock.positionMs());
        clock.onSeekApplied(90_000);
        assertEquals(PlaybackClock.State.PLAYING, clock.state());
        assertTrue(clock.positionMs() >= 90_000);
    }

    @Test
    void seekWhilePausedStaysPaused() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(180_000);
        clock.onOutputStarted(0);
        clock.onPause();
        clock.onSeekRequested(60_000);
        assertEquals(PlaybackClock.State.PAUSED, clock.state());
        clock.onSeekApplied(60_000);
        assertEquals(PlaybackClock.State.PAUSED, clock.state());
        assertFalse(clock.playing());
        assertEquals(60_000, clock.positionMs());
    }

    @Test
    void resumeGoesThroughBufferingUntilOutputRestarts() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(180_000);
        clock.onOutputStarted(0);
        clock.onPause();
        clock.onResume();
        assertEquals(PlaybackClock.State.BUFFERING, clock.state());
        assertFalse(clock.playing());
        clock.onSeekApplied(30_000);
        assertEquals(PlaybackClock.State.PLAYING, clock.state());
    }

    @Test
    void underrunFreezesAndRecovers() throws Exception {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(180_000);
        clock.onOutputStarted(1_000);
        Thread.sleep(20);
        clock.onUnderrun();
        long frozen = clock.positionMs();
        Thread.sleep(20);
        assertEquals(frozen, clock.positionMs());
        assertEquals(PlaybackClock.State.BUFFERING, clock.state());
        clock.onOutputStarted(frozen);
        assertEquals(PlaybackClock.State.PLAYING, clock.state());
    }

    @Test
    void drainingThenFinished() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(10_000);
        clock.onOutputStarted(9_000);
        clock.onDecoderEnded();
        assertEquals(PlaybackClock.State.DRAINING, clock.state());
        clock.onDrained();
        assertEquals(PlaybackClock.State.FINISHED, clock.state());
        assertTrue(clock.positionMs() >= 9_000 && clock.positionMs() <= 10_000); // 冻结且钳制到时长
        clock.onDrained(); // 幂等
        assertEquals(PlaybackClock.State.FINISHED, clock.state());
    }

    @Test
    void errorFreezesAndBlocksFurtherTransitions() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(180_000);
        clock.onOutputStarted(0);
        clock.onError("TRACK_ERROR");
        assertEquals(PlaybackClock.State.ERROR, clock.state());
        assertEquals("TRACK_ERROR", clock.errorCode());
        clock.onOutputStarted(50_000); // 不应复活
        assertEquals(PlaybackClock.State.ERROR, clock.state());
    }

    @Test
    void positionClampedToDuration() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(1_000);
        clock.onOutputStarted(999);
        assertTrue(clock.positionMs() <= 1_000);
    }
}
