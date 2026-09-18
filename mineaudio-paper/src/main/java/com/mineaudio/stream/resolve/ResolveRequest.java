package com.mineaudio.stream.resolve;

/** 解析请求：直链（uri 非空）或音源标识（source+id）。 */
public record ResolveRequest(String source, String id, String uri) {

    public static ResolveRequest of(String source, String id, String uri) {
        return new ResolveRequest(source, id, uri);
    }
}
