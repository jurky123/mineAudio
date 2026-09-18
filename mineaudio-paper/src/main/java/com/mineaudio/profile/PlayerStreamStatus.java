package com.mineaudio.profile;

import org.bukkit.entity.Player;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.client.ClientCapabilities;
import com.mineaudio.client.ClientConnectionRegistry;
import com.mineaudio.client.ClientProtocolService;

/** 玩家流媒体能力：只认 MineAudio Client 握手（未安装时按 fallback 降级）。 */
public final class PlayerStreamStatus {

    private final MineAudioPlugin plugin;
    private final ClientProtocolService protocol;

    public PlayerStreamStatus(MineAudioPlugin plugin, ClientProtocolService protocol) {
        this.plugin = plugin;
        this.protocol = protocol;
    }

    public boolean streamAvailable(Player player) {
        ClientConnectionRegistry.ClientInfo info = protocol.registry().get(player);
        if (info != null && info.supports(ClientCapabilities.STREAM_PLAYBACK)) {
            return true;
        }
        return plugin.getConfig().getBoolean("stream.assume-available", false);
    }
}
