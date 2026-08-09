package com.pokeemc.storage;

import com.pokeemc.PokeEMC;
import com.pokeemc.api.PokeTradeStorageApiImpl;
import com.pokeemc.config.PokeTradeConfig;
import com.pokeemc.storage.adapter.CondenserAdapter;
import com.pokeemc.storage.adapter.MinecraftSlotStore;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import com.pokeemc.storage.adapter.StorageHandleExt;
import com.pokeemc.storage.adapter.StorageHandleImpl;
import com.pokeemc.storage.adapter.VanillaBarrelAdapter;
import com.pokeemc.storage.adapter.VanillaChestAdapter;
import com.pokeemc.storage.adapter.VanillaDoubleChestAdapter;
import com.pokeemc.storage.adapter.VanillaEnderChestAdapter;
import com.pokeemc.storage.adapter.VanillaTrappedChestAdapter;
import com.pokeemc.storage.discovery.StorageDiscoveryService;
import com.poketrade.api.storage.StorageService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Optional;
import java.util.UUID;

/**
 * 仓储适配器注册表与 {@link StorageService} 实现的进程级装配器。
 *
 * <p>在模组构造阶段调用 {@link #init()}：注册内置适配器并把注册表接入
 * {@link StorageSavedData#setAdapterTypeValidator(Predicate)}，保证持久化加载时
 * 只接受已注册的适配器类型。统一鉴权服务 {@link StorageAccessService} 的管理员
 * 判定与审计回填也在此装配。</p>
 */
public final class StorageServices {

    private static volatile StorageAdapterRegistryImpl registry;
    private static volatile StorageAccessService accessService;
    private static volatile StorageDiscoveryService discoveryService;
    private static volatile StorageTransactionService transactionService;
    private static volatile StorageService storageService;

    private StorageServices() {
    }

    /** 注册内置适配器并装配服务。幂等，可重复调用。 */
    public static synchronized void init() {
        if (registry != null) {
            return;
        }
        StorageAdapterRegistryImpl reg = new StorageAdapterRegistryImpl();
        reg.register(new VanillaChestAdapter());
        reg.register(new VanillaDoubleChestAdapter());
        reg.register(new VanillaTrappedChestAdapter());
        reg.register(new VanillaBarrelAdapter());
        reg.register(new VanillaEnderChestAdapter());
        reg.register(new CondenserAdapter());
        registry = reg;
        StorageSavedData.setAdapterTypeValidator(reg::isRegistered);
        accessService = new StorageAccessService(
                id -> Optional.empty(),
                StorageServices::isAdmin,
                StorageServices::auditAdminBypass);
        discoveryService = new StorageDiscoveryService(
                reg, accessService, PokeTradeConfig.storageConfig(), StorageServices::isAdmin);
        transactionService = new StorageTransactionService(
                reg, accessService,
                StorageServices::storageSavedData,
                StorageServices::playerInventoryHandle,
                StorageServices::refreshPlayerInventory);
        storageService = new PokeTradeStorageApiImpl(
                reg, accessService, discoveryService, transactionService);
    }

    public static StorageAdapterRegistryImpl registry() {
        ensureInitialized();
        return registry;
    }

    public static StorageAccessService access() {
        ensureInitialized();
        return accessService;
    }

    public static StorageDiscoveryService discovery() {
        ensureInitialized();
        return discoveryService;
    }

    public static StorageTransactionService transactionService() {
        ensureInitialized();
        return transactionService;
    }

    public static StorageService storageService() {
        ensureInitialized();
        return storageService;
    }

    private static void ensureInitialized() {
        if (registry == null) {
            throw new IllegalStateException("storage services not initialized");
        }
    }

    // ---------------------------------------------------------------- 事务依赖提供者

    /** 事务服务的数据来源：主世界存档数据。 */
    private static StorageSavedData storageSavedData() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("storage services require a running server");
        }
        return server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
    }

    /** 事务服务读写的玩家背包句柄；玩家不在线时返回 null。 */
    private static StorageHandleExt playerInventoryHandle(UUID actorId) {
        ServerPlayer player = onlinePlayer(actorId);
        return player == null
                ? null
                : StorageHandleImpl.of(null, MinecraftSlotStore.of(player.getInventory()));
    }

    /** 事务涉及玩家背包后刷新客户端菜单显示。 */
    private static void refreshPlayerInventory(UUID actorId) {
        ServerPlayer player = onlinePlayer(actorId);
        if (player != null) {
            player.inventoryMenu.broadcastChanges();
        }
    }

    private static ServerPlayer onlinePlayer(UUID actorId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(actorId);
    }

    /** 管理员判定：在线且权限等级 >= 2（OP）。 */
    private static boolean isAdmin(UUID playerId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return false;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null && player.hasPermissions(2);
    }

    /** 管理员绕过鉴权时回填审计记录。 */
    private static void auditAdminBypass(
            UUID actorId, UUID storageOwnerId, StoragePermission permission) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        try {
            StorageSavedData data = server.overworld().getDataStorage()
                    .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
            data.appendAudit(
                    System.currentTimeMillis(), "-", actorId, "admin_bypass",
                    "permission " + permission + " for owner " + storageOwnerId);
        } catch (RuntimeException e) {
            PokeEMC.LOGGER.warn("failed to record admin bypass audit", e);
        }
    }
}
