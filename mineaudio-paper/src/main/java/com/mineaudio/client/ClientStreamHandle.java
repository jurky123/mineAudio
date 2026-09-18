package com.mineaudio.client;

import java.time.Duration;
import java.util.UUID;

import org.bukkit.entity.Player;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackState;
import com.mineaudio.playback.StatusAware;
import com.mineaudio.protocol.Envelope;
import com.mineaudio.protocol.PacketType;
import com.mineaudio.protocol.Packets;
import com.mineaudio.protocol.ProtocolCodec;

/** 单个玩家的客户端流会话句柄：指令下发 + 状态读服务端缓存。 */
final class ClientStreamHandle implements PlaybackHandle, StatusAware {

    private final MineAudioPlugin plugin;
    private final ClientProtocolService protocol;
    private final Player player;
    private final UUID sessionId;
    private int revision = 1;
    private PlaybackState localState = PlaybackState.PENDING;

    ClientStreamHandle(MineAudioPlugin plugin, ClientProtocolService protocol, Player player, UUID sessionId) {
        this.plugin = plugin;
        this.protocol = protocol;
        this.player = player;
        this.sessionId = sessionId;
    }

    @Override
    public UUID id() {
        return sessionId;
    }

    @Override
    public PlaybackState state() {
        ClientPlaybackStateCache.Snapshot snapshot = protocol.stateCache().snapshot(player, sessionId.toString());
        if (snapshot == null) return localState;
        try {
            return PlaybackState.valueOf(snapshot.state());
        } catch (IllegalArgumentException e) {
            return localState;
        }
    }

    @Override
    public boolean stop() {
        send(PacketType.STOP, new Packets.Stop("server"));
        localState = PlaybackState.STOPPED;
        protocol.stateCache().remove(player, sessionId.toString());
        return true;
    }

    @Override
    public boolean pause() {
        if (!player.isOnline()) return false;
        send(PacketType.PAUSE, new Packets.Pause(currentPositionMs(), ClientProtocolService.monotonicMs()));
        localState = PlaybackState.PAUSED;
        return true;
    }

    @Override
    public boolean resume() {
        if (!player.isOnline()) return false;
        send(PacketType.RESUME, new Packets.Resume(currentPositionMs(), ClientProtocolService.monotonicMs()));
        localState = PlaybackState.PLAYING;
        return true;
    }

    @Override
    public boolean seek(Duration position) {
        if (!player.isOnline()) return false;
        send(PacketType.SEEK, new Packets.Seek(Math.max(0, position.toMillis()),
                ClientProtocolService.monotonicMs()));
        return true;
    }

    @Override
    public boolean setVolume(float volume) {
        if (!player.isOnline()) return false;
        send(PacketType.VOLUME, new Packets.Volume(Math.max(0f, Math.min(1f, volume)), 300));
        return true;
    }

    @Override
    public String statusNote() {
        ClientPlaybackStateCache.Snapshot snapshot = protocol.stateCache().snapshot(player, sessionId.toString());
        if (snapshot == null) return null;
        if (snapshot.errorCode() != null) {
            return "播放错误（" + snapshot.errorCode() + "）：" + snapshot.errorMessage();
        }
        return switch (snapshot.state()) {
            case "BUFFERING" -> "缓冲中…";
            case "PENDING" -> "等待客户端起播…";
            default -> null;
        };
    }

    private long currentPositionMs() {
        ClientPlaybackStateCache.Snapshot snapshot = protocol.stateCache().snapshot(player, sessionId.toString());
        return snapshot == null ? 0 : snapshot.displayPositionMs();
    }

    private void send(PacketType type, Object data) {
        revision++;
        protocol.send(player, Envelope.session(type, sessionId.toString(), revision,
                ProtocolCodec.data(data)));
        if (plugin.debug()) {
            plugin.getLogger().info("[client] -> " + player.getName() + " " + type + " rev=" + revision);
        }
    }
}
