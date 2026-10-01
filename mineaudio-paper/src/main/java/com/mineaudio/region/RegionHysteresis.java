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
 *
 * <p>延迟以 <b>server tick</b> 计；每次检测（默认每 {@code region-check-interval-ticks} tick）调用
 * {@link #update} 时把“自上次检测以来经过的 tick 数”传入，因此检测间隔不改变实际延迟时长。
 * 由于是轮询采样，状态切换发生在 {@code [delay, delay + interval)} tick 之间（检测粒度误差）；
 * elapsed 用实际 tick 差衡量，服务器卡顿（tick 变慢）时按真实经过的 tick 推进。</p>
 */
public final class RegionHysteresis {

    private final Map<String, Integer> enterTicks = new HashMap<>();
    private final Map<String, Integer> exitTicks = new HashMap<>();
    private final Set<String> active = new LinkedHashSet<>();

    /**
     * 返回本次状态发生变化的区域 ID（进入或离开）。
     *
     * @param elapsedTicks 自上次检测以来经过的 server tick 数（&gt;=1）
     */
    public List<String> update(Set<String> inside, Function<String, AudioRegion> lookup, int elapsedTicks) {
        int elapsed = Math.max(1, elapsedTicks);
        List<String> changes = new ArrayList<>();
        for (String id : inside) {
            if (active.contains(id)) {
                enterTicks.remove(id);
                exitTicks.remove(id);
                continue;
            }
            int ticks = enterTicks.merge(id, elapsed, Integer::sum);
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
            int ticks = exitTicks.merge(id, elapsed, Integer::sum);
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
