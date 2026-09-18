package com.mineaudio.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import com.mineaudio.protocol.Packets;

/**
 * 客户端播放状态缓存（服务端权威展示数据）。
 * 显示位置按收到时间外推（仅 PLAYING 时增长），PAPI/MineUI 只读这里，绝不查询客户端。
 */
public final class ClientPlaybackStateCache {

    public record Snapshot(
            UUID playerId,
            String sessionId,
            String state,
            long positionMs,
            long durationMs,
            long bufferedMs,
            double bufferRatio,
            long rttMs,
            long driftMs,
            String errorCode,
            String errorMessage,
            int revision,
            long receivedAtMs) {

        public boolean playing() {
            return "PLAYING".equals(state);
        }

        /** 展示用位置：PLAYING 时按收到时间外推。 */
        public long displayPositionMs() {
            if (!playing()) return positionMs;
            return positionMs + Math.max(0, System.currentTimeMillis() - receivedAtMs);
        }
    }

    private final Map<UUID, Map<String, Snapshot>> byPlayer = new HashMap<>();

    public void update(Player player, String sessionId, int revision, Packets.State state) {
        Snapshot snapshot = new Snapshot(
                player.getUniqueId(),
                sessionId,
                state.state(),
                state.positionMs(),
                state.durationMs(),
                state.bufferedMs(),
                state.bufferRatio(),
                state.rttMs(),
                state.driftMs(),
                state.error() == null ? null : state.error().code(),
                state.error() == null ? null : state.error().message(),
                revision,
                System.currentTimeMillis());
        byPlayer.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>()).put(sessionId, snapshot);
    }

    public Snapshot snapshot(Player player, String sessionId) {
        Map<String, Snapshot> sessions = byPlayer.get(player.getUniqueId());
        return sessions == null ? null : sessions.get(sessionId);
    }

    /** 该玩家最近更新的会话快照（用于 PAPI/计分板单行展示）。 */
    public Snapshot latest(Player player) {
        Map<String, Snapshot> sessions = byPlayer.get(player.getUniqueId());
        if (sessions == null || sessions.isEmpty()) return null;
        Snapshot latest = null;
        for (Snapshot snapshot : sessions.values()) {
            if (latest == null || snapshot.receivedAtMs() > latest.receivedAtMs()) {
                latest = snapshot;
            }
        }
        return latest;
    }

    public List<Snapshot> snapshots(Player player) {
        Map<String, Snapshot> sessions = byPlayer.get(player.getUniqueId());
        return sessions == null ? List.of() : List.copyOf(sessions.values());
    }

    public void remove(Player player, String sessionId) {
        Map<String, Snapshot> sessions = byPlayer.get(player.getUniqueId());
        if (sessions != null) sessions.remove(sessionId);
    }

    public void clear(Player player) {
        byPlayer.remove(player.getUniqueId());
    }
}
