package com.mineaudio.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import com.mineaudio.protocol.Packets;

/** 已握手 MineAudio Client 的连接注册表。 */
public final class ClientConnectionRegistry {

    public record ClientInfo(
            UUID playerId,
            String modVersion,
            String minecraft,
            String locale,
            List<String> capabilities,
            List<String> formats,
            long connectedAtMs) {

        public boolean supports(String capability) {
            return capabilities != null && capabilities.contains(capability);
        }
    }

    private final Map<UUID, ClientInfo> clients = new HashMap<>();

    public void markHello(Player player, Packets.Hello hello) {
        clients.put(player.getUniqueId(), new ClientInfo(
                player.getUniqueId(),
                hello.modVersion(),
                hello.minecraft(),
                hello.locale(),
                hello.capabilities() == null ? List.of() : List.copyOf(hello.capabilities()),
                hello.formats() == null ? List.of() : List.copyOf(hello.formats()),
                System.currentTimeMillis()));
    }

    public void remove(UUID playerId) {
        clients.remove(playerId);
    }

    public ClientInfo get(Player player) {
        return clients.get(player.getUniqueId());
    }

    public ClientInfo get(UUID playerId) {
        return clients.get(playerId);
    }

    public boolean connected(Player player) {
        return clients.containsKey(player.getUniqueId());
    }

    public boolean hasAny() {
        return !clients.isEmpty();
    }

    public Collection<ClientInfo> all() {
        return List.copyOf(clients.values());
    }
}
