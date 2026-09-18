package com.mineaudio.command;

import com.mineaudio.MineAudioPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** /audio 命令：M0 只提供 reload / debug 占位，后续里程碑补齐 play/stop/region/emitter。 */
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
                plugin.reloadConfig();
                sender.sendMessage(Component.text("MineAudio 配置已重载", NamedTextColor.GREEN));
            }
            case "debug" -> sender.sendMessage(Component.text(
                    "MineAudio " + plugin.getPluginMeta().getVersion() + "：尚未初始化音频管理器", NamedTextColor.YELLOW));
            default -> sendUsage(sender);
        }
        return true;
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
