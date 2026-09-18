package com.mineaudio.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Chunk → Region 索引，避免每 tick 全量扫描。 */
public final class RegionIndex {

    private final Map<String, Map<Long, List<AudioRegion>>> byWorld = new HashMap<>();

    public void rebuild(Collection<AudioRegion> regions) {
        byWorld.clear();
        for (AudioRegion region : regions) {
            Map<Long, List<AudioRegion>> chunks =
                    byWorld.computeIfAbsent(region.world(), ignored -> new HashMap<>());
            RegionShape shape = region.shape();
            for (int chunkX = shape.minChunkX(); chunkX <= shape.maxChunkX(); chunkX++) {
                for (int chunkZ = shape.minChunkZ(); chunkZ <= shape.maxChunkZ(); chunkZ++) {
                    chunks.computeIfAbsent(key(chunkX, chunkZ), ignored -> new ArrayList<>()).add(region);
                }
            }
        }
    }

    public List<AudioRegion> candidates(String world, int chunkX, int chunkZ) {
        Map<Long, List<AudioRegion>> chunks = byWorld.get(world);
        if (chunks == null) return List.of();
        return chunks.getOrDefault(key(chunkX, chunkZ), List.of());
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }
}
