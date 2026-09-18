package com.mineaudio;

import com.mineaudio.command.AudioCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** MineAudio 主插件：统一管理“什么时候、给谁、在哪里、播放什么”。 */
public final class MineAudioPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        AudioCommand command = new AudioCommand(this);
        PluginCommand audio = getCommand("audio");
        if (audio != null) {
            audio.setExecutor(command);
            audio.setTabCompleter(command);
        }

        getLogger().info("MineAudio 已启用（版本 " + getPluginMeta().getVersion() + "）");
    }

    @Override
    public void onDisable() {
        getLogger().info("MineAudio 已停用");
    }
}
