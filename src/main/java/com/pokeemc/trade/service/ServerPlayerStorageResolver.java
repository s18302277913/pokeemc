package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.MinecraftPlayerInventoryStore;
import com.pokeemc.trade.asset.MinecraftContainerStore;
import com.pokeemc.trade.asset.MinecraftPokemonStoragePort;
import com.pokeemc.trade.asset.PlayerInventoryStore;
import com.pokeemc.trade.asset.PokemonStoragePort;
import com.pokeemc.trade.asset.WalletPort;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 生产玩家存储解析器（Task 11 步骤 3）：把玩家 UUID 解析为 Minecraft 背包、
 * Pixelmon 钱包与宝可梦存储适配器，供 {@link TradeServiceImpl} 在真实服务器使用。
 */
public final class ServerPlayerStorageResolver implements PlayerStorageResolver {

    private final WalletPort wallet;

    public ServerPlayerStorageResolver(WalletPort wallet) {
        this.wallet = wallet;
    }

    @Override
    public boolean isOnline(UUID playerId) {
        return player(playerId) != null;
    }

    @Override
    public String displayName(UUID playerId) {
        ServerPlayer player = player(playerId);
        return player != null
                ? player.getGameProfile().getName()
                : "Player-" + playerId.toString().substring(0, 8);
    }

    @Override
    public Collection<UUID> onlinePlayers() {
        MinecraftServer server = server();
        if (server == null) {
            return List.of();
        }
        return server.getPlayerList().getPlayers().stream()
                .map(ServerPlayer::getUUID)
                .toList();
    }

    @Override
    public PlayerInventoryStore inventory(UUID playerId) {
        ServerPlayer player = requirePlayer(playerId);
        return MinecraftPlayerInventoryStore.of(player.getInventory(), player.registryAccess());
    }

    @Override
    public PlayerInventoryStore enderChest(UUID playerId) {
        ServerPlayer player = requirePlayer(playerId);
        return MinecraftContainerStore.of(player.getEnderChestInventory(), player.registryAccess());
    }

    @Override
    public WalletPort wallet() {
        return wallet;
    }

    @Override
    public PokemonStoragePort pokemonStorage(UUID playerId) {
        return MinecraftPokemonStoragePort.of(requirePlayer(playerId));
    }

    private static MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    private static ServerPlayer player(UUID playerId) {
        MinecraftServer server = server();
        return server == null ? null : server.getPlayerList().getPlayer(playerId);
    }

    private static ServerPlayer requirePlayer(UUID playerId) {
        ServerPlayer player = player(playerId);
        if (player == null) {
            throw new IllegalStateException("player not online: " + playerId);
        }
        return player;
    }
}
