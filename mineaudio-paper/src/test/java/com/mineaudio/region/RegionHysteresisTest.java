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

    private static AudioRegion region(int enterTicks, int exitTicks) {
        return new AudioRegion("r", "world",
                new RegionShape.Cuboid(0, 0, 0, 1, 1, 1), 0, null, List.of(), 0, 0, enterTicks, exitTicks);
    }

    @Test
    void enterAndExitRespectDelays() {
        RegionHysteresis hysteresis = new RegionHysteresis();
        Set<String> inside = Set.of("r");

        assertTrue(hysteresis.update(inside, lookup, 1).isEmpty());
        assertEquals(List.of("r"), hysteresis.update(inside, lookup, 1));
        assertTrue(hysteresis.isActive("r"));

        assertTrue(hysteresis.update(Set.of(), lookup, 1).isEmpty());
        assertTrue(hysteresis.update(Set.of(), lookup, 1).isEmpty());
        assertEquals(List.of("r"), hysteresis.update(Set.of(), lookup, 1));
        assertFalse(hysteresis.isActive("r"));
    }

    @Test
    void boundaryFlickerDoesNotToggle() {
        RegionHysteresis hysteresis = new RegionHysteresis();
        hysteresis.update(Set.of("r"), lookup, 1);
        hysteresis.update(Set.of("r"), lookup, 1);
        assertTrue(hysteresis.isActive("r"));

        // 离开一 tick 后又回到区域内：离开计数被清除，不会误触发离开
        hysteresis.update(Set.of(), lookup, 1);
        hysteresis.update(Set.of("r"), lookup, 1);
        assertTrue(hysteresis.isActive("r"));

        // 再离开也未达到 exitDelay，仍保持激活
        hysteresis.update(Set.of(), lookup, 1);
        hysteresis.update(Set.of("r"), lookup, 1);
        assertTrue(hysteresis.isActive("r"));
    }

    @Test
    void disabledDelayActivatesImmediately() {
        AudioRegion immediate = new AudioRegion("i", "world",
                new RegionShape.Cuboid(0, 0, 0, 1, 1, 1), 0, null, List.of(), 0, 0, 0, 0);
        RegionHysteresis hysteresis = new RegionHysteresis();
        assertEquals(List.of("i"), hysteresis.update(Set.of("i"), id -> immediate, 1));
    }

    /**
     * 回归：延迟以 tick 计，{@code update} 按“自上次检测经过的 tick 数”推进，
     * 不被检测间隔放大。enter=6（300ms）、检测间隔=10 时，一次 update(elapsed=10) 即应进入
     * （修复前按“每次调用+1”需 6 次检测≈60 tick≈3s）。
     */
    @Test
    void elapsedTicksHonorDelayNotCheckCount() {
        RegionHysteresis hysteresis = new RegionHysteresis();
        Function<String, AudioRegion> sixTen = id -> region(6, 10);

        assertEquals(List.of("r"), hysteresis.update(Set.of("r"), sixTen, 10));
        assertTrue(hysteresis.isActive("r"));

        // 离开延迟 10 tick：一次 elapsed=10 即离开
        assertEquals(List.of("r"), hysteresis.update(Set.of(), sixTen, 10));
        assertFalse(hysteresis.isActive("r"));
    }

    @Test
    void partialElapsedAccumulates() {
        RegionHysteresis hysteresis = new RegionHysteresis();
        Function<String, AudioRegion> ten = id -> region(10, 10);

        assertTrue(hysteresis.update(Set.of("r"), ten, 4).isEmpty());
        assertFalse(hysteresis.isActive("r"));
        assertTrue(hysteresis.update(Set.of("r"), ten, 4).isEmpty());
        assertFalse(hysteresis.isActive("r"));
        assertEquals(List.of("r"), hysteresis.update(Set.of("r"), ten, 4));
        assertTrue(hysteresis.isActive("r"));
    }

    /** 非 1 检测间隔下，边界反复进出仍不会误触发（离开计数在回到区域内时清零）。 */
    @Test
    void flickerWithNonUnitInterval() {
        RegionHysteresis hysteresis = new RegionHysteresis();
        Function<String, AudioRegion> sixTen = id -> region(6, 10);

        hysteresis.update(Set.of("r"), sixTen, 10);
        assertTrue(hysteresis.isActive("r"));

        // 离开 4 tick（<10）后又回来 1 tick：离开计数清零
        assertTrue(hysteresis.update(Set.of(), sixTen, 4).isEmpty());
        assertTrue(hysteresis.update(Set.of("r"), sixTen, 1).isEmpty());
        assertTrue(hysteresis.isActive("r"));

        // 累计离开 10 tick 才真正离开
        assertTrue(hysteresis.update(Set.of(), sixTen, 5).isEmpty());
        assertEquals(List.of("r"), hysteresis.update(Set.of(), sixTen, 5));
        assertFalse(hysteresis.isActive("r"));
    }

    @Test
    void elapsedAtLeastOneTick() {
        RegionHysteresis hysteresis = new RegionHysteresis();
        // elapsed<=0 视作 1，避免零推进导致状态卡住
        assertTrue(hysteresis.update(Set.of("r"), lookup, 0).isEmpty());
        assertEquals(List.of("r"), hysteresis.update(Set.of("r"), lookup, 0));
        assertTrue(hysteresis.isActive("r"));
    }
}
