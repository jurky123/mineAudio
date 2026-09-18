package com.mineaudio;

import java.io.File;

import com.mineaudio.backend.BackendRegistry;
import com.mineaudio.backend.NbsBackend;
import com.mineaudio.backend.SoundBackend;
import com.mineaudio.command.AudioCommand;
import com.mineaudio.config.YamlFile;
import com.mineaudio.config.YamlNode;
import com.mineaudio.listener.PlayerConnectionListener;
import com.mineaudio.playback.AudioOrchestrator;
import com.mineaudio.profile.PlayerPackStatus;
import com.mineaudio.track.CueRegistry;
import com.mineaudio.track.TrackRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import com.mineaudio.api.MineAudioProvider;

/** MineAudio 主插件：统一管理“什么时候、给谁、在哪里、播放什么”。 */
public final class MineAudioPlugin extends JavaPlugin {

    private final TrackRegistry trackRegistry = new TrackRegistry();
    private final CueRegistry cueRegistry = new CueRegistry();
    private final BackendRegistry backends = new BackendRegistry();
    private NbsBackend nbsBackend;
    private AudioOrchestrator orchestrator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadAudio();

        backends.register(new SoundBackend(this));
        if (Bukkit.getPluginManager().getPlugin("NoteBlockAPI") != null) {
            nbsBackend = new NbsBackend(this);
            backends.register(nbsBackend);
        } else {
            getLogger().info("未检测到 NoteBlockAPI，NBS Backend 不可用（PACK / Vanilla 不受影响）");
        }
        PlayerPackStatus packStatus = new PlayerPackStatus(this);
        orchestrator = new AudioOrchestrator(this, trackRegistry, cueRegistry, backends, packStatus);
        MineAudioProvider.register(orchestrator);
        Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(orchestrator), this);

        AudioCommand command = new AudioCommand(this, orchestrator);
        PluginCommand audio = getCommand("audio");
        if (audio != null) {
            audio.setExecutor(command);
            audio.setTabCompleter(command);
        }

        getLogger().info("MineAudio 已启用（" + trackRegistry.size() + " 首曲目，"
                + cueRegistry.size() + " 个音效，" + backends.all().size() + " 个 Backend）");
    }

    @Override
    public void onDisable() {
        MineAudioProvider.unregister();
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        getLogger().info("MineAudio 已停用");
    }

    /** 重载 config.yml / tracks.yml / cues.yml 与 NBS 曲目。 */
    public void reloadAudio() {
        reloadConfig();
        loadTracks();
        loadCues();
        if (nbsBackend != null) {
            nbsBackend.reload();
        }
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

    public AudioOrchestrator orchestrator() {
        return orchestrator;
    }

    public boolean debug() {
        return getConfig().getBoolean("debug", false);
    }
}
