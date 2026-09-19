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
import com.mineaudio.client.ClientConnectionRegistry;
import com.mineaudio.client.ClientPlaybackStateCache;
import com.mineaudio.emitter.AudioEmitter;
import com.mineaudio.emitter.Trigger;
import com.mineaudio.playback.AudioOrchestrator;
import com.mineaudio.region.AudioRegion;
import com.mineaudio.region.RegionParser;
import com.mineaudio.region.RegionShape;
import com.mineaudio.track.TrackParser;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** /mineaudio：点播、停止、区域管理、重载与调试。 */
public final class AudioCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("play", "stop", "pause", "resume", "seek", "volume",
            "search", "queue", "region", "emitter", "ui", "hud", "reload", "debug");
    private static final List<String> QUEUE_ACTIONS = List.of("add", "play", "list", "clear");
    private static final List<String> SCOPES = List.of("self", "player", "world", "global");
    private static final List<String> BUSES = List.of("MUSIC", "AMBIENT", "SFX", "UI");
    private static final List<String> REGION_ACTIONS = List.of("list", "pos1", "pos2", "create", "sphere",
            "delete", "settrack", "setambient", "setpriority");
    private static final List<String> EMITTER_ACTIONS = List.of("list", "create", "bind", "delete", "settrack",
            "settrigger", "setradius", "start", "stop");
    private static final List<String> TRIGGERS = List.of("ALWAYS", "REDSTONE", "COMMAND", "INTERACT");
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
            case "pause" -> pauseResume(sender, args, true);
            case "resume" -> pauseResume(sender, args, false);
            case "seek" -> seek(sender, args);
            case "volume" -> volume(sender, args);
            case "search" -> search(sender, args);
            case "queue" -> queue(sender, args);
            case "region" -> region(sender, args);
            case "emitter" -> emitter(sender, args);
            case "ui" -> {
                if (sender instanceof Player player) {
                    if (plugin.audioUi().open(player)) {
                        sender.sendMessage(Component.text("已打开音乐界面", NamedTextColor.GREEN));
                    } else {
                        sender.sendMessage(Component.text("需要 MineUI 客户端（0.7.0+）才能打开音乐界面",
                                NamedTextColor.YELLOW));
                    }
                } else {
                    sender.sendMessage(Component.text("该命令只能在游戏内使用", NamedTextColor.RED));
                }
            }
            case "hud" -> {
                if (sender instanceof Player player) {
                    if (!plugin.audioUi().hudSupported(player)) {
                        sender.sendMessage(Component.text("需要 MineUI 0.11+ 客户端才能显示 HUD",
                                NamedTextColor.YELLOW));
                    } else if (plugin.audioUi().toggleHud(player)) {
                        sender.sendMessage(Component.text("已开启“正在播放”HUD（关闭界面后可见，F6 可开关）",
                                NamedTextColor.GREEN));
                    } else {
                        sender.sendMessage(Component.text("已关闭“正在播放”HUD", NamedTextColor.GREEN));
                    }
                } else {
                    sender.sendMessage(Component.text("该命令只能在游戏内使用", NamedTextColor.RED));
                }
            }
            case "reload" -> {
                plugin.reloadAudio();
                sender.sendMessage(Component.text("MineAudio 配置已重载（"
                        + plugin.trackRegistry().size() + " 首曲目，"
                        + plugin.cueRegistry().size() + " 个音效，"
                        + plugin.regionManager().all().size() + " 个区域，"
                        + plugin.emitterManager().all().size() + " 个发声点）", NamedTextColor.GREEN));
            }
            case "debug" -> debug(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    // ---------- 播放 ----------

    private void play(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/mineaudio play <曲目> [self|player <玩家>|world <世界>|global]",
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

    // ---------- 搜索 / 点歌队列 ----------

    private void search(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/mineaudio search <关键词>", NamedTextColor.RED));
            return;
        }
        if (plugin.searchService() == null) {
            sender.sendMessage(Component.text("搜索服务不可用（resolvers.netease.enabled=false）", NamedTextColor.RED));
            return;
        }
        String keyword = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        sender.sendMessage(Component.text("正在搜索：" + keyword + " …", NamedTextColor.GRAY));
        plugin.searchService().search(keyword).whenComplete((results, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        sender.sendMessage(Component.text("搜索失败：" + describeError(error), NamedTextColor.RED));
                        return;
                    }
                    if (sender instanceof Player player) {
                        orchestrator.setSearchResults(player, results);
                    }
                    if (results.isEmpty()) {
                        sender.sendMessage(Component.text("没有找到结果", NamedTextColor.YELLOW));
                        return;
                    }
                    sender.sendMessage(Component.text("搜索结果（" + results.size() + "）：", NamedTextColor.YELLOW));
                    for (int i = 0; i < results.size(); i++) {
                        var result = results.get(i);
                        boolean hasCover = result.coverUrl() != null && !result.coverUrl().isBlank();
                        sender.sendMessage(Component.text("  " + (i + 1) + ". " + result.title()
                                + " - " + result.artist() + "  [" + result.note()
                                + (hasCover ? "·封面" : "·无封面") + "]",
                                result.playable() ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY));
                    }
                    sender.sendMessage(Component.text(
                            "点歌：/mineaudio queue add <序号>；立即播放：/mineaudio queue play <序号>",
                            NamedTextColor.GRAY));
                }));
    }

    private void queue(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("队列命令只能在游戏内使用", NamedTextColor.RED));
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "list";
        switch (action) {
            case "list" -> {
                List<AudioTrack> queue = orchestrator.queue(player);
                if (queue.isEmpty()) {
                    sender.sendMessage(Component.text("队列为空（上限 " + orchestrator.queueLimit() + " 首）",
                            NamedTextColor.GRAY));
                    return;
                }
                sender.sendMessage(Component.text("点歌队列（" + queue.size() + "/"
                        + orchestrator.queueLimit() + "）：", NamedTextColor.YELLOW));
                for (int i = 0; i < queue.size(); i++) {
                    AudioTrack track = queue.get(i);
                    sender.sendMessage(Component.text("  " + (i + 1) + ". " + track.metadata().title()
                            + " - " + track.metadata().author(), NamedTextColor.WHITE));
                }
            }
            case "clear" -> {
                orchestrator.clearQueue(player);
                sender.sendMessage(Component.text("已清空点歌队列", NamedTextColor.GREEN));
            }
            case "add", "play" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("用法：/mineaudio queue " + action + " <序号>", NamedTextColor.RED));
                    return;
                }
                int index;
                try {
                    index = Integer.parseInt(args[2]) - 1;
                } catch (NumberFormatException e) {
                    index = -1;
                }
                List<com.mineaudio.stream.search.SearchResult> results = orchestrator.searchResults(player);
                if (index < 0 || index >= results.size()) {
                    sender.sendMessage(Component.text("序号无效（先 /mineaudio search <关键词>）", NamedTextColor.RED));
                    return;
                }
                com.mineaudio.stream.search.SearchResult result = results.get(index);
                if (!result.playable()) {
                    sender.sendMessage(Component.text("该曲目不可播放（" + result.note() + "）", NamedTextColor.RED));
                    return;
                }
                AudioTrack track = com.mineaudio.playback.AudioOrchestrator.searchTrack(result);
                if ("play".equals(action)) {
                    orchestrator.playNow(player, track);
                    sender.sendMessage(Component.text("立即播放：" + result.title() + " - " + result.artist(),
                            NamedTextColor.GREEN));
                } else if (orchestrator.enqueue(player, track)
                        == com.mineaudio.playback.AudioOrchestrator.EnqueueResult.FULL) {
                    sender.sendMessage(Component.text("队列已满（每人最多 " + orchestrator.queueLimit() + " 首）",
                            NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("已加入队列：" + result.title() + " - " + result.artist()
                            + "（" + orchestrator.queue(player).size() + "/" + orchestrator.queueLimit() + "）",
                            NamedTextColor.GREEN));
                }
            }
            default -> sender.sendMessage(Component.text("用法：/mineaudio queue add|play <序号> | list | clear",
                    NamedTextColor.RED));
        }
    }

    private static String describeError(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        if (cause instanceof com.mineaudio.stream.resolve.ResolveException resolve) {
            return resolve.kind() + "：" + resolve.getMessage();
        }
        return String.valueOf(cause.getMessage());
    }

    // ---------- 客户端控制 ----------

    private void pauseResume(CommandSender sender, String[] args, boolean pause) {
        Player target = targetPlayer(sender, args, 1);
        if (target == null) return;
        boolean ok = pause ? orchestrator.pauseMusic(target) : orchestrator.resumeMusic(target);
        sender.sendMessage(Component.text((ok ? "已" : "无法") + (pause ? "暂停 " : "继续 ") + target.getName(),
                ok ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void seek(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/mineaudio seek <毫秒|mm:ss> [玩家]", NamedTextColor.RED));
            return;
        }
        long positionMs = parseTime(args[1]);
        if (positionMs < 0) {
            sender.sendMessage(Component.text("时间格式：毫秒或 mm:ss / hh:mm:ss", NamedTextColor.RED));
            return;
        }
        Player target = targetPlayer(sender, args, 2);
        if (target == null) return;
        com.mineaudio.playback.PlaybackSession session = orchestrator.currentMusic(target);
        boolean ok = session != null && session.handle().seek(java.time.Duration.ofMillis(positionMs));
        sender.sendMessage(Component.text((ok ? "已定位到 " + positionMs + "ms → " : "当前没有可定位的音乐 → ")
                + target.getName(), ok ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void volume(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/mineaudio volume <0-100> [玩家]", NamedTextColor.RED));
            return;
        }
        int percent;
        try {
            percent = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            percent = -1;
        }
        if (percent < 0 || percent > 100) {
            sender.sendMessage(Component.text("音量范围 0-100", NamedTextColor.RED));
            return;
        }
        Player target = targetPlayer(sender, args, 2);
        if (target == null) return;
        com.mineaudio.playback.PlaybackSession session = orchestrator.currentMusic(target);
        boolean ok = session != null && session.handle().setVolume(percent / 100f);
        sender.sendMessage(Component.text((ok ? "音量已设为 " + percent + "% → " : "当前没有可调音量的音乐 → ")
                + target.getName(), ok ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private Player targetPlayer(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player named = org.bukkit.Bukkit.getPlayerExact(args[index]);
            if (named == null) {
                sender.sendMessage(Component.text("玩家不在线：" + args[index], NamedTextColor.RED));
            }
            return named;
        }
        if (sender instanceof Player player) return player;
        sender.sendMessage(Component.text("控制台使用时请指定玩家", NamedTextColor.RED));
        return null;
    }

    private static long parseTime(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // 继续尝试 mm:ss
        }
        String[] parts = value.split(":");
        try {
            if (parts.length == 2) {
                return (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
            }
            if (parts.length == 3) {
                return (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60
                        + Long.parseLong(parts[2])) * 1000;
            }
        } catch (NumberFormatException ignored) {
            // 落到 -1
        }
        return -1;
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
            sender.sendMessage(Component.text("请先用 /mineaudio region pos1 与 pos2 选两个角", NamedTextColor.RED));
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
        sender.sendMessage(Component.text("已创建区域 " + id + "，用 /mineaudio region settrack 设置音乐",
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

    // ---------- 发声点 ----------

    private void emitter(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendEmitterUsage(sender);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            for (AudioEmitter emitter : plugin.emitterManager().all()) {
                sender.sendMessage(Component.text("  " + emitter.id() + " @ " + emitter.world()
                        + " " + (long) emitter.x() + "," + (long) emitter.y() + "," + (long) emitter.z()
                        + " track=" + emitter.track() + " r=" + (long) emitter.radius()
                        + " " + emitter.trigger(), NamedTextColor.GRAY));
            }
            sender.sendMessage(Component.text("共 " + plugin.emitterManager().all().size()
                    + " 个发声点", NamedTextColor.GREEN));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该命令只能在游戏内使用", NamedTextColor.RED));
            return;
        }
        switch (action) {
            case "create" -> createEmitter(sender, player, args);
            case "bind" -> bindEmitter(sender, player, args);
            case "delete" -> {
                if (!emitterExists(sender, args, 2)) return;
                plugin.emitterManager().remove(args[2].toLowerCase(Locale.ROOT));
                plugin.emitterManager().save();
                sender.sendMessage(Component.text("已删除发声点 " + args[2], NamedTextColor.GREEN));
            }
            case "settrack" -> setEmitterTrack(sender, args);
            case "settrigger" -> setEmitterTrigger(sender, args);
            case "setradius" -> setEmitterRadius(sender, args);
            case "start" -> {
                if (!emitterExists(sender, args, 2)) return;
                boolean started = plugin.emitterManager().start(args[2].toLowerCase(Locale.ROOT));
                sender.sendMessage(Component.text(started ? "已启动 " + args[2] : "启动失败（可能已在播放）",
                        started ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            case "stop" -> {
                if (!emitterExists(sender, args, 2)) return;
                boolean stopped = plugin.emitterManager().stop(args[2].toLowerCase(Locale.ROOT));
                sender.sendMessage(Component.text(stopped ? "已停止 " + args[2] : "当前未在播放",
                        stopped ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            default -> sendEmitterUsage(sender);
        }
    }

    private void createEmitter(CommandSender sender, Player player, String[] args) {
        if (args.length < 4 || !validId(sender, args[2])) return;
        Block block = player.getTargetBlockExact(5);
        if (block == null) {
            sender.sendMessage(Component.text("请对准一个方块", NamedTextColor.RED));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        if (plugin.emitterManager().emitter(id) != null) {
            sender.sendMessage(Component.text("发声点已存在：" + id, NamedTextColor.RED));
            return;
        }
        Key track = TrackParser.keyOf(args[3]).orElse(null);
        if (track == null || !plugin.trackRegistry().contains(track)) {
            sender.sendMessage(Component.text("未知曲目：" + args[3], NamedTextColor.RED));
            return;
        }
        plugin.emitterManager().put(new AudioEmitter(id, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), track, 0, Trigger.REDSTONE, false));
        plugin.emitterManager().save();
        sender.sendMessage(Component.text("已创建发声点 " + id + "（REDSTONE 触发）", NamedTextColor.GREEN));
    }

    private void bindEmitter(CommandSender sender, Player player, String[] args) {
        if (!emitterExists(sender, args, 2)) return;
        Block block = player.getTargetBlockExact(5);
        if (block == null) {
            sender.sendMessage(Component.text("请对准一个方块", NamedTextColor.RED));
            return;
        }
        AudioEmitter emitter = plugin.emitterManager().emitter(args[2].toLowerCase(Locale.ROOT));
        plugin.emitterManager().put(new AudioEmitter(emitter.id(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), emitter.track(), emitter.radius(),
                emitter.trigger(), emitter.loop()));
        plugin.emitterManager().save();
        sender.sendMessage(Component.text("发声点 " + emitter.id() + " 已绑定到 "
                + block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ(),
                NamedTextColor.GREEN));
    }

    private void setEmitterTrack(CommandSender sender, String[] args) {
        if (args.length < 4 || !emitterExists(sender, args, 2)) return;
        Key track = TrackParser.keyOf(args[3]).orElse(null);
        if (track == null || !plugin.trackRegistry().contains(track)) {
            sender.sendMessage(Component.text("未知曲目：" + args[3], NamedTextColor.RED));
            return;
        }
        AudioEmitter emitter = plugin.emitterManager().emitter(args[2].toLowerCase(Locale.ROOT));
        plugin.emitterManager().put(withTrack(emitter, track));
        plugin.emitterManager().save();
        sender.sendMessage(Component.text("发声点 " + emitter.id() + " 曲目已设为 " + track,
                NamedTextColor.GREEN));
    }

    private void setEmitterTrigger(CommandSender sender, String[] args) {
        if (args.length < 4 || !emitterExists(sender, args, 2)) return;
        Trigger trigger;
        try {
            trigger = Trigger.valueOf(args[3].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("未知触发方式：" + args[3], NamedTextColor.RED));
            return;
        }
        AudioEmitter emitter = plugin.emitterManager().emitter(args[2].toLowerCase(Locale.ROOT));
        plugin.emitterManager().put(new AudioEmitter(emitter.id(), emitter.world(), emitter.x(), emitter.y(),
                emitter.z(), emitter.track(), emitter.radius(), trigger, emitter.loop()));
        plugin.emitterManager().save();
        sender.sendMessage(Component.text("发声点 " + emitter.id() + " 触发方式已设为 " + trigger,
                NamedTextColor.GREEN));
    }

    private void setEmitterRadius(CommandSender sender, String[] args) {
        if (args.length < 4 || !emitterExists(sender, args, 2)) return;
        double radius;
        try {
            radius = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("半径必须是数字", NamedTextColor.RED));
            return;
        }
        if (radius < 0) {
            sender.sendMessage(Component.text("半径不能为负", NamedTextColor.RED));
            return;
        }
        AudioEmitter emitter = plugin.emitterManager().emitter(args[2].toLowerCase(Locale.ROOT));
        plugin.emitterManager().put(new AudioEmitter(emitter.id(), emitter.world(), emitter.x(), emitter.y(),
                emitter.z(), emitter.track(), radius, emitter.trigger(), emitter.loop()));
        plugin.emitterManager().save();
        sender.sendMessage(Component.text("发声点 " + emitter.id() + " 半径已设为 " + radius,
                NamedTextColor.GREEN));
    }

    private static AudioEmitter withTrack(AudioEmitter emitter, Key track) {
        return new AudioEmitter(emitter.id(), emitter.world(), emitter.x(), emitter.y(), emitter.z(),
                track, emitter.radius(), emitter.trigger(), emitter.loop());
    }

    private boolean emitterExists(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            sender.sendMessage(Component.text("缺少发声点 ID", NamedTextColor.RED));
            return false;
        }
        if (plugin.emitterManager().emitter(args[index].toLowerCase(Locale.ROOT)) == null) {
            sender.sendMessage(Component.text("发声点不存在：" + args[index], NamedTextColor.RED));
            return false;
        }
        return true;
    }

    // ---------- 调试 ----------

    private void debug(CommandSender sender) {
        sender.sendMessage(Component.text("MineAudio " + plugin.getPluginMeta().getVersion()
                + " | 曲目 " + plugin.trackRegistry().size()
                + " | 音效 " + plugin.cueRegistry().size()
                + " | 区域 " + plugin.regionManager().all().size()
                + " | 发声点 " + plugin.emitterManager().all().size(), NamedTextColor.YELLOW));
        for (AudioTrack track : plugin.trackRegistry().all()) {
            sender.sendMessage(Component.text("  - " + track.id() + " [" + track.bus() + "] "
                    + sourceName(track.primary()), NamedTextColor.GRAY));
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (String line : orchestrator.describe(player)) {
                sender.sendMessage(Component.text("  [" + player.getName() + "] " + line, NamedTextColor.GRAY));
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            ClientConnectionRegistry.ClientInfo info = plugin.clientProtocol().registry().get(player);
            if (info != null) {
                sender.sendMessage(Component.text("  [" + player.getName() + "] MineAudio Client "
                        + info.modVersion() + " mc=" + info.minecraft()
                        + " caps=" + info.capabilities(), NamedTextColor.GRAY));
            }
            for (ClientPlaybackStateCache.Snapshot snapshot : plugin.clientProtocol().stateCache().snapshots(player)) {
                sender.sendMessage(Component.text("    session " + snapshot.sessionId().substring(0, 8)
                        + " " + snapshot.state()
                        + " " + snapshot.displayPositionMs() + "/" + snapshot.durationMs() + "ms"
                        + " buffer=" + snapshot.bufferedMs() + "ms"
                        + " rtt=" + snapshot.rttMs() + "ms drift=" + snapshot.driftMs() + "ms",
                        NamedTextColor.GRAY));
            }
        }
        if (sender instanceof Player player) {
            AudioCapabilities capabilities = orchestrator.capabilities(player);
            sender.sendMessage(Component.text("  能力：vanillaClient=" + capabilities.vanillaClient()
                    + " stream=" + orchestrator.streamAvailable(player)
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
            case AudioSource.Stream stream -> "STREAM " + stream.provider() + " "
                    + (stream.uri() != null ? stream.uri() : stream.source() + ":" + stream.id());
        };
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "用法：/mineaudio play <曲目> [范围] | /mineaudio stop [Bus] [范围] |"
                        + " /mineaudio region ... | /mineaudio emitter ... | /mineaudio ui | /mineaudio reload | /mineaudio debug",
                NamedTextColor.RED));
    }

    private void sendRegionUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "用法：/mineaudio region list|pos1|pos2|create <id>|sphere <id> <半径>|delete <id>|"
                        + "settrack <id> <曲目|clear>|setambient <id> <曲目...|clear>|"
                        + "setpriority <id> <值>", NamedTextColor.RED));
    }

    private void sendEmitterUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "用法：/mineaudio emitter list|create <id> <曲目>|bind <id>|delete <id>|"
                        + "settrack <id> <曲目>|settrigger <id> <ALWAYS|REDSTONE|COMMAND|INTERACT>|"
                        + "setradius <id> <半径>|start <id>|stop <id>", NamedTextColor.RED));
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
        if (sub.equals("emitter")) {
            return emitterComplete(args);
        }
        if (sub.equals("queue")) {
            if (args.length == 2) return match(QUEUE_ACTIONS, args[1]);
            if (args.length == 3 && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("play"))) {
                return match(List.of("1", "2", "3", "4", "5", "6"), args[2]);
            }
            return List.of();
        }
        if (sub.equals("pause") || sub.equals("resume")) {
            return args.length == 2 ? match(onlineNames(), args[1]) : List.of();
        }
        if (sub.equals("seek") || sub.equals("volume")) {
            if (args.length == 2) {
                return sub.equals("seek") ? List.of("30", "1:00", "2:00") : List.of("0", "50", "100");
            }
            return args.length == 3 ? match(onlineNames(), args[2]) : List.of();
        }
        return List.of();
    }

    private List<String> emitterComplete(String[] args) {
        if (args.length == 2) {
            return match(EMITTER_ACTIONS, args[1]);
        }
        String action = args[1].toLowerCase();
        if (args.length == 3) {
            return switch (action) {
                case "create", "bind", "delete", "settrack", "settrigger", "setradius", "start", "stop" ->
                        match(emitterIds(), args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4) {
            return switch (action) {
                case "create", "settrack" -> match(trackIds(), args[3]);
                case "settrigger" -> match(TRIGGERS, args[3]);
                case "setradius" -> List.of("8", "16", "24", "32", "48", "64");
                default -> List.of();
            };
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

    private List<String> emitterIds() {
        List<String> ids = new ArrayList<>();
        for (AudioEmitter emitter : plugin.emitterManager().all()) ids.add(emitter.id());
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
