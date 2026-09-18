package com.mineaudio.stream.resolve;

/** 解析失败（可分类）。消息不包含任何凭证。 */
public class ResolveException extends Exception {

    private final ResolveFailureKind kind;

    public ResolveException(ResolveFailureKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ResolveFailureKind kind() {
        return kind;
    }
}
