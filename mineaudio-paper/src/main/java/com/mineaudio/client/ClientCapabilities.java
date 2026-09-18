package com.mineaudio.client;

/** 客户端 HELLO 声明的能力位（与设计文档 §23 一致）。 */
public final class ClientCapabilities {

    public static final String STREAM_PLAYBACK = "stream_playback";
    public static final String SEEK = "seek";
    public static final String PAUSE = "pause";
    public static final String VOLUME = "volume";
    public static final String FADE = "fade";
    public static final String MULTI_SESSION = "multi_session";
    public static final String SYNC = "sync";
    public static final String CACHE = "cache";
    public static final String POSITIONAL = "positional";

    private ClientCapabilities() {
    }
}
