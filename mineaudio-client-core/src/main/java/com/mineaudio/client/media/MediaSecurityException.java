package com.mineaudio.client.media;

/** 媒体安全校验失败（不可恢复，应上报 ERROR）。 */
public class MediaSecurityException extends Exception {

    private final String code;

    public MediaSecurityException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
