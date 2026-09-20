package com.mineaudio.region;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.event.AudioRegionEnterEvent;
import com.mineaudio.api.event.AudioRegionLeaveEvent;
import com.mineaudio.config.YamlNode;
import com.mineaudio.playback.PlaybackOrigin;
import com.mineaudio.playback.PlaybackSession;

import net.kyori.adventure.key.Key;

/**
 * 区域总控：按 chunk 索引检测玩家所在区域，处理边界迟滞，仲裁区域栈音乐/环境音，
 * 并负责 regions.yml 的读写。
 */
public final class RegionManager {

    private final MineAudioPlugin plugin;
    private final Map<String, AudioRegion> regionById = new LinkedHashMap<>();
    private final Map<String, RegionParser.WorldLayer> worlds = new LinkedHashMap<>();
    private final RegionIndex index = new RegionIndex();
    private final Map<UUID, RegionHysteresis> presence = new HashMap<>();
    private BukkitTask task;
    private int maxAmbientLayers = 3;

    public RegionManager(MineAudioPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(YamlNode root) {
        RegionParser.Parsed parsed = RegionParser.parse(root,
                message -> plugin.getLogger().warning("[区域] " + message),
                plugin.getConfig().getInt("region-enter-delay-ms", 300),
                plugin.getConfig().getInt("region-exit-delay-ms", 500));
        regionById.clear();
        worlds.clear();
        for (AudioRegion region : parsed.regions()) {
            regionById.put(region.id(), region);
        }
        worlds.putAll(parsed.worlds());
        rebuild();
        validateTracks();
    }

    public void start() {
        stop();
        maxAmbientLayers = Math.max(0, plugin.getConfig().getInt("max-ambient-layers", 3));
        int interval = Math.max(1, plugin.getConfig().getInt("region-check-interval-ticks", 10));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ---------- 检测与仲裁 ----------

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player);
        }
        presence.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void updatePlayer(Player player) {
        Location location = player.getLocation();
        List<AudioRegion> candidates = index.candidates(player.getWorld().getName(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4);
        Set<String> inside = new HashSet<>();
        for (AudioRegion region : candidates) {
            if (region.shape().contains(location.x(), location.y(), location.z())) {
                inside.add(region.id());
            }
        }
        RegionHysteresis hysteresis = presence.computeIfAbsent(player.getUniqueId(),
                ignored -> new RegionHysteresis());
        for (String id : hysteresis.update(inside, regionById::get)) {
            AudioRegion region = regionById.get(id);
            if (region == null) continue;
            AudioTrack track = region.music() == null ? null
                    : plugin.trackRegistry().get(region.music()).orElse(null);
            if (hysteresis.isActive(id)) {
                Bukkit.getPluginManager().callEvent(new AudioRegionEnterEvent(player, id, track));
            } else {
                Bukkit.getPluginManager().callEvent(new AudioRegionLeaveEvent(player, id, track));
            }
        }
        applyMusic(player, hysteresis);
        applyAmbient(player, hysteresis);
    }

    private List<AudioRegion> activeRegions(RegionHysteresis hysteresis) {
        List<AudioRegion> active = new ArrayList<>();
        for (String id : hysteresis.active()) {
            AudioRegion region = regionById.get(id);
            if (region != null) active.add(region);
        }
        return active;
    }

    private void applyMusic(Player player, RegionHysteresis hysteresis) {
        List<AudioRegion> active = activeRegions(hysteresis);
        RegionParser.WorldLayer worldLayer = worlds.get(player.getWorld().getName());
        Key desired = RegionSelection.selectMusic(active, worldLayer == null ? null : worldLayer.music());
        if (desired == null) {
            plugin.orchestrator().stopManagedMusic(player);
            return;
        }
        PlaybackOrigin origin = RegionSelection.fromRegion(active, desired)
                ? PlaybackOrigin.REGION : PlaybackOrigin.WORLD;
        AudioTrack track = plugin.trackRegistry().get(desired).orElse(null);
        if (track != null) {
            // 只声明“希望有这条 region/world 音乐”；能否真正播放由 MusicArbiter 按优先级决定
            plugin.orchestrator().playManaged(player, track, origin);
        }
    }

    private void applyAmbient(Player player, RegionHysteresis hysteresis) {
        List<AudioRegion> active = activeRegions(hysteresis);
        RegionParser.WorldLayer worldLayer = worlds.get(player.getWorld().getName());

        LinkedHashSet<Key> desired = new LinkedHashSet<>(
                RegionSelection.selectAmbient(active, maxAmbientLayers));
        if (worldLayer != null) {
            for (Key key : worldLayer.ambient()) {
                if (desired.size() >= maxAmbientLayers) break;
                desired.add(key);
            }
        }

        Set<Key> managed = new HashSet<>();
        for (PlaybackSession session : plugin.orchestrator().sessions(player)) {
            if (session.track().bus() == AudioBus.AMBIENT && session.origin() != PlaybackOrigin.API) {
                managed.add(session.track().id());
            }
        }
        for (Key key : desired) {
            if (managed.contains(key)) continue;
            AudioTrack track = plugin.trackRegistry().get(key).orElse(null);
            if (track != null) {
                plugin.orchestrator().playManagedAmbient(player, track);
            }
        }
        for (Key key : managed) {
            if (!desired.contains(key)) {
                plugin.orchestrator().stopManagedAmbient(player, key);
            }
        }
    }

    // ---------- 数据访问与持久化 ----------

    public Collection<AudioRegion> all() {
        return List.copyOf(regionById.values());
    }

    public AudioRegion region(String id) {
        return regionById.get(id);
    }

    public void put(AudioRegion region) {
        regionById.put(region.id(), region);
        rebuild();
    }

    public boolean remove(String id) {
        if (regionById.remove(id) == null) return false;
        rebuild();
        return true;
    }

    public void setWorldLayer(String world, Key music, List<Key> ambient) {
        worlds.put(world, new RegionParser.WorldLayer(music, ambient));
    }

    private void rebuild() {
        index.rebuild(regionById.values());
        presence.clear();
    }

    private void validateTracks() {
        for (AudioRegion region : regionById.values()) {
            checkTrack(region.music(), region.id());
            for (Key key : region.ambient()) checkTrack(key, region.id());
        }
        for (Map.Entry<String, RegionParser.WorldLayer> entry : worlds.entrySet()) {
            checkTrack(entry.getValue().music(), "worlds." + entry.getKey());
            for (Key key : entry.getValue().ambient()) checkTrack(key, "worlds." + entry.getKey());
        }
    }

    private void checkTrack(Key key, String where) {
        if (key != null && !plugin.trackRegistry().contains(key)) {
            plugin.getLogger().warning("[区域] " + where + " 引用了未定义曲目 " + key);
        }
    }

    /** 写回 regions.yml（注释会丢失）。 */
    public void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> worldsOut = new LinkedHashMap<>();
        for (Map.Entry<String, RegionParser.WorldLayer> entry : worlds.entrySet()) {
            Map<String, Object> layer = new LinkedHashMap<>();
            RegionParser.WorldLayer value = entry.getValue();
            if (value.music() != null) layer.put("music", shortKey(value.music()));
            if (!value.ambient().isEmpty()) {
                layer.put("ambient", value.ambient().stream().map(RegionManager::shortKey).toList());
            }
            worldsOut.put(entry.getKey(), layer);
        }
        Map<String, Object> regionsOut = new LinkedHashMap<>();
        for (AudioRegion region : regionById.values()) {
            regionsOut.put(region.id(), regionToMap(region));
        }
        root.put("worlds", worldsOut);
        root.put("regions", regionsOut);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String yaml = "# MineAudio 区域配置（由 /mineaudio region 命令写入，注释会丢失）\n"
                + new Yaml(options).dump(root);
        try {
            Files.writeString(new File(plugin.getDataFolder(), "regions.yml").toPath(),
                    yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("保存 regions.yml 失败：" + e.getMessage());
        }
    }

    private static Map<String, Object> regionToMap(AudioRegion region) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", region.world());
        map.put("shape", shapeToMap(region.shape()));
        map.put("priority", region.priority());
        if (region.music() != null) map.put("music", shortKey(region.music()));
        if (!region.ambient().isEmpty()) {
            map.put("ambient", region.ambient().stream().map(RegionManager::shortKey).toList());
        }
        map.put("fade-in-ms", region.fadeInMs());
        map.put("fade-out-ms", region.fadeOutMs());
        map.put("enter-delay-ms", region.enterDelayTicks() * 50);
        map.put("exit-delay-ms", region.exitDelayTicks() * 50);
        return map;
    }

    private static Map<String, Object> shapeToMap(RegionShape shape) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (shape instanceof RegionShape.Cuboid cuboid) {
            map.put("type", "CUBOID");
            map.put("min", xyz(cuboid.minX(), cuboid.minY(), cuboid.minZ()));
            map.put("max", xyz(cuboid.maxX(), cuboid.maxY(), cuboid.maxZ()));
        } else if (shape instanceof RegionShape.Sphere sphere) {
            map.put("type", "SPHERE");
            map.put("center", xyz(sphere.centerX(), sphere.centerY(), sphere.centerZ()));
            map.put("radius", number(sphere.radius()));
        }
        return map;
    }

    private static Map<String, Object> xyz(double x, double y, double z) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", number(x));
        map.put("y", number(y));
        map.put("z", number(z));
        return map;
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
