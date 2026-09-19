package com.mineaudio.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimelineTest {

    @Test
    void positionAtStartEqualsOffset() {
        Timeline timeline = new Timeline(1_000, 0);
        assertEquals(0, timeline.positionAt(1_000));
    }

    @Test
    void positionAdvancesWithServerTime() {
        Timeline timeline = new Timeline(1_000, 0);
        assertEquals(500, timeline.positionAt(1_500));
    }

    @Test
    void lateJoinUsesFutureStartTime() {
        // 会话 1s 时起播；10s 后新玩家计划 1.2s 后起播 → 届时应为 10.2s
        Timeline timeline = new Timeline(1_000, 0);
        assertEquals(10_200, timeline.positionAt(11_200));
    }

    @Test
    void beforeStartClampsToOffset() {
        Timeline timeline = new Timeline(5_000, 300);
        assertEquals(300, timeline.positionAt(1_000));
    }

    @Test
    void offsetAppliesToPositions() {
        Timeline timeline = new Timeline(1_000, 9_000);
        assertEquals(9_500, timeline.positionAt(1_500));
    }
}
