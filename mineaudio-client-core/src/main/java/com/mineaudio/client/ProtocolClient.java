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

    private static final System.Logger LOG = System.getLogger("MineAudio");

    public interface Transport {
        void send(byte[] data);
    }

    /** 服务端指令与心跳回调；全部为默认空实现，平台层按需覆盖。session 为服务端会话号。 */
    public interface Listener {
        default void onHelloAck(Packets.HelloAck ack) {
        }

        default void onPlay(String session, Packets.Play play) {
        }

        default void onStop(String session, Packets.Stop stop) {
        }

        default void onPause(String session, Packets.Pause pause) {
        }

        default void onResume(String session, Packets.Resume resume) {
        }

        default void onSeek(String session, Packets.Seek seek) {
        }

        default void onVolume(String session, Packets.Volume volume) {
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
    private Packets.Hello hello;
    private long lastHelloAt;
    private int helloAttempts;

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
        this.helloAttempts = 0;
        this.lastHelloAt = System.nanoTime() / 1_000_000;
        this.clock.reset();
        this.hello = new Packets.Hello(modVersion, minecraft, locale,
                List.copyOf(capabilities), List.copyOf(formats));
        send(PacketType.HELLO, ProtocolCodec.data(hello));
        helloAttempts = 1;
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
        // 握手重试：ACK 丢失时（网络抖动/服务端延迟）保证能力注册与状态上报不至于整局失效
        if (!connected && hello != null && helloAttempts < 5 && nowMs - lastHelloAt >= 2000) {
            lastHelloAt = nowMs;
            helloAttempts++;
            LOG.log(System.Logger.Level.INFO, "尚未收到 HELLO_ACK，重发握手（第 " + helloAttempts + " 次）");
            send(PacketType.HELLO, ProtocolCodec.data(hello));
        }
        // STATE 不依赖握手完成：服务端按 session 更新缓存，不要求注册状态
        if (nowMs - lastStateReportAt >= reportIntervalMs) {
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

    /** 本地曲库上传完成后，请求服务端入全服队列。 */
    public void sendLibraryAdd(Packets.LibraryAdd add) {
        send(PacketType.LIBRARY_ADD, ProtocolCodec.data(add));
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
            case PLAY -> dispatchSession(envelope, Packets.Play.class, listener::onPlay);
            case STOP -> dispatchSession(envelope, Packets.Stop.class, listener::onStop);
            case PAUSE -> dispatchSession(envelope, Packets.Pause.class, listener::onPause);
            case RESUME -> dispatchSession(envelope, Packets.Resume.class, listener::onResume);
            case SEEK -> dispatchSession(envelope, Packets.Seek.class, listener::onSeek);
            case VOLUME -> dispatchSession(envelope, Packets.Volume.class, listener::onVolume);
            default -> {
                // 未知/暂不处理的包：忽略
            }
        }
    }

    private void handleHelloAck(Envelope envelope) {
        try {
            serverInfo = ProtocolCodec.data(envelope, Packets.HelloAck.class);
        } catch (ProtocolException | RuntimeException | LinkageError e) {
            LOG.log(System.Logger.Level.WARNING, "HELLO_ACK 解析失败: " + e);
            return;
        }
        connected = true;
        helloAttempts = 0;
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

    private <T> void dispatchSession(Envelope envelope, Class<T> type,
            java.util.function.BiConsumer<String, T> consumer) {
        try {
            consumer.accept(envelope.session(), ProtocolCodec.data(envelope, type));
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
