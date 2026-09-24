package com.mineaudio.client.fabric;

import com.mineaudio.client.fabric.audio.ClientAudioManager;
import com.mineui.client.api.ClientStateProvider;
import com.mineui.client.api.MineUiClientBridge;

/**
 * MineUI 客户端本地状态/动作注册（命名空间 mineaudio）。
 * 通过独立 library mod mineui-client-api 提供；未安装时本类加载失败，由调用方惰性降级。
 */
final class MineAudioLocalBridge {

    private MineAudioLocalBridge() {
    }

    static void register(ClientAudioManager audio) {
        MineUiClientBridge bridge = MineUiClientBridge.get();
        bridge.register("mineaudio",
                new ClientStateProvider() {
                    @Override
                    public Object get(String key) {
                        return audio.localState(key);
                    }

                    @Override
                    public long generation() {
                        return audio.localGeneration();
                    }
                },
                audio::localAction);
        // 业务能力位：服务端据此确认“MineAudio 本地控制”可用（local_state 只说明有本地提供者）
        bridge.declareCapability("mineaudio_local_v1");
    }
}
