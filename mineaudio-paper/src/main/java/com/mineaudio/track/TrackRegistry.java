package com.mineaudio.track;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.mineaudio.api.AudioTrack;
import com.mineaudio.config.YamlNode;

import net.kyori.adventure.key.Key;

/** 曲目注册表：由 tracks.yml 加载，重载时整体替换。 */
public final class TrackRegistry {

    private final Map<Key, AudioTrack> tracks = new LinkedHashMap<>();

    public void load(YamlNode root, Consumer<String> warn) {
        tracks.clear();
        if (root == null || root.isEmpty()) return;
        for (String id : root.keys()) {
            YamlNode entry = root.section(id);
            if (entry == null) {
                warn.accept(id + "：曲目定义必须是配置段");
                continue;
            }
            TrackParser.parseTrack(id, entry, warn).ifPresent(track -> tracks.put(track.id(), track));
        }
    }

    public Optional<AudioTrack> get(Key key) {
        return Optional.ofNullable(tracks.get(key));
    }

    public boolean contains(Key key) {
        return tracks.containsKey(key);
    }

    public Collection<AudioTrack> all() {
        return List.copyOf(tracks.values());
    }

    public int size() {
        return tracks.size();
    }
}
