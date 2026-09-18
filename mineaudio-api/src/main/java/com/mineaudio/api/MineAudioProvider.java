package com.mineaudio.api;

/** MineAudio 服务定位器：由 mineaudio-paper 在启用时注册，业务插件只读。 */
public final class MineAudioProvider {

    private static volatile MineAudio instance;

    private MineAudioProvider() {
    }

    /** MineAudio 是否可用（未安装/未启用时为 null）。 */
    public static MineAudio get() {
        return instance;
    }

    public static void register(MineAudio api) {
        instance = api;
    }

    public static void unregister() {
        instance = null;
    }
}
