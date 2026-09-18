package com.mineaudio.protocol;

import com.google.gson.JsonObject;

/**
 * 协议信封：{@code protocol / type / session / revision / data}。
 * 非会话包（HELLO / PING 等）session 可为 null。
 */
public record Envelope(int protocol, PacketType type, String session, int revision, JsonObject data) {

    public static Envelope of(PacketType type, JsonObject data) {
        return new Envelope(ProtocolVersion.PROTOCOL, type, null, 0, data);
    }

    public static Envelope session(PacketType type, String session, int revision, JsonObject data) {
        return new Envelope(ProtocolVersion.PROTOCOL, type, session, revision, data);
    }

    /** 会话控制包是否过期（客户端用于丢弃旧 revision 的异步回调）。 */
    public boolean isStale(int currentRevision) {
        return revision <= currentRevision;
    }
}
