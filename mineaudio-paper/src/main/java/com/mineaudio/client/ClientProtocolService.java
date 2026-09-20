package com.mineaudio.client;


import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.playback.AudioOrchestrator;
import com.mineaudio.protocol.Envelope;
import com.mineaudio.protocol.PacketType;
import com.mineaudio.protocol.Packets;
import com.mineaudio.protocol.ProtocolCodec;
import com.mineaudio.protocol.ProtocolException;
import com.mineaudio.protocol.ProtocolVersion;

/**
 * MineAudio Client 协议服务：插件消息通道注册、握手、校时与状态缓存。
 * 所有处理都在主线程（Bukkit 插件消息回调），重活交给客户端。
 */
public final class ClientProtocolService {

    private final MineAudioPlugin plugin;
    private final ClientConnectionRegistry registry = new ClientConnectionRegistry();
    private final ClientPlaybackStateCache stateCache = new ClientPlaybackStateCache();
    /** 最近一次发出的 seek（sessionId -> requestId），供 UI 待确认绑定。 */
    private final java.util.Map<java.util.UUID, java.util.Map<String, Long>> lastSeekRequests =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final PluginMessageListener listener = this::onPluginMessageReceived;

    public ClientProtocolService(MineAudioPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, ProtocolVersion.CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, ProtocolVersion.CHANNEL, listener);
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                registry.remove(event.getPlayer().getUniqueId());
                stateCache.clear(event.getPlayer());
                lastSeekRequests.remove(event.getPlayer().getUniqueId());
            }
        }, plugin);
    }

    public void unregister() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, ProtocolVersion.CHANNEL, listener);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, ProtocolVersion.CHANNEL);
        lastSeekRequests.clear();
    }

    public ClientConnectionRegistry registry() {
        return registry;
    }

    public ClientPlaybackStateCache stateCache() {
        return stateCache;
    }

    public void noteSeekRequest(Player player, String sessionId, long requestId) {
        lastSeekRequests.computeIfAbsent(player.getUniqueId(),
                ignored -> new java.util.concurrent.ConcurrentHashMap<>()).put(sessionId, requestId);
        ClientPlaybackStateCache.Snapshot snapshot = stateCache.snapshot(player, sessionId);
        if (snapshot != null && snapshot.lastCommandId() == requestId) {
            lastSeekRequests.get(player.getUniqueId()).remove(sessionId);
        }
    }

    /** 该会话最近一次发出的 seek 命令序号；无记录返回 0。 */
    public long lastSeekRequest(Player player, String sessionId) {
        java.util.Map<String, Long> bySession = lastSeekRequests.get(player.getUniqueId());
        if (bySession == null) return 0;
        return bySession.getOrDefault(sessionId, 0L);
    }

    public static long monotonicMs() {
        return System.nanoTime() / 1_000_000;
    }

    // ---------- 发送 ----------

    public void send(Player player, Envelope envelope) {
        if (player == null || !player.isOnline()) return;
        try {
            player.sendPluginMessage(plugin, ProtocolVersion.CHANNEL, ProtocolCodec.encode(envelope));
        } catch (Throwable t) {
            if (plugin.debug()) {
                plugin.getLogger().warning("发送协议包失败（" + envelope.type() + "）：" + t);
            }
        }
    }

    // ---------- 接收 ----------

    private void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ProtocolVersion.CHANNEL.equals(channel)) return;
        Envelope envelope;
        try {
            envelope = ProtocolCodec.decode(message);
        } catch (ProtocolException e) {
            return;
        }
        switch (envelope.type()) {
            case HELLO -> onHello(player, envelope);
            case PING -> onPing(player, envelope);
            case STATE -> onState(player, envelope);
            case ERROR -> onError(player, envelope);
            default -> {
                // 未知包忽略
            }
        }
    }

    private void onHello(Player player, Envelope envelope) {
        Packets.Hello hello;
        try {
            hello = ProtocolCodec.data(envelope, Packets.Hello.class);
        } catch (ProtocolException e) {
            return;
        }
        registry.markHello(player, hello);
        // 握手完成后再尝试受众会话（晚加入：按共享时间轴对齐当前进度）
        AudioOrchestrator orchestrator = plugin.orchestrator();
        if (orchestrator != null) {
            orchestrator.onClientReady(player);
        }
        Packets.HelloAck ack = new Packets.HelloAck(
                plugin.getPluginMeta().getVersion(),
                Math.max(200, plugin.getConfig().getInt("stream-client.state-report-ms", 1000)),
                Math.max(0, plugin.getConfig().getInt("stream-client.max-ambient-layers",
                        plugin.getConfig().getInt("max-ambient-layers", 3))),
                new Packets.HelloAck.Sync(
                        plugin.getConfig().getBoolean("stream-client.sync.enabled", true),
                        plugin.getConfig().getInt("stream-client.sync.ping-interval-ms", 10000),
                        plugin.getConfig().getInt("stream-client.sync.drift-threshold-ms", 150)),
                new Packets.HelloAck.Firewall(
                        plugin.getConfig().getBoolean("stream-client.firewall.https-only", true),
                        plugin.getConfig().getBoolean("stream-client.firewall.deny-private-network", true),
                        plugin.getConfig().getInt("stream-client.firewall.max-redirects", 5)));
        send(player, Envelope.of(PacketType.HELLO_ACK, ProtocolCodec.data(ack)));
        if (plugin.debug()) {
            plugin.getLogger().info("[client] " + player.getName() + " 握手完成 mod=" + hello.modVersion()
                    + " mc=" + hello.minecraft() + " caps=" + hello.capabilities());
        }
    }

    private void onPing(Player player, Envelope envelope) {
        Packets.Ping ping;
        try {
            ping = ProtocolCodec.data(envelope, Packets.Ping.class);
        } catch (ProtocolException e) {
            return;
        }
        long t1 = monotonicMs();
        Packets.Pong pong = new Packets.Pong(ping.t0(), t1, monotonicMs());
        send(player, Envelope.of(PacketType.PONG, ProtocolCodec.data(pong)));
    }

    private void onState(Player player, Envelope envelope) {
        if (envelope.session() == null) return;
        Packets.State state;
        try {
            state = ProtocolCodec.data(envelope, Packets.State.class);
        } catch (ProtocolException e) {
            return;
        }
        stateCache.update(player, envelope.session(), envelope.revision(), state);
        // 客户端终态驱动服务端会话收尾（STREAM 以客户端输出耗尽为准，定时器只兜底）
        if ("FINISHED".equals(state.state()) || "ERROR".equals(state.state())) {
            AudioOrchestrator orchestrator = plugin.orchestrator();
            if (orchestrator != null) {
                Packets.State.Error error = state.error();
                orchestrator.onClientTerminal(player, envelope.session(),
                        "FINISHED".equals(state.state()),
                        error == null ? null : error.code(),
                        error == null ? null : error.message());
            }
        }
        if (plugin.debug()) {
            plugin.getLogger().info("[client] <- " + player.getName() + " STATE session="
                    + envelope.session() + " state=" + state.state() + " pos=" + state.positionMs()
                    + " dur=" + state.durationMs());
        }
    }

    private void onError(Player player, Envelope envelope) {
        Packets.ErrorReport error;
        try {
            error = ProtocolCodec.data(envelope, Packets.ErrorReport.class);
        } catch (ProtocolException e) {
            return;
        }
        plugin.getLogger().warning("[client] " + player.getName() + " 播放错误 "
                + error.code() + "：" + error.message());
        // ERROR 包同样驱动服务端幂等收尾（与 STATE 终态互为兜底）
        AudioOrchestrator orchestrator = plugin.orchestrator();
        if (orchestrator != null && envelope.session() != null) {
            orchestrator.onClientTerminal(player, envelope.session(), false,
                    error.code(), error.message());
        }
    }
}
