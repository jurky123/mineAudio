package com.mineaudio.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.key.Key;

class RegionSelectionTest {

    private static final Key SPAWN = Key.key("mineaudio:spawn");
    private static final Key TAVERN = Key.key("mineaudio:tavern");
    private static final Key WORLD = Key.key("mineaudio:world");

    private static AudioRegion region(String id, int priority, Key music, List<Key> ambient) {
        return new AudioRegion(id, "world", new RegionShape.Cuboid(0, 0, 0, 1, 1, 1),
                priority, music, ambient, 0, 0, 0, 0);
    }

    @Test
    void musicTakesHighestPriority() {
        AudioRegion low = region("spawn", 10, SPAWN, List.of());
        AudioRegion high = region("tavern", 20, TAVERN, List.of());
        assertEquals(TAVERN, RegionSelection.selectMusic(List.of(low, high), null));
        assertEquals(TAVERN, RegionSelection.selectMusic(List.of(high, low), null));
    }

    @Test
    void musicFallsBackToWorldLayer() {
        assertEquals(WORLD, RegionSelection.selectMusic(List.of(), WORLD));
        assertNull(RegionSelection.selectMusic(
                List.of(region("no-music", 99, null, List.of(SPAWN))), null));
    }

    @Test
    void fromRegionDetectsOrigin() {
        AudioRegion region = region("tavern", 20, TAVERN, List.of());
        assertTrue(RegionSelection.fromRegion(List.of(region), TAVERN));
        assertFalse(RegionSelection.fromRegion(List.of(region), WORLD));
        assertFalse(RegionSelection.fromRegion(List.of(region), null));
    }

    @Test
    void ambientMergesByPriorityAndLimitsLayers() {
        AudioRegion low = region("spawn", 10, null, List.of(SPAWN));
        AudioRegion high = region("tavern", 20, null, List.of(TAVERN, WORLD));
        assertEquals(List.of(TAVERN, WORLD, SPAWN),
                RegionSelection.selectAmbient(List.of(low, high), 5));
        assertEquals(List.of(TAVERN, WORLD),
                RegionSelection.selectAmbient(List.of(low, high), 2));
        assertEquals(List.of(), RegionSelection.selectAmbient(List.of(low, high), 0));
    }

    @Test
    void ambientDeduplicates() {
        AudioRegion first = region("a", 10, null, List.of(SPAWN, TAVERN));
        AudioRegion second = region("b", 20, null, List.of(SPAWN));
        assertEquals(List.of(SPAWN, TAVERN),
                RegionSelection.selectAmbient(List.of(first, second), 5));
    }
}
