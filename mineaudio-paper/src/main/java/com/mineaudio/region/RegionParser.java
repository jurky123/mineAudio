package com.mineaudio.region;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.mineaudio.config.YamlNode;
import com.mineaudio.track.TrackParser;

import net.kyori.adventure.key.Key;

/** regions.yml 解析：worlds（世界层）+ regions（区域）。 */
public final class RegionParser {

    private RegionParser() {
    }

    public record WorldLayer(Key music, List<Key> ambient) {
    }

    public record Parsed(Map<String, WorldLayer> worlds, List<AudioRegion> regions) {
    }

    public static Parsed parse(YamlNode root, Consumer<String> warn,
                               int defaultEnterDelayMs, int defaultExitDelayMs) {
        Map<String, WorldLayer> worlds = new LinkedHashMap<>();
        List<AudioRegion> regions = new ArrayList<>();
        if (root == null) {
            return new Parsed(worlds, regions);
        }

        YamlNode worldsNode = root.section("worlds");
        if (worldsNode != null) {
            for (String world : worldsNode.keys()) {
                YamlNode layer = worldsNode.section(world);
                if (layer == null) {
                    warn.accept("worlds." + world + " 必须是配置段");
                    continue;
                }
                Key music = parseTrackKey(layer.string("music", null), "worlds." + world, warn);
                List<Key> ambient = parseKeyList(layer.stringList("ambient"), "worlds." + world, warn);
                worlds.put(world, new WorldLayer(music, ambient));
            }
        }

        YamlNode regionsNode = root.section("regions");
        if (regionsNode != null) {
            for (String id : regionsNode.keys()) {
                YamlNode entry = regionsNode.section(id);
                if (entry == null) {
                    warn.accept(id + "：区域定义必须是配置段");
                    continue;
                }
                String world = entry.string("world", "").trim();
                if (world.isEmpty()) {
                    warn.accept(id + "：缺少 world");
                    continue;
                }
                RegionShape shape = parseShape(entry.section("shape"), id, warn);
                if (shape == null) continue;
                int priority = (int) entry.longValue("priority", 0);
                Key music = parseTrackKey(entry.string("music", null), id, warn);
                List<Key> ambient = parseKeyList(entry.stringList("ambient"), id, warn);
                int enterTicks = msToTicks((int) entry.longValue("enter-delay-ms", defaultEnterDelayMs));
                int exitTicks = msToTicks((int) entry.longValue("exit-delay-ms", defaultExitDelayMs));
                regions.add(new AudioRegion(id, world, shape, priority, music, ambient,
                        (int) Math.max(0, entry.longValue("fade-in-ms", 0)),
                        (int) Math.max(0, entry.longValue("fade-out-ms", 0)),
                        enterTicks, exitTicks));
            }
        }
        return new Parsed(worlds, regions);
    }

    public static int msToTicks(int ms) {
        return Math.max(0, (int) Math.ceil(ms / 50.0));
    }

    private static RegionShape parseShape(YamlNode shape, String id, Consumer<String> warn) {
        if (shape == null) {
            warn.accept(id + "：缺少 shape");
            return null;
        }
        String type = shape.string("type", "").trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "CUBOID" -> {
                YamlNode min = shape.section("min");
                YamlNode max = shape.section("max");
                if (min == null || max == null) {
                    warn.accept(id + "：CUBOID 需要 min 与 max");
                    return null;
                }
                return new RegionShape.Cuboid(
                        min.number("x", 0), min.number("y", 0), min.number("z", 0),
                        max.number("x", 0), max.number("y", 0), max.number("z", 0));
            }
            case "SPHERE" -> {
                YamlNode center = shape.section("center");
                if (center == null) {
                    warn.accept(id + "：SPHERE 需要 center");
                    return null;
                }
                double radius = shape.number("radius", 0);
                if (radius <= 0) {
                    warn.accept(id + "：SPHERE radius 必须大于 0");
                    return null;
                }
                return new RegionShape.Sphere(center.number("x", 0), center.number("y", 0),
                        center.number("z", 0), radius);
            }
            default -> {
                warn.accept(id + "：未知形状类型 " + type);
                return null;
            }
        }
    }

    private static Key parseTrackKey(String raw, String where, Consumer<String> warn) {
        if (raw == null || raw.isBlank()) return null;
        return TrackParser.keyOf(raw).orElseGet(() -> {
            warn.accept(where + "：非法曲目 ID " + raw);
            return null;
        });
    }

    private static List<Key> parseKeyList(List<String> raw, String where, Consumer<String> warn) {
        List<Key> keys = new ArrayList<>();
        for (String value : raw) {
            Key key = parseTrackKey(value, where, warn);
            if (key != null) keys.add(key);
        }
        return keys;
    }
}
