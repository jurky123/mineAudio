package com.mineaudio.protocol;

import java.util.Locale;

/** 协议包类型；未知类型解析为 {@link #UNKNOWN} 并被忽略。 */
public enum PacketType {
    HELLO,
    HELLO_ACK,
    PLAY,
    STOP,
    PAUSE,
    RESUME,
    SEEK,
    VOLUME,
    URL_REFRESH,
    URL_REFRESH_RESULT,
    STATE,
    PING,
    PONG,
    ERROR,
    UNKNOWN;

    public static PacketType from(String raw) {
        if (raw == null) return UNKNOWN;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
