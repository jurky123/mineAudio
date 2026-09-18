package com.mineaudio.profile;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.client.ClientCapabilities;
import com.mineaudio.client.ClientConnectionRegistry;
import com.mineaudio.client.ClientProtocolService;

/**
 * 玩家流媒体能力：优先看 MineAudio Client 握手，其次 MoeMusic 客户端通道。
 */
public final class PlayerStreamStatus {

    /** MoeMusic 客户端握手通道（core protocol 的 C2S channel key）。 */
    public static final String MOEMUSIC_CHANNEL = "moemusic:client_handshake";

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
        if (Bukkit.getPluginManager().getPlugin("MoeMusic") == null) {
            return false;
        }
        if (player.getListeningPluginChannels().contains(MOEMUSIC_CHANNEL)) {
            return true;
        }
        return plugin.getConfig().getBoolean("stream.assume-available", false);
    }
}
