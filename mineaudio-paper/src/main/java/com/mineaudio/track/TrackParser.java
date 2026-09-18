package com.mineaudio.track;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioCue;
import com.mineaudio.api.AudioMetadata;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackOptions;
import com.mineaudio.config.YamlNode;

import net.kyori.adventure.key.Key;

/** tracks.yml / cues.yml 解析：一个条目 = 一个来源 + 可选 fallback + 播放参数。 */
public final class TrackParser {

    private TrackParser() {
    }

    public static Optional<AudioTrack> parseTrack(String id, YamlNode entry, Consumer<String> warn) {
        Key key = parseId(id, warn);
        if (key == null) return Optional.empty();
        Optional<AudioSource> primary = parseSource(sourceSection(entry), id, warn);
        if (primary.isEmpty()) return Optional.empty();
        AudioSource fallback = parseFallback(entry, id, warn);
        AudioBus bus = parseBus(entry.string("bus", null), AudioBus.MUSIC, id, warn);
        AudioMetadata metadata = new AudioMetadata(entry.string("title", ""), entry.string("author", ""),
                entry.longValue("duration", -1));
        return Optional.of(new AudioTrack(key, bus, primary.get(), fallback, parseOptions(entry), metadata));
    }

    public static Optional<AudioCue> parseCue(String id, YamlNode entry, Consumer<String> warn) {
        Key key = parseId(id, warn);
        if (key == null) return Optional.empty();
        Optional<AudioSource> primary = parseSource(sourceSection(entry), id, warn);
        if (primary.isEmpty()) return Optional.empty();
        AudioSource fallback = parseFallback(entry, id, warn);
        AudioBus bus = parseBus(entry.string("bus", null), AudioBus.SFX, id, warn);
        return Optional.of(new AudioCue(key, bus, primary.get(), fallback, parseOptions(entry)));
    }

    /** 可选 fallback：未配置时静默返回 null。 */
    private static AudioSource parseFallback(YamlNode entry, String where, Consumer<String> warn) {
        YamlNode section = entry.section("fallback");
        if (section == null) return null;
        return parseSource(section, where + ".fallback", warn).orElse(null);
    }

    /** 条目本身带 type 时直接作为 primary，否则取 primary 子节点。 */
    private static YamlNode sourceSection(YamlNode entry) {
        return entry.has("type") ? entry : entry.section("primary");
    }

    public static Optional<AudioSource> parseSource(YamlNode section, String where, Consumer<String> warn) {
        if (section == null) {
            warn.accept(where + "：缺少声音来源（type / primary）");
            return Optional.empty();
        }
        String type = section.string("type", "").trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "PACK" -> {
                Key sound = parseSound(section.string("sound", null), where, warn);
                return sound == null ? Optional.empty() : Optional.of(new AudioSource.PackSound(sound));
            }
            case "VANILLA" -> {
                Key sound = parseSound(section.string("sound", null), where, warn);
                return sound == null ? Optional.empty() : Optional.of(new AudioSource.VanillaSound(sound));
            }
            case "NBS" -> {
                String file = section.string("file", "").trim();
                if (file.isEmpty() || file.contains("/") || file.contains("\\") || file.contains("..")
                        || !file.toLowerCase(Locale.ROOT).endsWith(".nbs")) {
                    warn.accept(where + "：NBS 文件名非法（只允许 nbs/ 下的 .nbs 文件名）：" + file);
                    return Optional.empty();
                }
                return Optional.of(new AudioSource.Nbs(file));
            }
            default -> {
                warn.accept(where + "：未知来源类型 " + type);
                return Optional.empty();
            }
        }
    }

    /** 把字符串转成完整 Key：无命名空间时补 mineaudio。命令与业务侧共用。 */
    public static Optional<Key> keyOf(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String value = id.trim().toLowerCase(Locale.ROOT);
        if (!value.contains(":")) {
            value = "mineaudio:" + value;
        } else {
            String[] parts = value.split(":", 2);
            if (parts[0].isEmpty() || parts[1].isEmpty()) return Optional.empty();
        }
        try {
            return Optional.of(Key.key(value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Key parseId(String id, Consumer<String> warn) {
        Optional<Key> key = keyOf(id);
        if (key.isEmpty()) {
            warn.accept("非法曲目 ID：" + id);
            return null;
        }
        return key.get();
    }

    private static Key parseSound(String raw, String where, Consumer<String> warn) {
        if (raw == null || raw.isBlank()) {
            warn.accept(where + "：缺少 sound");
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!value.contains(":")) value = "minecraft:" + value;
        try {
            return Key.key(value);
        } catch (RuntimeException e) {
            warn.accept(where + "：非法声音 key " + raw);
            return null;
        }
    }

    private static AudioBus parseBus(String raw, AudioBus fallback, String where, Consumer<String> warn) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return AudioBus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warn.accept(where + "：未知 Bus " + raw + "，使用 " + fallback);
            return fallback;
        }
    }

    private static PlaybackOptions parseOptions(YamlNode entry) {
        PlaybackOptions.Builder builder = PlaybackOptions.builder();
        double volume = entry.number("volume", 1.0);
        if (volume > 0) builder.volume((float) volume);
        double pitch = entry.number("pitch", 1.0);
        if (pitch > 0) builder.pitch((float) pitch);
        if (entry.bool("loop", false)) builder.loop(true);
        builder.fadeInMs((int) Math.max(0, entry.longValue("fade-in-ms", 0)));
        builder.fadeOutMs((int) Math.max(0, entry.longValue("fade-out-ms", 0)));
        return builder.build();
    }
}
