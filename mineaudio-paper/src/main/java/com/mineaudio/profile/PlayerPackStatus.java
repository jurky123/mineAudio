package com.mineaudio.profile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import com.mineaudio.MineAudioPlugin;

/** 每玩家资源包加载状态：PACK 声音在未加载时走 fallback。 */
public final class PlayerPackStatus implements Listener {

    private final MineAudioPlugin plugin;
    private final Map<UUID, Boolean> recorded = new HashMap<>();

    public PlayerPackStatus(MineAudioPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean packAvailable(Player player) {
        Boolean value = recorded.get(player.getUniqueId());
        if (value != null) return value;
        if (player.hasResourcePack()) return true;
        return plugin.getConfig().getBoolean("pack.assume-loaded", true);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> recorded.put(event.getPlayer().getUniqueId(), true);
            case FAILED_DOWNLOAD, DECLINED, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                    recorded.put(event.getPlayer().getUniqueId(), false);
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        recorded.remove(event.getPlayer().getUniqueId());
    }
}
