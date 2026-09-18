package com.mineaudio.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class RegionHysteresisTest {

    private final AudioRegion region = new AudioRegion("r", "world",
            new RegionShape.Cuboid(0, 0, 0, 1, 1, 1), 0, null, List.of(), 0, 0, 2, 3);
    private final Function<String, AudioRegion> lookup = id -> region;

    @Test
    void enterAndExitRespectDelays() {
        RegionHysteresis hysteresis = new RegionHysteresis();
        Set<String> inside = Set.of("r");

        assertTrue(hysteresis.update(inside, lookup).isEmpty());
        assertEquals(List.of("r"), hysteresis.update(inside, lookup));
        assertTrue(hysteresis.isActive("r"));

        assertTrue(hysteresis.update(Set.of(), lookup).isEmpty());
        assertTrue(hysteresis.update(Set.of(), lookup).isEmpty());
        assertEquals(List.of("r"), hysteresis.update(Set.of(), lookup));
        assertFalse(hysteresis.isActive("r"));
    }

    @Test
    void boundaryFlickerDoesNotToggle() {
        RegionHysteresis hysteresis = new RegionHysteresis();
        hysteresis.update(Set.of("r"), lookup);
        hysteresis.update(Set.of("r"), lookup);
        assertTrue(hysteresis.isActive("r"));

        // 离开一 tick 后又回到区域内：离开计数被清除，不会误触发离开
        hysteresis.update(Set.of(), lookup);
        hysteresis.update(Set.of("r"), lookup);
        assertTrue(hysteresis.isActive("r"));

        // 再离开也未达到 exitDelay，仍保持激活
        hysteresis.update(Set.of(), lookup);
        hysteresis.update(Set.of("r"), lookup);
        assertTrue(hysteresis.isActive("r"));
    }

    @Test
    void disabledDelayActivatesImmediately() {
        AudioRegion immediate = new AudioRegion("i", "world",
                new RegionShape.Cuboid(0, 0, 0, 1, 1, 1), 0, null, List.of(), 0, 0, 0, 0);
        RegionHysteresis hysteresis = new RegionHysteresis();
        assertEquals(List.of("i"), hysteresis.update(Set.of("i"), id -> immediate));
    }
}
