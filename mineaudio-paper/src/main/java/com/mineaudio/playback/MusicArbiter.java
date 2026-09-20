package com.mineaudio.playback;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单玩家 MUSIC 选曲器（纯 Java，可单测）：只决定“应该播放哪条 intent”，
 * 不接触 Backend / 通道 / Bukkit。
 *
 * <p>不变量：intent 描述“应该存在什么”，实际播放由 Orchestrator 的 reconcile 负责。
 * 选曲规则：先算最高优先级 intent，再判断是否需要切换；同层后到者（sequence 大）胜。</p>
 */
public final class MusicArbiter {

    private final Map<String, MusicIntent> intents = new LinkedHashMap<>();

    /** 新增/替换同 sourceId 的 intent；内容不变时保留原 sequence（避免 refresh 反抢）。 */
    public MusicIntent upsert(MusicIntent incoming) {
        MusicIntent previous = intents.get(incoming.sourceId());
        if (previous != null && previous.sameContent(incoming)) {
            return previous;
        }
        intents.put(incoming.sourceId(), incoming);
        return previous;
    }

    public MusicIntent remove(String sourceId) {
        return intents.remove(sourceId);
    }

    public boolean has(String sourceId) {
        return intents.containsKey(sourceId);
    }

    public MusicIntent get(String sourceId) {
        return intents.get(sourceId);
    }

    public Collection<MusicIntent> intents() {
        return intents.values();
    }

    /** 选出应播放的最高优先级 intent；无 intent 时返回 null。 */
    public MusicIntent selectWinner() {
        MusicIntent best = null;
        for (MusicIntent intent : intents.values()) {
            if (best == null || intent.compare(best) > 0) {
                best = intent;
            }
        }
        return best;
    }
}
