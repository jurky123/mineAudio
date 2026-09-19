package com.mineaudio.client.decode;

/** seek 执行结果：只表示解码器是否接受了定位请求，真正生效由目标帧到达确认。 */
public enum SeekStatus {
    /** 已提交解码器（需后续目标帧确认才算真正生效）。 */
    APPLIED,
    /** 解码器尚未加载音轨，无法定位。 */
    NOT_READY,
    /** 当前音频不支持定位。 */
    NOT_SEEKABLE
}
