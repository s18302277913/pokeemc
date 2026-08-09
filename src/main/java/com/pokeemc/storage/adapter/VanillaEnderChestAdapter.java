package com.pokeemc.storage.adapter;

import static com.poketrade.api.storage.StorageCapability.EXTRACT;
import static com.poketrade.api.storage.StorageCapability.INSERT;
import static com.poketrade.api.storage.StorageCapability.SELL_SOURCE;
import static com.poketrade.api.storage.StorageCapability.SNAPSHOT;

import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 玩家个人末影箱适配器（typeId {@code vanilla_ender_chest}）。
 *
 * <p>末影箱不是世界方块，而是每个玩家独有的个人容器；仓储键位置约定为
 * {@code player;<uuid>}。发现服务为查询玩家自动登记/列出该容器，其他玩家
 * 只能看到并操作自己的末影箱（记录 owner = 玩家本人）。</p>
 *
 * <p>只有在线玩家可打开；离线时 {@code supports}/{@code open} 返回空，
 * 列表仍按元数据呈现（不强制加载任何区块）。</p>
 */
public final class VanillaEnderChestAdapter implements StorageAdapter {

    public static final String TYPE_ID = "vanilla_ender_chest";

    private static final String PLAYER_PREFIX = "player;";
    private static final Set<StorageCapability> CAPABILITIES =
            Set.of(SNAPSHOT, INSERT, EXTRACT, SELL_SOURCE);

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public Set<StorageCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean supports(StorageAdapterContext context) {
        return playerFor(context.storageId()) != null;
    }

    @Override
    public Optional<StorageHandle> open(StorageAdapterContext context) {
        ServerPlayer player = playerFor(context.storageId());
        if (player == null) {
            return Optional.empty();
        }
        return Optional.of(new StorageHandleImpl(context.storageId(),
                MinecraftSlotStore.of(player.getEnderChestInventory()),
                slot -> true, slot -> true, () -> 0L));
    }

    /** 从仓储键位置解析在线玩家；格式非法或玩家离线返回 {@code null}。 */
    public static ServerPlayer playerFor(StorageId storageId) {
        String location = storageId.location();
        if (location == null || !location.startsWith(PLAYER_PREFIX)) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(location.substring(PLAYER_PREFIX.length()));
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            return server == null ? null : server.getPlayerList().getPlayer(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 构造玩家末影箱的规范仓储键位置（{@code player;<uuid>}）。 */
    public static String locationOf(UUID playerId) {
        return PLAYER_PREFIX + playerId;
    }

    /** 是否末影箱适配器（虚拟个人容器，无方块坐标，跳过距离/位置校验）。 */
    public static boolean isEnderChest(StorageId storageId) {
        return storageId != null && TYPE_ID.equals(storageId.adapterType());
    }

    /** 是否末影箱适配器类型 ID。 */
    public static boolean isEnderChest(String adapterType) {
        return TYPE_ID.equals(adapterType);
    }
}
