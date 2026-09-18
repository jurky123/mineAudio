package com.mineaudio.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.mineaudio.playback.AudioOrchestrator;

/** 玩家进出/换世界时同步 Audience 会话（GLOBAL / WORLD 音乐补播与退出）。 */
public final class PlayerConnectionListener implements Listener {

    private final AudioOrchestrator orchestrator;

    public PlayerConnectionListener(AudioOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        orchestrator.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        orchestrator.onQuit(event.getPlayer());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        orchestrator.onChangedWorld(event.getPlayer());
    }
}
