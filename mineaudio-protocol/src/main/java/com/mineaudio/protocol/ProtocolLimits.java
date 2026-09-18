package com.mineaudio.protocol;

/** 应用层硬限制（见设计文档 §70）。 */
public final class ProtocolLimits {

    public static final int MAX_PACKET_BYTES = 24 * 1024;
    public static final int MAX_URL_LENGTH = 8 * 1024;
    public static final int MAX_HEADER_COUNT = 16;
    public static final int MAX_HEADER_VALUE_LENGTH = 1024;
    public static final int MAX_ERROR_MESSAGE_LENGTH = 256;
    public static final int MAX_ID_LENGTH = 256;

    private ProtocolLimits() {
    }
}
