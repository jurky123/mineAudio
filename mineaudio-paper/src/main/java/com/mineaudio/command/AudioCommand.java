package com.mineaudio.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
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
import com.mineaudio.region.AudioRegion;
import com.mineaudio.region.RegionParser;
import com.mineaudio.region.RegionShape;
import com.mineaudio.track.TrackParser;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** /audio：点播、停止、区域管理、重载与调试。 */
public final class AudioCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("play", "stop", "region", "reload", "debug");
    private static final List<String> SCOPES = List.of("self", "player", "world", "global");
    private static final List<String> BUSES = List.of("MUSIC", "AMBIENT", "SFX", "UI");
    private static final List<String> REGION_ACTIONS = List.of("list", "pos1", "pos2", "create", "sphere",
            "delete", "settrack", "setambient", "setpriority");
    private static final Pattern REGION_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final MineAudioPlugin plugin;
    private final AudioOrchestrator orchestrator;
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public AudioCommand(MineAudioPlugin plugin, AudioOrchestrator orchestrator) {
        this.plugin = plugin;
        this.orchestrator = orchestrator;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("mineaudio.admin")) {
            sender.sendMessage(Component.text("没有权限", NamedTextColor.RED));
            return true;
        }
        String sub = args.length == 0 ? "debug" : args[0].toLowerCase();
        switch (sub) {
            case "play" -> play(sender, args);
            case "stop" -> stop(sender, args);
            case "region" -> region(sender, args);
            case "reload" -> {
                plugin.reloadAudio();
                sender.sendMessage(Component.text("MineAudio 配置已重载（"
                        + plugin.trackRegistry().size() + " 首曲目，"
                        + plugin.cueRegistry().size() + " 个音效，"
                        + plugin.regionManager().all().size() + " 个区域）", NamedTextColor.GREEN));
            }
            case "debug" -> debug(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    // ---------- 播放 ----------

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

    // ---------- 区域 ----------

    private void region(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("区域命令只能在游戏内使用", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sendRegionUsage(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                for (AudioRegion region : plugin.regionManager().all()) {
                    sender.sendMessage(Component.text("  " + region.id() + " @ " + region.world()
                            + " p" + region.priority()
                            + " music=" + (region.music() == null ? "-" : region.music())
                            + " ambient=" + region.ambient(), NamedTextColor.GRAY));
                }
                sender.sendMessage(Component.text("共 " + plugin.regionManager().all().size()
                        + " 个区域", NamedTextColor.GREEN));
            }
            case "pos1" -> {
                Location target = target(player);
                pos1.put(player.getUniqueId(), target);
                sender.sendMessage(Component.text("pos1 = " + format(target), NamedTextColor.GREEN));
            }
            case "pos2" -> {
                Location target = target(player);
                pos2.put(player.getUniqueId(), target);
                sender.sendMessage(Component.text("pos2 = " + format(target), NamedTextColor.GREEN));
            }
            case "create" -> createRegion(sender, player, args);
            case "sphere" -> createSphere(sender, player, args);
            case "delete" -> {
                if (regionExists(sender, args, 2)) return;
                plugin.regionManager().remove(args[2].toLowerCase(Locale.ROOT));
                plugin.regionManager().save();
                sender.sendMessage(Component.text("已删除区域 " + args[2], NamedTextColor.GREEN));
            }
            case "settrack" -> setTrack(sender, args);
            case "setambient" -> setAmbient(sender, args);
            case "setpriority" -> setPriority(sender, args);
            default -> sendRegionUsage(sender);
        }
    }

    private void createRegion(CommandSender sender, Player player, String[] args) {
        if (args.length < 3 || !validId(sender, args[2])) return;
        Location first = pos1.get(player.getUniqueId());
        Location second = pos2.get(player.getUniqueId());
        if (first == null || second == null) {
            sender.sendMessage(Component.text("请先用 /audio region pos1 与 pos2 选两个角", NamedTextColor.RED));
            return;
        }
        if (!first.getWorld().equals(second.getWorld())) {
            sender.sendMessage(Component.text("两个角必须在同一世界", NamedTextColor.RED));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        if (plugin.regionManager().region(id) != null) {
            sender.sendMessage(Component.text("区域已存在：" + id, NamedTextColor.RED));
            return;
        }
        AudioRegion region = new AudioRegion(id, first.getWorld().getName(),
                new RegionShape.Cuboid(first.getX(), first.getY(), first.getZ(),
                        second.getX(), second.getY(), second.getZ()),
                0, null, List.of(), 0, 0, defaultEnterTicks(), defaultExitTicks());
        plugin.regionManager().put(region);
        plugin.regionManager().save();
        sender.sendMessage(Component.text("已创建区域 " + id + "，用 /audio region settrack 设置音乐",
                NamedTextColor.GREEN));
    }

    private void createSphere(CommandSender sender, Player player, String[] args) {
        if (args.length < 4 || !validId(sender, args[2])) return;
        double radius;
        try {
            radius = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("半径必须是数字", NamedTextColor.RED));
            return;
        }
        if (radius <= 0) {
            sender.sendMessage(Component.text("半径必须大于 0", NamedTextColor.RED));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        if (plugin.regionManager().region(id) != null) {
            sender.sendMessage(Component.text("区域已存在：" + id, NamedTextColor.RED));
            return;
        }
        Location center = player.getLocation();
        AudioRegion region = new AudioRegion(id, center.getWorld().getName(),
                new RegionShape.Sphere(center.getX(), center.getY(), center.getZ(), radius),
                0, null, List.of(), 0, 0, defaultEnterTicks(), defaultExitTicks());
        plugin.regionManager().put(region);
        plugin.regionManager().save();
        sender.sendMessage(Component.text("已创建球形区域 " + id + "（半径 " + radius + "）",
                NamedTextColor.GREEN));
    }

    private void setTrack(CommandSender sender, String[] args) {
        if (args.length < 4 || !regionExists(sender, args, 2)) return;
        AudioRegion region = plugin.regionManager().region(args[2].toLowerCase(Locale.ROOT));
        Key music;
        if (args[3].equalsIgnoreCase("clear")) {
            music = null;
        } else {
            music = TrackParser.keyOf(args[3]).orElse(null);
            if (music == null || !plugin.trackRegistry().contains(music)) {
                sender.sendMessage(Component.text("未知曲目：" + args[3], NamedTextColor.RED));
                return;
            }
        }
        plugin.regionManager().put(withMusic(region, music));
        plugin.regionManager().save();
        sender.sendMessage(Component.text("区域 " + region.id() + " 音乐已设为 "
                + (music == null ? "无" : music), NamedTextColor.GREEN));
    }

    private void setAmbient(CommandSender sender, String[] args) {
        if (args.length < 4 || !regionExists(sender, args, 2)) return;
        AudioRegion region = plugin.regionManager().region(args[2].toLowerCase(Locale.ROOT));
        List<Key> ambient = new ArrayList<>();
        if (!args[3].equalsIgnoreCase("clear")) {
            for (int i = 3; i < args.length; i++) {
                Key key = TrackParser.keyOf(args[i]).orElse(null);
                if (key == null || !plugin.trackRegistry().contains(key)) {
                    sender.sendMessage(Component.text("未知曲目：" + args[i], NamedTextColor.RED));
                    return;
                }
                ambient.add(key);
            }
        }
        plugin.regionManager().put(withAmbient(region, ambient));
        plugin.regionManager().save();
        sender.sendMessage(Component.text("区域 " + region.id() + " 环境音已更新为 " + ambient,
                NamedTextColor.GREEN));
    }

    private void setPriority(CommandSender sender, String[] args) {
        if (args.length < 4 || !regionExists(sender, args, 2)) return;
        int priority;
        try {
            priority = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("优先级必须是整数", NamedTextColor.RED));
            return;
        }
        AudioRegion region = plugin.regionManager().region(args[2].toLowerCase(Locale.ROOT));
        plugin.regionManager().put(new AudioRegion(region.id(), region.world(), region.shape(),
                priority, region.music(), region.ambient(), region.fadeInMs(), region.fadeOutMs(),
                region.enterDelayTicks(), region.exitDelayTicks()));
        plugin.regionManager().save();
        sender.sendMessage(Component.text("区域 " + region.id() + " 优先级已设为 " + priority,
                NamedTextColor.GREEN));
    }

    private static AudioRegion withMusic(AudioRegion region, Key music) {
        return new AudioRegion(region.id(), region.world(), region.shape(), region.priority(),
                music, region.ambient(), region.fadeInMs(), region.fadeOutMs(),
                region.enterDelayTicks(), region.exitDelayTicks());
    }

    private static AudioRegion withAmbient(AudioRegion region, List<Key> ambient) {
        return new AudioRegion(region.id(), region.world(), region.shape(), region.priority(),
                region.music(), ambient, region.fadeInMs(), region.fadeOutMs(),
                region.enterDelayTicks(), region.exitDelayTicks());
    }

    private boolean regionExists(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            sender.sendMessage(Component.text("缺少区域 ID", NamedTextColor.RED));
            return false;
        }
        if (plugin.regionManager().region(args[index].toLowerCase(Locale.ROOT)) == null) {
            sender.sendMessage(Component.text("区域不存在：" + args[index], NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private boolean validId(CommandSender sender, String raw) {
        if (!REGION_ID.matcher(raw.toLowerCase(Locale.ROOT)).matches()) {
            sender.sendMessage(Component.text("区域 ID 只允许小写字母、数字、_ 与 -（≤32 字符）",
                    NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private int defaultEnterTicks() {
        return RegionParser.msToTicks(plugin.getConfig().getInt("region-enter-delay-ms", 300));
    }

    private int defaultExitTicks() {
        return RegionParser.msToTicks(plugin.getConfig().getInt("region-exit-delay-ms", 500));
    }

    private static Location target(Player player) {
        Block block = player.getTargetBlockExact(5);
        return block != null ? block.getLocation() : player.getLocation();
    }

    private static String format(Location location) {
        return location.getWorld().getName() + " " + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ();
    }

    // ---------- 调试 ----------

    private void debug(CommandSender sender) {
        sender.sendMessage(Component.text("MineAudio " + plugin.getPluginMeta().getVersion()
                + " | 曲目 " + plugin.trackRegistry().size()
                + " | 音效 " + plugin.cueRegistry().size()
                + " | 区域 " + plugin.regionManager().all().size(), NamedTextColor.YELLOW));
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
                "用法：/audio play <曲目> [范围] | /audio stop [Bus] [范围] |"
                        + " /audio region ... | /audio reload | /audio debug", NamedTextColor.RED));
    }

    private void sendRegionUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "用法：/audio region list|pos1|pos2|create <id>|sphere <id> <半径>|delete <id>|"
                        + "settrack <id> <曲目|clear>|setambient <id> <曲目...|clear>|"
                        + "setpriority <id> <值>", NamedTextColor.RED));
    }

    // ---------- Tab 补全 ----------

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
        if (sub.equals("region")) {
            return regionComplete(args);
        }
        return List.of();
    }

    private List<String> regionComplete(String[] args) {
        if (args.length == 2) {
            return match(REGION_ACTIONS, args[1]);
        }
        String action = args[1].toLowerCase();
        if (args.length == 3) {
            return switch (action) {
                case "create", "sphere", "delete", "settrack", "setambient", "setpriority" ->
                        match(regionIds(), args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4) {
            return switch (action) {
                case "sphere" -> List.of("8", "16", "24", "32", "48", "64");
                case "settrack" -> match(concat(List.of("clear"), trackIds()), args[3]);
                case "setambient" -> match(concat(List.of("clear"), trackIds()), args[3]);
                case "setpriority" -> List.of("0", "10", "20", "30");
                default -> List.of();
            };
        }
        if (action.equals("setambient")) {
            return match(concat(List.of("clear"), trackIds()), args[args.length - 1]);
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

    private List<String> regionIds() {
        List<String> ids = new ArrayList<>();
        for (AudioRegion region : plugin.regionManager().all()) ids.add(region.id());
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
