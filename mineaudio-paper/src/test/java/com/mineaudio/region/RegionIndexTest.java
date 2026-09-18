package com.mineaudio.region;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class RegionIndexTest {

    @Test
    void indexesCuboidByChunk() {
        AudioRegion region = new AudioRegion("tavern", "world",
                new RegionShape.Cuboid(0, 0, 0, 31, 255, 31), 0, null, List.of(), 0, 0, 0, 0);
        RegionIndex index = new RegionIndex();
        index.rebuild(List.of(region));

        assertEquals(1, index.candidates("world", 0, 0).size());
        assertEquals(1, index.candidates("world", 1, 1).size());
        assertEquals(0, index.candidates("world", 2, 0).size());
        assertEquals(0, index.candidates("other", 0, 0).size());
    }

    @Test
    void sphereRegistersBoundingBoxChunks() {
        AudioRegion region = new AudioRegion("orb", "world",
                new RegionShape.Sphere(0, 64, 0, 17), 10, null, List.of(), 0, 0, 0, 0);
        RegionIndex index = new RegionIndex();
        index.rebuild(List.of(region));

        assertEquals(1, index.candidates("world", -2, -2).size());
        assertEquals(1, index.candidates("world", 1, 1).size());
        assertEquals(0, index.candidates("world", 2, 0).size());
    }
}
