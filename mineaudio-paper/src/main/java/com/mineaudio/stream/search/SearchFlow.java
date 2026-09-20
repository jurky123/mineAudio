package com.mineaudio.stream.search;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.mineaudio.MineAudioPlugin;

/**
 * 搜索用例统一入口（命令与界面共用）：每玩家请求代际 + 生命周期检查，
 * 旧请求回退结果直接丢弃；成功时统一写回 orchestrator 的搜索结果。
 */
public final class SearchFlow {

    private SearchFlow() {
    }

    private static final Map<UUID, Long> GENERATIONS = new ConcurrentHashMap<>();

    /** 玩家退出/插件停用时清理，避免旧代际污染下次会话。 */
    public static void clear(UUID playerId) {
        GENERATIONS.remove(playerId);
    }

    public static void clearAll() {
        GENERATIONS.clear();
    }

    /**
     * @param display 在主线程回调（results 为 null 表示失败，看 error）
     */
    public static void search(MineAudioPlugin plugin, Player player, String keyword, int page,
                              BiConsumer<List<SearchResult>, Throwable> display) {
        long generation = GENERATIONS.merge(player.getUniqueId(), 1L, Long::sum);
        plugin.searchService().search(keyword, page).whenComplete((results, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.isShuttingDown() || !player.isOnline()) return;
                    if (GENERATIONS.getOrDefault(player.getUniqueId(), 0L) != generation) return;
                    if (error == null) {
                        plugin.orchestrator().setSearchQuery(player, keyword, page, results);
                    }
                    display.accept(results, error);
                }));
    }
}
