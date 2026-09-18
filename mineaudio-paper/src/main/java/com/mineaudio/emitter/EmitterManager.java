package com.mineaudio.emitter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitTask;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.event.AudioEmitterStartEvent;
import com.mineaudio.config.YamlNode;

import net.kyori.adventure.key.Key;

/**
 * 发声点总控：ALWAYS 随启用播放，REDSTONE 轮询方块供电启停，INTERACT 右键切换，
 * COMMAND 由命令启停；权威数据在 emitters.yml。
 */
public final class EmitterManager implements Listener {

    private final MineAudioPlugin plugin;
    private final Map<String, AudioEmitter> emitterById = new LinkedHashMap<>();
    private final Map<String, AudioEmitter> byBlock = new HashMap<>();
    private final Map<String, PlaybackHandle> playing = new HashMap<>();
    private BukkitTask pollTask;

    public EmitterManager(MineAudioPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(YamlNode root) {
        stopAll();
        emitterById.clear();
        byBlock.clear();
        for (AudioEmitter emitter : EmitterParser.parse(root,
                message -> plugin.getLogger().warning("[Emitter] " + message))) {
            put(emitter);
        }
    }

    public void start() {
        stopTask();
        for (AudioEmitter emitter : emitterById.values()) {
            if (emitter.trigger() == Trigger.ALWAYS) {
                start(emitter.id());
            }
        }
        int interval = Math.max(1, plugin.getConfig().getInt("emitter-poll-interval-ticks", 5));
        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, interval, interval);
    }

    public void stop() {
        stopTask();
        stopAll();
    }

    private void stopTask() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    private void poll() {
        for (AudioEmitter emitter : emitterById.values()) {
            if (emitter.trigger() != Trigger.REDSTONE) continue;
            boolean active = playing.containsKey(emitter.id());
            boolean powered = isPowered(emitter);
            if (powered && !active) {
                start(emitter.id());
            } else if (!powered && active) {
                stop(emitter.id());
            }
        }
    }

    // ---------- 启停 ----------

    public boolean start(String id) {
        AudioEmitter emitter = emitterById.get(id);
        if (emitter == null || playing.containsKey(id)) return false;
        AudioTrack track = plugin.trackRegistry().get(emitter.track()).orElse(null);
        if (track == null) {
            plugin.getLogger().warning("[Emitter] " + id + " 引用了未定义曲目 " + emitter.track());
            return false;
        }
        Location location = locationOf(emitter);
        if (location.getWorld() == null) {
            plugin.getLogger().warning("[Emitter] " + id + " 的世界不存在：" + emitter.world());
            return false;
        }
        AudioTrack looped = withLoop(track, emitter.loop());
        PlaybackHandle handle = plugin.orchestrator().playTrackAt(location, looped, emitter.radius());
        playing.put(id, handle);
        Bukkit.getPluginManager().callEvent(new AudioEmitterStartEvent(id, looped, location));
        return true;
    }

    public boolean stop(String id) {
        PlaybackHandle handle = playing.remove(id);
        if (handle == null) return false;
        handle.stop();
        return true;
    }

    public void toggle(String id) {
        if (!stop(id)) {
            start(id);
        }
    }

    private void stopAll() {
        for (PlaybackHandle handle : playing.values()) {
            handle.stop();
        }
        playing.clear();
    }

    private boolean isPowered(AudioEmitter emitter) {
        Location location = locationOf(emitter);
        World world = location.getWorld();
        if (world == null) return false;
        Block block = world.getBlockAt(location);
        return block.isBlockPowered() || block.isBlockIndirectlyPowered();
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        AudioEmitter emitter = emitterAt(event.getClickedBlock());
        if (emitter != null && emitter.trigger() == Trigger.INTERACT) {
            toggle(emitter.id());
        }
    }

    // ---------- 数据与持久化 ----------

    public Collection<AudioEmitter> all() {
        return List.copyOf(emitterById.values());
    }

    public AudioEmitter emitter(String id) {
        return emitterById.get(id);
    }

    public void put(AudioEmitter emitter) {
        removeIndex(emitter.id());
        emitterById.put(emitter.id(), emitter);
        byBlock.put(blockKey(emitter), emitter);
    }

    public boolean remove(String id) {
        removeIndex(id);
        return emitterById.remove(id) != null;
    }

    private void removeIndex(String id) {
        AudioEmitter previous = emitterById.get(id);
        if (previous != null) {
            byBlock.remove(blockKey(previous));
        }
    }

    public AudioEmitter emitterAt(Block block) {
        return byBlock.get(blockKey(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ()));
    }

    public void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> emitters = new LinkedHashMap<>();
        for (AudioEmitter emitter : emitterById.values()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("world", emitter.world());
            map.put("x", number(emitter.x()));
            map.put("y", number(emitter.y()));
            map.put("z", number(emitter.z()));
            map.put("track", shortKey(emitter.track()));
            if (emitter.radius() > 0) map.put("radius", number(emitter.radius()));
            map.put("trigger", emitter.trigger().name());
            if (emitter.loop()) map.put("loop", true);
            emitters.put(emitter.id(), map);
        }
        root.put("emitters", emitters);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String yaml = "# MineAudio 发声点配置（由 /audio emitter 命令写入，注释会丢失）\n"
                + new Yaml(options).dump(root);
        try {
            Files.writeString(new File(plugin.getDataFolder(), "emitters.yml").toPath(),
                    yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("保存 emitters.yml 失败：" + e.getMessage());
        }
    }

    private static AudioTrack withLoop(AudioTrack track, boolean loop) {
        if (track.options().loop() == loop) return track;
        return new AudioTrack(track.id(), track.bus(), track.primary(), track.fallback(),
                track.options().toBuilder().loop(loop).build(), track.metadata());
    }

    private static Location locationOf(AudioEmitter emitter) {
        return new Location(Bukkit.getWorld(emitter.world()),
                emitter.x() + 0.5, emitter.y() + 0.5, emitter.z() + 0.5);
    }

    private static String blockKey(AudioEmitter emitter) {
        return blockKey(emitter.world(), (int) Math.floor(emitter.x()),
                (int) Math.floor(emitter.y()), (int) Math.floor(emitter.z()));
    }

    private static String blockKey(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    private static Object number(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return (long) value;
        }
        return value;
    }

    private static String shortKey(Key key) {
        return "mineaudio".equals(key.namespace()) ? key.value() : key.asString();
    }
}
