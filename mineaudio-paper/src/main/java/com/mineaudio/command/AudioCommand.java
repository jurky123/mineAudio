package com.mineaudio.command;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** /audio 命令：M1 提供 reload / debug（曲目与音效注册表），后续里程碑补齐 play/stop/region/emitter。 */
public final class AudioCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload", "debug");

    private final MineAudioPlugin plugin;

    public AudioCommand(MineAudioPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "debug" : args[0].toLowerCase();
        switch (sub) {
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

    private void debug(CommandSender sender) {
        sender.sendMessage(Component.text("MineAudio " + plugin.getPluginMeta().getVersion()
                + " | 曲目 " + plugin.trackRegistry().size()
                + " | 音效 " + plugin.cueRegistry().size(), NamedTextColor.YELLOW));
        for (AudioTrack track : plugin.trackRegistry().all()) {
            sender.sendMessage(Component.text("  - " + track.id() + " [" + track.bus() + "] "
                    + sourceName(track.primary()), NamedTextColor.GRAY));
        }
    }

    private static String sourceName(AudioSource source) {
        return switch (source) {
            case AudioSource.PackSound pack -> "PACK " + pack.sound();
            case AudioSource.VanillaSound vanilla -> "VANILLA " + vanilla.sound();
            case AudioSource.Nbs nbs -> "NBS " + nbs.file();
        };
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("用法：/audio reload|debug", NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return match(SUBCOMMANDS, args[0]);
        }
        return List.of();
    }

    private static List<String> match(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) result.add(option);
        }
        return result;
    }
}
