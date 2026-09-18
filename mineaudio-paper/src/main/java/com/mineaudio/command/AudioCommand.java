package com.mineaudio.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.Audience;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.playback.AudioOrchestrator;
import com.mineaudio.track.TrackParser;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** /audio：点播、停止、重载与调试。 */
public final class AudioCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("play", "stop", "reload", "debug");
    private static final List<String> SCOPES = List.of("self", "player", "world", "global");
    private static final List<String> BUSES = List.of("MUSIC", "AMBIENT", "SFX", "UI");

    private final MineAudioPlugin plugin;
    private final AudioOrchestrator orchestrator;

    public AudioCommand(MineAudioPlugin plugin, AudioOrchestrator orchestrator) {
        this.plugin = plugin;
        this.orchestrator = orchestrator;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "debug" : args[0].toLowerCase();
        switch (sub) {
            case "play" -> play(sender, args);
            case "stop" -> stop(sender, args);
            case "reload" -> {
                plugin.reloadAudio();
                sender.sendMessage(Component.text("MineAudio 配置已重载（"
                        + plugin.trackRegistry().size() + " 首曲目，"
                        + plugin.cueRegistry().size() + " 个音效）", NamedTextColor.GREEN));
            }
            case "debug" -> debug(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void play(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/audio play <曲目> [self|player <玩家>|world <世界>|global]",
                    NamedTextColor.RED));
            return;
        }
        Key key = TrackParser.keyOf(args[1]).orElse(null);
        if (key == null || !plugin.trackRegistry().contains(key)) {
            sender.sendMessage(Component.text("未知曲目：" + args[1], NamedTextColor.RED));
            return;
        }
        Audience audience = resolveAudience(sender, args, 2);
        if (audience == null) return;
        PlaybackHandle handle = orchestrator.play(audience, key);
        sender.sendMessage(Component.text("播放 " + key + " → " + describeAudience(audience)
                + "（" + handle.state() + "）", NamedTextColor.GREEN));
    }

    private void stop(CommandSender sender, String[] args) {
        AudioBus bus = null;
        int index = 1;
        if (args.length > 1) {
            try {
                bus = AudioBus.valueOf(args[1].toUpperCase());
                index = 2;
            } catch (IllegalArgumentException ignored) {
                // 第一个参数不是 Bus，按范围解析
            }
        }
        Audience audience = resolveAudience(sender, args, index);
        if (audience == null) return;
        if (bus == null) {
            orchestrator.stopAll(audience);
        } else {
            orchestrator.stop(audience, bus);
        }
        sender.sendMessage(Component.text("已停止 " + (bus == null ? "全部" : bus) + " 播放", NamedTextColor.GREEN));
    }

    private void debug(CommandSender sender) {
        sender.sendMessage(Component.text("MineAudio " + plugin.getPluginMeta().getVersion()
                + " | 曲目 " + plugin.trackRegistry().size()
                + " | 音效 " + plugin.cueRegistry().size(), NamedTextColor.YELLOW));
        for (AudioTrack track : plugin.trackRegistry().all()) {
            sender.sendMessage(Component.text("  - " + track.id() + " [" + track.bus() + "] "
                    + sourceName(track.primary()), NamedTextColor.GRAY));
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (String line : orchestrator.describe(player)) {
                sender.sendMessage(Component.text("  [" + player.getName() + "] " + line, NamedTextColor.GRAY));
            }
        }
        if (sender instanceof Player player) {
            AudioCapabilities capabilities = orchestrator.capabilities(player);
            sender.sendMessage(Component.text("  能力：vanillaClient=" + capabilities.vanillaClient()
                    + " loop=" + capabilities.loop()
                    + " positional=" + capabilities.positional(), NamedTextColor.GRAY));
        }
    }

    private Audience resolveAudience(CommandSender sender, String[] args, int index) {
        String scope = args.length > index ? args[index].toLowerCase() : "self";
        switch (scope) {
            case "self" -> {
                if (sender instanceof Player player) {
                    return Audience.player(player);
                }
                sender.sendMessage(Component.text("控制台请指定范围：player <玩家> / world <世界> / global",
                        NamedTextColor.RED));
                return null;
            }
            case "player" -> {
                Player target = args.length > index + 1 ? Bukkit.getPlayerExact(args[index + 1]) : null;
                if (target == null) {
                    sender.sendMessage(Component.text("玩家不在线", NamedTextColor.RED));
                    return null;
                }
                return Audience.player(target);
            }
            case "world" -> {
                World world = args.length > index + 1 ? Bukkit.getWorld(args[index + 1]) : null;
                if (world == null) {
                    sender.sendMessage(Component.text("世界不存在", NamedTextColor.RED));
                    return null;
                }
                return Audience.world(world);
            }
            case "global" -> {
                return Audience.global();
            }
            default -> {
                sender.sendMessage(Component.text("未知范围：" + scope, NamedTextColor.RED));
                return null;
            }
        }
    }

    private static String describeAudience(Audience audience) {
        int size = audience.players().size();
        return size == 1 ? "该玩家" : size + " 名玩家";
    }

    private static String sourceName(AudioSource source) {
        return switch (source) {
            case AudioSource.PackSound pack -> "PACK " + pack.sound();
            case AudioSource.VanillaSound vanilla -> "VANILLA " + vanilla.sound();
            case AudioSource.Nbs nbs -> "NBS " + nbs.file();
        };
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "用法：/audio play <曲目> [self|player <玩家>|world <世界>|global] |"
                        + " /audio stop [Bus] [范围] | /audio reload | /audio debug", NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return match(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("play")) {
            if (args.length == 2) return match(trackIds(), args[1]);
            if (args.length == 3) return match(SCOPES, args[2]);
            if (args.length == 4) return scopeTarget(args, 2);
            return List.of();
        }
        if (sub.equals("stop")) {
            if (args.length == 2) return match(concat(BUSES, SCOPES), args[1]);
            if (args.length == 3) return scopeTarget(args, isBus(args[1]) ? 2 : 1);
            if (args.length == 4 && isBus(args[1])) return scopeTarget(args, 2);
            return List.of();
        }
        return List.of();
    }

    /** 补全范围参数后面的目标：args[scopeIndex] 是范围，args[scopeIndex+1] 是正在输入的目标。 */
    private List<String> scopeTarget(String[] args, int scopeIndex) {
        if (args.length != scopeIndex + 2) return List.of();
        String scope = args[scopeIndex].toLowerCase();
        if (scope.equals("player")) {
            return match(onlineNames(), args[scopeIndex + 1]);
        }
        if (scope.equals("world")) {
            return match(worldNames(), args[scopeIndex + 1]);
        }
        return List.of();
    }

    private static boolean isBus(String value) {
        return value != null && BUSES.contains(value.toUpperCase());
    }

    private List<String> trackIds() {
        List<String> ids = new ArrayList<>();
        for (AudioTrack track : plugin.trackRegistry().all()) {
            Key id = track.id();
            ids.add("mineaudio".equals(id.namespace()) ? id.value() : id.asString());
        }
        return ids;
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
        return names;
    }

    private static List<String> worldNames() {
        List<String> names = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) names.add(world.getName());
        return names;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static List<String> match(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) result.add(option);
        }
        return result;
    }
}
