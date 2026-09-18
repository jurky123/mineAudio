package com.mineaudio.profile;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.mineaudio.MineAudioPlugin;

/**
 * 玩家流媒体能力：MoeMusic 客户端 mod 会注册 {@code moemusic:client_handshake}
 * 通道，通过 Bukkit 的客户端通道列表即可探测。
 */
public final class PlayerStreamStatus {

    /** MoeMusic 客户端握手通道（core protocol 的 C2S channel key）。 */
    public static final String MOEMUSIC_CHANNEL = "moemusic:client_handshake";

    private final MineAudioPlugin plugin;

    public PlayerStreamStatus(MineAudioPlugin plugin) {
        this.plugin = plugin;
    }

    /** MoeMusic 服务端插件存在且该玩家装有 MoeMusic 客户端时才可流播放。 */
    public boolean streamAvailable(Player player) {
        if (Bukkit.getPluginManager().getPlugin("MoeMusic") == null) {
            return false;
        }
        if (player.getListeningPluginChannels().contains(MOEMUSIC_CHANNEL)) {
            return true;
        }
        return plugin.getConfig().getBoolean("stream.assume-available", false);
    }
}
