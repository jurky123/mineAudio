package com.mineaudio.api;

/** 四种音频总线：MUSIC 每玩家通常只有一个，AMBIENT 允许多层，SFX / UI 为短音效。 */
public enum AudioBus {
    MUSIC,
    AMBIENT,
    SFX,
    UI
}
