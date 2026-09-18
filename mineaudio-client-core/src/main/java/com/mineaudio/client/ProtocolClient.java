package com.mineaudio.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.mineaudio.protocol.Envelope;
import com.mineaudio.protocol.PacketType;
import com.mineaudio.protocol.Packets;
import com.mineaudio.protocol.ProtocolCodec;
import com.mineaudio.protocol.ProtocolException;
import com.mineaudio.protocol.ProtocolLimits;

/**
 * 协议客户端：握手、校时、心跳与状态上报；播放会话管理由平台层监听器接入。
 * 与 Minecraft 解耦，由平台层提供 {@link Transport} 并在客户端线程驱动。
 */
public final class ProtocolClient {

    public interface Transport {
        void send(byte[] data);
    }

    /** 服务端指令与心跳回调；全部为默认空实现，平台层按需覆盖。 */
    public interface Listener {
        default void onHelloAck(Packets.HelloAck ack) {
        }

        default void onPlay(Packets.Play play) {
        }

        default void onStop(Packets.Stop stop) {
        }

        default void onPause(Packets.Pause pause) {
        }

        default void onResume(Packets.Resume resume) {
        }

        default void onSeek(Packets.Seek seek) {
        }

        default void onVolume(Packets.Volume volume) {
        }

        /** 按服务端配置的间隔触发（默认 1s），用于上报各会话状态。 */
        default void onTickReport() {
        }
    }

    private static final ProtocolClient INSTANCE = new ProtocolClient();

    private final ClockSynchronizer clock = new ClockSynchronizer();
    private final List<String> capabilities = new ArrayList<>();
    private final List<String> formats = new ArrayList<>();

    private Transport transport;
    private Listener listener = new Listener() {
    };
    private Packets.HelloAck serverInfo;
    private boolean connected;
    private long lastPingAt;
    private int pingIntervalMs = 10000;
    private int reportIntervalMs = 1000;
    private long lastStateReportAt;

    private ProtocolClient() {
    }

    public static ProtocolClient get() {
        return INSTANCE;
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? new Listener() {
        } : listener;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities.clear();
        this.capabilities.addAll(capabilities);
    }

    public void setFormats(List<String> formats) {
        this.formats.clear();
        this.formats.addAll(formats);
    }

    public boolean connected() {
        return connected;
    }

    public Packets.HelloAck serverInfo() {
        return serverInfo;
    }

    public ClockSynchronizer clock() {
        return clock;
    }

    public int reportIntervalMs() {
        return reportIntervalMs;
    }

    // ---------- 生命周期 ----------

    public void onJoin(Transport transport, String modVersion, String minecraft, String locale) {
        this.transport = transport;
        this.connected = false;
        this.serverInfo = null;
        this.lastPingAt = 0;
        this.lastStateReportAt = 0;
        this.clock.reset();
        Packets.Hello hello = new Packets.Hello(modVersion, minecraft, locale,
                List.copyOf(capabilities), List.copyOf(formats));
        send(PacketType.HELLO, ProtocolCodec.data(hello));
    }

    public void onDisconnect() {
        transport = null;
        connected = false;
        serverInfo = null;
        clock.reset();
    }

    public void tick(long nowMs) {
        if (transport == null) return;
        if (nowMs - lastPingAt >= pingIntervalMs) {
            lastPingAt = nowMs;
            send(PacketType.PING, ProtocolCodec.data(new Packets.Ping(clock.monotonicMs())));
        }
        if (connected && nowMs - lastStateReportAt >= reportIntervalMs) {
            lastStateReportAt = nowMs;
            listener.onTickReport();
        }
    }

    public void sendState(String session, int revision, Packets.State state) {
        sendSession(PacketType.STATE, session, revision, ProtocolCodec.data(state));
    }

    public void sendError(String session, int revision, String code, String message) {
        String safe = message == null ? "" : message;
        if (safe.length() > ProtocolLimits.MAX_ERROR_MESSAGE_LENGTH) {
            safe = safe.substring(0, ProtocolLimits.MAX_ERROR_MESSAGE_LENGTH);
        }
        sendSession(PacketType.ERROR, session, revision,
                ProtocolCodec.data(new Packets.ErrorReport(code, safe)));
    }

    public void sendUrlRefresh(String session, int revision, Packets.UrlRefresh refresh) {
        sendSession(PacketType.URL_REFRESH, session, revision, ProtocolCodec.data(refresh));
    }

    // ---------- 收发 ----------

    public void handle(byte[] data) {
        Envelope envelope;
        try {
            envelope = ProtocolCodec.decode(data);
        } catch (ProtocolException e) {
            return;
        }
        switch (envelope.type()) {
            case HELLO_ACK -> handleHelloAck(envelope);
            case PONG -> handlePong(envelope);
            case PLAY -> dispatch(envelope, Packets.Play.class, listener::onPlay);
            case STOP -> dispatch(envelope, Packets.Stop.class, listener::onStop);
            case PAUSE -> dispatch(envelope, Packets.Pause.class, listener::onPause);
            case RESUME -> dispatch(envelope, Packets.Resume.class, listener::onResume);
            case SEEK -> dispatch(envelope, Packets.Seek.class, listener::onSeek);
            case VOLUME -> dispatch(envelope, Packets.Volume.class, listener::onVolume);
            default -> {
                // 未知/暂不处理的包：忽略
            }
        }
    }

    private void handleHelloAck(Envelope envelope) {
        try {
            serverInfo = ProtocolCodec.data(envelope, Packets.HelloAck.class);
        } catch (ProtocolException e) {
            return;
        }
        connected = true;
        if (serverInfo != null) {
            pingIntervalMs = Math.max(1000, serverInfo.sync() == null
                    ? 10000 : serverInfo.sync().pingIntervalMs());
            reportIntervalMs = Math.max(200, serverInfo.reportIntervalMs());
        }
        listener.onHelloAck(serverInfo);
    }

    private void handlePong(Envelope envelope) {
        try {
            Packets.Pong pong = ProtocolCodec.data(envelope, Packets.Pong.class);
            clock.onPong(pong.t0(), pong.t1(), pong.t2());
        } catch (ProtocolException ignored) {
            // 忽略坏包
        }
    }

    private <T> void dispatch(Envelope envelope, Class<T> type, Consumer<T> consumer) {
        try {
            consumer.accept(ProtocolCodec.data(envelope, type));
        } catch (ProtocolException ignored) {
            // 忽略坏包
        }
    }

    private void send(PacketType type, JsonObject data) {
        Transport current = transport;
        if (current == null) return;
        current.send(ProtocolCodec.encode(Envelope.of(type, data)));
    }

    private void sendSession(PacketType type, String session, int revision, JsonObject data) {
        Transport current = transport;
        if (current == null || session == null) return;
        current.send(ProtocolCodec.encode(Envelope.session(type, session, revision, data)));
    }
}
