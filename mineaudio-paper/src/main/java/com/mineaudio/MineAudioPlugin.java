package com.mineaudio;

import java.io.File;

import com.mineaudio.backend.BackendRegistry;
import com.mineaudio.backend.NbsBackend;
import com.mineaudio.backend.SoundBackend;
import com.mineaudio.backend.StreamBackend;
import com.mineaudio.client.ClientProtocolService;
import com.mineaudio.client.MineAudioClientProvider;
import com.mineaudio.command.AudioCommand;
import com.mineaudio.config.YamlFile;
import com.mineaudio.config.YamlNode;
import com.mineaudio.emitter.EmitterManager;
import com.mineaudio.integration.PlaceholderHook;
import com.mineaudio.listener.PlayerConnectionListener;
import com.mineaudio.playback.AudioOrchestrator;
import com.mineaudio.profile.PlayerPackStatus;
import com.mineaudio.profile.PlayerStreamStatus;
import com.mineaudio.region.RegionManager;
import com.mineaudio.stream.resolve.DirectUrlResolver;
import com.mineaudio.stream.resolve.NeteaseEapiResolver;
import com.mineaudio.stream.resolve.StreamResolverChain;
import com.mineaudio.track.CueRegistry;
import com.mineaudio.track.TrackRegistry;
import com.mineaudio.ui.AudioUi;
import com.mineaudio.ui.MineUiHook;
import com.mineaudio.ui.NoopAudioUi;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import com.mineaudio.api.MineAudioProvider;

/** MineAudio 主插件：统一管理“什么时候、给谁、在哪里、播放什么”。 */
public final class MineAudioPlugin extends JavaPlugin {

    private final TrackRegistry trackRegistry = new TrackRegistry();
    private final CueRegistry cueRegistry = new CueRegistry();
    private final BackendRegistry backends = new BackendRegistry();
    private final RegionManager regionManager = new RegionManager(this);
    private final EmitterManager emitterManager = new EmitterManager(this);
    private NbsBackend nbsBackend;
    private ClientProtocolService clientProtocol;
    private AudioOrchestrator orchestrator;
    private AudioUi audioUi = new NoopAudioUi();
    private com.mineaudio.stream.search.NeteaseSearch searchService;
    private volatile boolean shuttingDown;
    private Runnable placeholderUnregister = () -> {
    };

    /** 插件正在停用：异步解析/调度回调据此拒绝过期结果。 */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

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
        clientProtocol = new ClientProtocolService(this);
        clientProtocol.register();
        StreamBackend streamBackend = new StreamBackend(this);
        StreamResolverChain resolvers = createResolvers();
        streamBackend.register(new MineAudioClientProvider(this, clientProtocol, resolvers));
        backends.register(streamBackend);
        PlayerPackStatus packStatus = new PlayerPackStatus(this);
        PlayerStreamStatus streamStatus = new PlayerStreamStatus(this, clientProtocol);
        orchestrator = new AudioOrchestrator(this, trackRegistry, cueRegistry, backends,
                packStatus, streamStatus);
        audioUi = MineUiHook.create(this);
        placeholderUnregister = PlaceholderHook.create(this);
        MineAudioProvider.register(orchestrator);
        Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(orchestrator), this);
        Bukkit.getPluginManager().registerEvents(emitterManager, this);
        regionManager.start();
        emitterManager.start();

        AudioCommand command = new AudioCommand(this, orchestrator);
        PluginCommand audio = getCommand("mineaudio");        if (audio != null) {
            audio.setExecutor(command);
            audio.setTabCompleter(command);
        }

        getLogger().info("MineAudio 已启用（" + trackRegistry.size() + " 首曲目，"
                + cueRegistry.size() + " 个音效，" + backends.all().size() + " 个 Backend）");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        MineAudioProvider.unregister();
        placeholderUnregister.run();
        // 先停调度与实际播放（此时消息通道仍可用，能发出 STOP），最后再注销协议通道
        regionManager.stop();
        emitterManager.stop();
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        audioUi.shutdown();
        if (clientProtocol != null) {
            clientProtocol.unregister();
        }
        getLogger().info("MineAudio 已停用");
    }

    /** 重载 config.yml / tracks.yml / cues.yml / regions.yml / emitters.yml 与 NBS 曲目。 */
    public void reloadAudio() {
        reloadConfig();
        loadTracks();
        loadCues();
        loadRegions();
        loadEmitters();
        if (nbsBackend != null) {
            nbsBackend.reload();
        }
        // 首次启用时 orchestrator 尚未创建，由 onEnable 随后启动；重载时立即重建 ALWAYS 与轮询任务
        if (orchestrator != null) {
            emitterManager.start();
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

    private void loadRegions() {
        try {
            YamlNode root = YamlFile.load(dataFile("regions.yml"));
            regionManager.load(root);
        } catch (Exception e) {
            getLogger().warning("读取 regions.yml 失败：" + e.getMessage());
        }
    }

    private void loadEmitters() {
        try {
            YamlNode root = YamlFile.load(dataFile("emitters.yml"));
            emitterManager.load(root.section("emitters"));
        } catch (Exception e) {
            getLogger().warning("读取 emitters.yml 失败：" + e.getMessage());
        }
    }

    private File dataFile(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) saveResource(name, false);
        return file;
    }

    /** 组装 Resolver 链：直链 + 网易 eapi（可选凭证，无凭证匿名尝试）。 */
    private StreamResolverChain createResolvers() {
        boolean neteaseEnabled = getConfig().getBoolean("resolvers.netease.enabled", true);
        String credentialEnv = getConfig().getString("resolvers.netease.credential-env",
                "MINEAUDIO_NETEASE_MUSIC_U");
        String credentialFile = getConfig().getString("resolvers.netease.credential-file", "");
        String level = getConfig().getString("resolvers.netease.level", "exhigh");
        int timeoutMs = getConfig().getInt("resolvers.netease.timeout-ms", 5000);
        int maxConcurrent = getConfig().getInt("resolvers.netease.max-concurrent", 4);
        int coverPx = getConfig().getInt("resolvers.netease.cover-px", 64);
        NeteaseEapiResolver netease = new NeteaseEapiResolver(new NeteaseEapiResolver.Config(
                neteaseEnabled, credentialEnv, credentialFile, level, timeoutMs, maxConcurrent, coverPx));
        boolean searchEnabled = neteaseEnabled && getConfig().getBoolean("search.enabled", true);
        int maxResults = getConfig().getInt("search.max-results", 6);
        searchService = new com.mineaudio.stream.search.NeteaseSearch(
                searchEnabled, timeoutMs, maxResults, coverPx, netease::cookieHeader);
        if (neteaseEnabled) {
            getLogger().info("网易解析器已启用（凭证：" + (netease.hasCredential() ? "已配置" : "未配置，仅匿名")
                    + "，音质：" + level + "）");
        }
        return new StreamResolverChain(java.util.List.of(new DirectUrlResolver(), netease));
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

    public RegionManager regionManager() {
        return regionManager;
    }

    public EmitterManager emitterManager() {
        return emitterManager;
    }

    public AudioUi audioUi() {
        return audioUi;
    }

    public com.mineaudio.stream.search.NeteaseSearch searchService() {
        return searchService;
    }

    public ClientProtocolService clientProtocol() {
        return clientProtocol;
    }

    public boolean debug() {
        return getConfig().getBoolean("debug", false);
    }
}
