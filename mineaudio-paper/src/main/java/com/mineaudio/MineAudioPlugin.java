package com.mineaudio;

import java.io.File;

import com.mineaudio.command.AudioCommand;
import com.mineaudio.config.YamlFile;
import com.mineaudio.config.YamlNode;
import com.mineaudio.track.CueRegistry;
import com.mineaudio.track.TrackRegistry;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** MineAudio 主插件：统一管理“什么时候、给谁、在哪里、播放什么”。 */
public final class MineAudioPlugin extends JavaPlugin {

    private final TrackRegistry trackRegistry = new TrackRegistry();
    private final CueRegistry cueRegistry = new CueRegistry();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadAudio();

        AudioCommand command = new AudioCommand(this);
        PluginCommand audio = getCommand("audio");
        if (audio != null) {
            audio.setExecutor(command);
            audio.setTabCompleter(command);
        }

        getLogger().info("MineAudio 已启用（" + trackRegistry.size() + " 首曲目，"
                + cueRegistry.size() + " 个音效）");
    }

    @Override
    public void onDisable() {
        getLogger().info("MineAudio 已停用");
    }

    /** 重载 config.yml / tracks.yml / cues.yml。 */
    public void reloadAudio() {
        reloadConfig();
        loadTracks();
        loadCues();
    }

    private void loadTracks() {
        try {
            YamlNode root = YamlFile.load(dataFile("tracks.yml"));
            trackRegistry.load(root.section("tracks"), this::warn);
        } catch (Exception e) {
            getLogger().warning("读取 tracks.yml 失败：" + e.getMessage());
        }
    }

    private void loadCues() {
        try {
            YamlNode root = YamlFile.load(dataFile("cues.yml"));
            cueRegistry.load(root.section("cues"), this::warn);
        } catch (Exception e) {
            getLogger().warning("读取 cues.yml 失败：" + e.getMessage());
        }
    }

    private File dataFile(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) saveResource(name, false);
        return file;
    }

    private void warn(String message) {
        getLogger().warning("[配置] " + message);
    }

    public TrackRegistry trackRegistry() {
        return trackRegistry;
    }

    public CueRegistry cueRegistry() {
        return cueRegistry;
    }

    public boolean debug() {
        return getConfig().getBoolean("debug", false);
    }
}
