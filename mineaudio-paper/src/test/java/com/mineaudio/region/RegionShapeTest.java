package com.mineaudio.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegionShapeTest {

    @Test
    void cuboidContains() {
        RegionShape.Cuboid cuboid = new RegionShape.Cuboid(0, 60, 0, 10, 70, 10);
        assertTrue(cuboid.contains(5, 65, 5));
        assertTrue(cuboid.contains(0, 60, 0));
        assertFalse(cuboid.contains(11, 65, 5));
        assertFalse(cuboid.contains(5, 59, 5));
    }

    @Test
    void cuboidNormalizesCorners() {
        RegionShape.Cuboid cuboid = new RegionShape.Cuboid(10, 70, 10, 0, 60, 0);
        assertEquals(0, cuboid.minX());
        assertEquals(70, cuboid.maxY());
        assertTrue(cuboid.contains(5, 65, 5));
    }

    @Test
    void cuboidChunkBounds() {
        RegionShape.Cuboid cuboid = new RegionShape.Cuboid(-20, 0, -20, 20, 0, 20);
        assertEquals(-2, cuboid.minChunkX());
        assertEquals(1, cuboid.maxChunkX());
        assertEquals(-2, cuboid.minChunkZ());
        assertEquals(1, cuboid.maxChunkZ());
    }

    @Test
    void sphereContains() {
        RegionShape.Sphere sphere = new RegionShape.Sphere(0, 64, 0, 5);
        assertTrue(sphere.contains(3, 64, 4));
        assertFalse(sphere.contains(4, 64, 4));
    }

    @Test
    void sphereChunkBounds() {
        RegionShape.Sphere sphere = new RegionShape.Sphere(0, 64, 0, 17);
        assertEquals(-2, sphere.minChunkX());
        assertEquals(1, sphere.maxChunkX());
    }
}
