package com.mineaudio.region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import net.kyori.adventure.key.Key;

/** 区域栈仲裁：MUSIC 取最高优先级，AMBIENT 按优先级并放并限制层数。 */
public final class RegionSelection {

    private RegionSelection() {
    }

    public static Key selectMusic(List<AudioRegion> active, Key worldMusic) {
        AudioRegion best = null;
        for (AudioRegion region : active) {
            if (region.music() == null) continue;
            if (best == null || region.priority() > best.priority()) {
                best = region;
            }
        }
        return best == null ? worldMusic : best.music();
    }

    public static boolean fromRegion(List<AudioRegion> active, Key music) {
        if (music == null) return false;
        for (AudioRegion region : active) {
            if (music.equals(region.music())) return true;
        }
        return false;
    }

    public static List<Key> selectAmbient(List<AudioRegion> active, int maxLayers) {
        if (maxLayers <= 0) return List.of();
        List<AudioRegion> sorted = new ArrayList<>(active);
        sorted.sort(Comparator.comparingInt(AudioRegion::priority).reversed());
        LinkedHashSet<Key> keys = new LinkedHashSet<>();
        for (AudioRegion region : sorted) {
            for (Key key : region.ambient()) {
                if (keys.size() >= maxLayers) return List.copyOf(keys);
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }
}
