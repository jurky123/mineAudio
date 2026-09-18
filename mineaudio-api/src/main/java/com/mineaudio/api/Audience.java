package com.mineaudio.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 播放受众，避免 API 为每种范围堆重载。
 * {@link #players()} 在调用时解析，业务插件不要长期缓存返回值。
 */
public interface Audience {

    Collection<? extends Player> players();

    /** 是否为全服受众。流媒体 Backend 无多会话能力时只接受全服播放。 */
    default boolean isGlobal() {
        return false;
    }

    static Audience player(Player player) {
        return new SinglePlayerAudience(player);
    }

    static Audience players(Collection<? extends Player> players) {
        return new CollectionAudience(players);
    }

    static Audience world(World world) {
        return new WorldAudience(world);
    }

    static Audience global() {
        return GlobalAudience.INSTANCE;
    }
}

final class SinglePlayerAudience implements Audience {

    private final UUID playerId;

    SinglePlayerAudience(Player player) {
        this.playerId = Objects.requireNonNull(player, "player").getUniqueId();
    }

    @Override
    public Collection<? extends Player> players() {
        Player player = Bukkit.getPlayer(playerId);
        return player == null || !player.isOnline() ? List.of() : List.of(player);
    }
}

final class CollectionAudience implements Audience {

    private final List<Player> players;

    CollectionAudience(Collection<? extends Player> players) {
        this.players = new ArrayList<>(Objects.requireNonNull(players, "players"));
    }

    @Override
    public Collection<? extends Player> players() {
        players.removeIf(player -> !player.isOnline());
        return List.copyOf(players);
    }
}

final class WorldAudience implements Audience {

    private final UUID worldId;

    WorldAudience(World world) {
        this.worldId = Objects.requireNonNull(world, "world").getUID();
    }

    @Override
    public Collection<? extends Player> players() {
        World world = Bukkit.getWorld(worldId);
        return world == null ? List.of() : List.copyOf(world.getPlayers());
    }
}

final class GlobalAudience implements Audience {

    static final GlobalAudience INSTANCE = new GlobalAudience();

    private GlobalAudience() {
    }

    @Override
    public boolean isGlobal() {
        return true;
    }

    @Override
    public Collection<? extends Player> players() {
        return List.copyOf(Bukkit.getOnlinePlayers());
    }
}
