package com.mineaudio.stream;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import com.mineaudio.MineAudioPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.md_5.bungee.api.chat.BaseComponent;

/**
 * MoeMusic 当前曲目查询（best effort）。
 * <p>
 * MoeMusic 不对外提供播放状态 API，这里用带捕获的 CommandSender 执行 {@code /music queue}，
 * 解析输出里的 “正在播放” 行与元信息行；解析失败或格式变化时返回空，不影响其他功能。
 */
public final class MoeMusicNowPlaying {

    private static final long CACHE_MS = 2000;

    public record NowPlaying(String title, String artist) {
    }

    private final MineAudioPlugin plugin;
    private NowPlaying cached;
    private long cachedAt;

    public MoeMusicNowPlaying(MineAudioPlugin plugin) {
        this.plugin = plugin;
    }

    public Optional<NowPlaying> query() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MS) {
            return Optional.ofNullable(cached);
        }
        cached = fetch();
        cachedAt = now;
        return Optional.ofNullable(cached);
    }

    private NowPlaying fetch() {
        Plugin moeMusic = Bukkit.getPluginManager().getPlugin("MoeMusic");
        if (moeMusic == null || !moeMusic.isEnabled()) return null;
        List<Component> captured = new ArrayList<>();
        CommandSender sender = captureSender(captured);
        try {
            if (!Bukkit.dispatchCommand(sender, "music queue")) return null;
        } catch (Throwable t) {
            if (plugin.debug()) {
                plugin.getLogger().info("[stream] 查询 MoeMusic 队列失败：" + t);
            }
            return null;
        }
        List<String> lines = captured.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
        return parse(lines).orElse(null);
    }

    /** 从 /music queue 输出解析当前曲目（第一行是“正在播放”，下一行是艺术家/时长/点歌人）。 */
    static Optional<NowPlaying> parse(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = stripColors(lines.get(i)).trim();
            if (trimmed.isEmpty() || isMetaLine(trimmed)) continue;
            int colon = trimmed.indexOf(": ");
            if (colon <= 0) continue;
            String title = trimmed.substring(colon + 2).trim();
            if (title.isEmpty()) continue;
            String artist = "";
            if (i + 1 < lines.size()) {
                artist = artistOf(stripColors(lines.get(i + 1)).trim());
            }
            return Optional.of(new NowPlaying(title, artist));
        }
        return Optional.empty();
    }

    private static boolean isMetaLine(String trimmed) {
        return trimmed.matches("^\\S+\\s+\\d+:\\d+.*") || trimmed.matches(".*@\\S+\\s*$");
    }

    private static String artistOf(String metaLine) {
        StringBuilder artist = new StringBuilder();
        for (String token : metaLine.split("\\s+")) {
            if (token.isEmpty() || token.matches("\\d+:\\d+.*") || token.startsWith("@")) break;
            if (artist.length() > 0) artist.append(' ');
            artist.append(token);
        }
        return artist.toString();
    }

    private static String stripColors(String line) {
        return line == null ? "" : line.replaceAll("\u00a7.", "");
    }

    /** 可捕获 sendMessage 的 CommandSender，权限全部放行（仅用于内部查询）。 */
    private static CommandSender captureSender(List<Component> captured) {
        return (CommandSender) Proxy.newProxyInstance(
                MoeMusicNowPlaying.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "sendMessage" -> {
                            if (args != null && args.length > 0) {
                                collect(captured, args[0]);
                            }
                            return null;
                        }
                        case "name" -> {
                            return Component.text("MineAudio");
                        }
                        case "getName" -> {
                            return "MineAudio";
                        }
                        case "getServer" -> {
                            return Bukkit.getServer();
                        }
                        case "hasPermission", "isPermissionSet", "isOp" -> {
                            return true;
                        }
                        case "getEffectivePermissions" -> {
                            return Collections.emptySet();
                        }
                        case "getLocale", "locale" -> {
                            return Locale.SIMPLIFIED_CHINESE;
                        }
                        case "getScheduler" -> {
                            return Bukkit.getScheduler();
                        }
                        case "getScoreboard" -> {
                            return Bukkit.getScoreboardManager().getMainScoreboard();
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "toString" -> {
                            return "MineAudioQueueCapture";
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                });
    }

    private static void collect(List<Component> captured, Object message) {
        if (message instanceof Component component) {
            captured.add(component);
        } else if (message instanceof String text) {
            captured.add(Component.text(text));
        } else if (message instanceof BaseComponent[] components) {
            captured.add(LegacyComponentSerializer.legacySection()
                    .deserialize(BaseComponent.toLegacyText(components)));
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
