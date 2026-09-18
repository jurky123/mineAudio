package com.mineaudio.stream.resolve;

/** 解析失败分类：便于 UI/日志给出准确反馈，而不是统一的"播放失败"。 */
public enum ResolveFailureKind {
    UNSUPPORTED_SOURCE,
    CREDENTIAL_MISSING,
    NOT_PLAYABLE,
    ACCOUNT_NOT_ENTITLED,
    RATE_LIMITED,
    REMOTE_UNAVAILABLE,
    INVALID_RESPONSE,
    TIMEOUT,
    DISABLED
}
