package com.mineaudio.emitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.mineaudio.config.YamlNode;
import com.mineaudio.track.TrackParser;

import net.kyori.adventure.key.Key;

/** emitters.yml 解析。 */
public final class EmitterParser {

    private EmitterParser() {
    }

    public static List<AudioEmitter> parse(YamlNode root, Consumer<String> warn) {
        List<AudioEmitter> emitters = new ArrayList<>();
        if (root == null || root.isEmpty()) return emitters;
        for (String id : root.keys()) {
            YamlNode entry = root.section(id);
            if (entry == null) {
                warn.accept(id + "：Emitter 定义必须是配置段");
                continue;
            }
            String world = entry.string("world", "").trim();
            if (world.isEmpty()) {
                warn.accept(id + "：缺少 world");
                continue;
            }
            Key track = TrackParser.keyOf(entry.string("track", "")).orElse(null);
            if (track == null) {
                warn.accept(id + "：缺少或非法 track");
                continue;
            }
            Trigger trigger;
            try {
                trigger = Trigger.valueOf(entry.string("trigger", "ALWAYS").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warn.accept(id + "：未知 trigger，使用 ALWAYS");
                trigger = Trigger.ALWAYS;
            }
            emitters.add(new AudioEmitter(id, world,
                    entry.number("x", 0), entry.number("y", 0), entry.number("z", 0),
                    track, entry.number("radius", 0), trigger, entry.bool("loop", false)));
        }
        return emitters;
    }
}
