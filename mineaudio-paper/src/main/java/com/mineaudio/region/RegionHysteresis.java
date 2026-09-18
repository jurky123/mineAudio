package com.mineaudio.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 每玩家区域进入/离开迟滞：站在边界反复进出时不会立刻切歌。
 * 每次 tick 调用 {@link #update}，达到延迟后才改变激活状态。
 */
public final class RegionHysteresis {

    private final Map<String, Integer> enterTicks = new HashMap<>();
    private final Map<String, Integer> exitTicks = new HashMap<>();
    private final Set<String> active = new LinkedHashSet<>();

    /** 返回本次状态发生变化的区域 ID（进入或离开）。 */
    public List<String> update(Set<String> inside, Function<String, AudioRegion> lookup) {
        List<String> changes = new ArrayList<>();
        for (String id : inside) {
            if (active.contains(id)) {
                enterTicks.remove(id);
                exitTicks.remove(id);
                continue;
            }
            int ticks = enterTicks.merge(id, 1, Integer::sum);
            AudioRegion region = lookup.apply(id);
            int delay = region == null ? 0 : region.enterDelayTicks();
            if (ticks >= delay) {
                active.add(id);
                enterTicks.remove(id);
                changes.add(id);
            }
        }
        for (String id : new ArrayList<>(active)) {
            if (inside.contains(id)) continue;
            int ticks = exitTicks.merge(id, 1, Integer::sum);
            AudioRegion region = lookup.apply(id);
            int delay = region == null ? 0 : region.exitDelayTicks();
            if (ticks >= delay) {
                active.remove(id);
                exitTicks.remove(id);
                changes.add(id);
            }
        }
        enterTicks.keySet().removeIf(id -> !inside.contains(id) && !active.contains(id));
        return changes;
    }

    public boolean isActive(String regionId) {
        return active.contains(regionId);
    }

    public Set<String> active() {
        return java.util.Collections.unmodifiableSet(active);
    }

    public void clear() {
        enterTicks.clear();
        exitTicks.clear();
        active.clear();
    }
}
