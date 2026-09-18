package com.mineaudio.protocol;

/** 协议常量：V1 JSON、自定义载荷通道、版本号。 */
public final class ProtocolVersion {

    /** 当前协议版本；破坏性变更时递增。 */
    public static final int PROTOCOL = 1;

    /** 插件消息通道（Paper 与 Fabric 自定义载荷一致）。 */
    public static final String CHANNEL = "mineaudio:stream";

    private ProtocolVersion() {
    }
}
