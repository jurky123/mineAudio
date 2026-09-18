package com.mineaudio.track;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.bukkit.plugin.Plugin;

import com.mineaudio.api.AudioCue;
import com.mineaudio.config.YamlNode;

import net.kyori.adventure.key.Key;

/**
 * Cue 注册表：cues.yml 提供服务端定义，业务插件可用 {@link #register} 自带 Cue
 * （重复注册同一 key 时以最后一次为准，插件停用时整体注销）。
 */
public final class CueRegistry {

    private final Map<Key, AudioCue> yamlCues = new LinkedHashMap<>();
    private final Map<Key, AudioCue> runtimeCues = new LinkedHashMap<>();
    private final Map<Key, Plugin> runtimeOwners = new IdentityHashMap<>();
    private final Map<Plugin, Set<Key>> owners = new IdentityHashMap<>();

    public void load(YamlNode root, Consumer<String> warn) {
        yamlCues.clear();
        if (root == null || root.isEmpty()) return;
        for (String id : root.keys()) {
            YamlNode entry = root.section(id);
            if (entry == null) {
                warn.accept(id + "：Cue 定义必须是配置段");
                continue;
            }
            TrackParser.parseCue(id, entry, warn).ifPresent(cue -> yamlCues.put(cue.id(), cue));
        }
    }

    public void register(Plugin owner, AudioCue cue) {
        Plugin previous = runtimeOwners.put(cue.id(), owner);
        if (previous != null && previous != owner) {
            Set<Key> previousKeys = owners.get(previous);
            if (previousKeys != null) previousKeys.remove(cue.id());
        }
        runtimeCues.put(cue.id(), cue);
        owners.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(cue.id());
    }

    public void unregister(Plugin owner) {
        Set<Key> keys = owners.remove(owner);
        if (keys == null) return;
        for (Key key : keys) {
            if (runtimeOwners.get(key) == owner) {
                runtimeOwners.remove(key);
                runtimeCues.remove(key);
            }
        }
    }

    public Optional<AudioCue> get(Key key) {
        AudioCue cue = runtimeCues.get(key);
        return Optional.ofNullable(cue != null ? cue : yamlCues.get(key));
    }

    public boolean contains(Key key) {
        return runtimeCues.containsKey(key) || yamlCues.containsKey(key);
    }

    public Collection<AudioCue> all() {
        Map<Key, AudioCue> merged = new LinkedHashMap<>(yamlCues);
        merged.putAll(runtimeCues);
        return List.copyOf(merged.values());
    }

    public int size() {
        Set<Key> keys = new LinkedHashSet<>(yamlCues.keySet());
        keys.addAll(runtimeCues.keySet());
        return keys.size();
    }
}
