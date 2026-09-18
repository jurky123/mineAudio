package com.mineaudio.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.mineaudio.MineAudioPlugin;
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
    private final Map<String, CompletableFuture<Packets.UrlRefreshResult>> refreshFutures = new ConcurrentHashMap<>();
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
            }
        }, plugin);
    }

    public void unregister() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, ProtocolVersion.CHANNEL, listener);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, ProtocolVersion.CHANNEL);
    }

    public ClientConnectionRegistry registry() {
        return registry;
    }

    public ClientPlaybackStateCache stateCache() {
        return stateCache;
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

    /** 请求客户端刷新 URL；返回的 future 由调用方设置超时。 */
    public CompletableFuture<Packets.UrlRefreshResult> requestUrlRefresh(
            Player player, String session, int revision, Packets.UrlRefresh refresh) {
        CompletableFuture<Packets.UrlRefreshResult> future = new CompletableFuture<>();
        refreshFutures.put(refresh.requestId(), future);
        send(player, Envelope.session(PacketType.URL_REFRESH, session, revision, ProtocolCodec.data(refresh)));
        return future;
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
            case URL_REFRESH_RESULT -> onRefreshResult(envelope);
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
    }

    private void onRefreshResult(Envelope envelope) {
        Packets.UrlRefreshResult result;
        try {
            result = ProtocolCodec.data(envelope, Packets.UrlRefreshResult.class);
        } catch (ProtocolException e) {
            return;
        }
        CompletableFuture<Packets.UrlRefreshResult> future = refreshFutures.remove(result.requestId());
        if (future != null) {
            future.complete(result);
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
    }
}
