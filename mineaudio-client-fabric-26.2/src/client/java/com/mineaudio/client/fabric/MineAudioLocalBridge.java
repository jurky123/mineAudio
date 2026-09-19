package com.mineaudio.client.fabric;

import com.mineaudio.client.fabric.audio.ClientAudioManager;
import com.mineui.client.api.MineUiClientBridge;

/**
 * MineUI 客户端本地状态/动作注册（命名空间 mineaudio）。
 * 通过独立 library mod mineui-client-api 提供；未安装时本类加载失败，由调用方惰性降级。
 */
final class MineAudioLocalBridge {

    private MineAudioLocalBridge() {
    }

    static void register(ClientAudioManager audio) {
        MineUiClientBridge.get().register("mineaudio",
                audio::localState,
                audio::localAction);
    }
}
