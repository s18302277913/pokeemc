package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.exchange.ExchangeService;
import com.pokeemc.menu.ExchangeMenu;
import com.pokeemc.menu.TransmutationTableMenu;
import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import com.pokeemc.storage.adapter.VanillaEnderChestAdapter;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageTransactionResult;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端 -> 服务器：提交一批「仓储槽位出售」请求（Task 8）。
 *
 * <p>客户端把浏览面板中拖入出售区的槽位（仓储 id + 槽位 + 数量 + 槽位指纹）
 * 打包发送。服务端不信任客户端：收到后重新校验会话字段、玩家与仓储距离、
 * 必须处于转化桌或交易所菜单会话中，再交给 {@link ExchangeService} 在服务端线程执行
 * （重新查价、SELL 权限、revision/指纹并发控制、两阶段移除、钱包入账、幂等）。
 * 执行结果写入菜单的 DataSlot（结果码 + 序号），客户端轮询展示。</p>
 */
public record StorageSellPacket(
        String sessionId,
        String operationId,
        List<ExchangeService.SellEntry> entries,
        Map<StorageId, Long> expectedRevisions) implements CustomPacketPayload {

    /** 玩家可操作的仓储最大距离（格），与 StorageMovePacket 一致。 */
    public static final double MAX_OPERATION_DISTANCE_BLOCKS = 8.0;

    /** 会话/操作标识的最大长度，防止恶意超长字符串。 */
    public static final int MAX_SESSION_FIELD_LENGTH = 64;

    public static final Type<StorageSellPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_sell"));

    private static final StreamCodec<ByteBuf, StorageId> STORAGE_ID_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StorageId::dimension,
            ByteBufCodecs.STRING_UTF8, StorageId::adapterType,
            ByteBufCodecs.STRING_UTF8, StorageId::location,
            StorageId::new);

    private static final StreamCodec<ByteBuf, ExchangeService.SellEntry> SELL_ENTRY_CODEC =
            StreamCodec.composite(
                    STORAGE_ID_CODEC, ExchangeService.SellEntry::storageId,
                    ByteBufCodecs.VAR_INT, ExchangeService.SellEntry::slotIndex,
                    ByteBufCodecs.VAR_INT, ExchangeService.SellEntry::count,
                    ByteBufCodecs.VAR_LONG, ExchangeService.SellEntry::fingerprint,
                    ExchangeService.SellEntry::new);

    private static final StreamCodec<ByteBuf, List<ExchangeService.SellEntry>> ENTRIES_CODEC =
            new StreamCodec<>() {
                @Override
                public List<ExchangeService.SellEntry> decode(ByteBuf buf) {
                    int size = ByteBufCodecs.VAR_INT.decode(buf);
                    if (size < 0 || size > ExchangeService.MAX_ENTRIES) {
                        throw new IllegalArgumentException("bad sell entries size: " + size);
                    }
                    List<ExchangeService.SellEntry> list = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        list.add(SELL_ENTRY_CODEC.decode(buf));
                    }
                    return list;
                }

                @Override
                public void encode(ByteBuf buf, List<ExchangeService.SellEntry> list) {
                    ByteBufCodecs.VAR_INT.encode(buf, list.size());
                    for (ExchangeService.SellEntry entry : list) {
                        SELL_ENTRY_CODEC.encode(buf, entry);
                    }
                }
            };

    private static final StreamCodec<ByteBuf, Map<StorageId, Long>> REVISION_MAP_CODEC = new StreamCodec<>() {
        @Override
        public Map<StorageId, Long> decode(ByteBuf buf) {
            int size = ByteBufCodecs.VAR_INT.decode(buf);
            if (size < 0 || size > ExchangeService.MAX_ENTRIES) {
                throw new IllegalArgumentException("bad revision map size: " + size);
            }
            Map<StorageId, Long> map = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) {
                map.put(STORAGE_ID_CODEC.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf));
            }
            return map;
        }

        @Override
        public void encode(ByteBuf buf, Map<StorageId, Long> map) {
            ByteBufCodecs.VAR_INT.encode(buf, map.size());
            for (Map.Entry<StorageId, Long> entry : map.entrySet()) {
                STORAGE_ID_CODEC.encode(buf, entry.getKey());
                ByteBufCodecs.VAR_LONG.encode(buf, entry.getValue());
            }
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageSellPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageSellPacket decode(RegistryFriendlyByteBuf buf) {
                    return new StorageSellPacket(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ENTRIES_CODEC.decode(buf),
                            REVISION_MAP_CODEC.decode(buf));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageSellPacket packet) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.operationId());
                    ENTRIES_CODEC.encode(buf, packet.entries());
                    REVISION_MAP_CODEC.encode(buf, packet.expectedRevisions());
                }
            };

    @Override
    public Type<StorageSellPacket> type() {
        return TYPE;
    }

    public static void handle(StorageSellPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                int previousNonce = player.containerMenu instanceof ExchangeMenu menu
                        ? menu.getResultNonce() : -1;
                StorageTransactionResult result = executeSell(player, packet);
                if (!result.success() && player.containerMenu instanceof ExchangeMenu menu
                        && menu.getResultNonce() == previousNonce) {
                    menu.reportStorageResult(result);
                }
                PokeEMC.LOGGER.debug("storage sell {} entries = {} ({})",
                        packet.entries() == null ? 0 : packet.entries().size(), result.code(), result.message());
            }
        });
    }

    /**
     * 服务端执行入口：会话字段校验、转化桌菜单会话校验、距离校验后交给菜单执行出售。
     * 独立成静态方法便于直接调用/测试。执行结果由菜单写入 DataSlot 供客户端展示。
     */
    public static StorageTransactionResult executeSell(ServerPlayer player, StorageSellPacket packet) {
        if (isBlankOrTooLong(packet.sessionId())) {
            return StorageTransactionResult.failure(
                    "invalid_session", "invalid or missing session id");
        }
        if (isBlankOrTooLong(packet.operationId())) {
            return StorageTransactionResult.failure(
                    "invalid_operation", "invalid or missing operation id");
        }
        if (packet.entries() == null || packet.entries().isEmpty()) {
            return StorageTransactionResult.failure(
                    "invalid_request", "sell entries are empty");
        }
        if (packet.entries().size() > ExchangeService.MAX_ENTRIES) {
            return StorageTransactionResult.failure(
                    "invalid_request", "too many sell entries");
        }
        if (packet.expectedRevisions() == null) {
            return StorageTransactionResult.failure(
                    "invalid_request", "expected revisions missing");
        }
        for (ExchangeService.SellEntry entry : packet.entries()) {
            boolean virtual = VanillaEnderChestAdapter.isEnderChest(entry.storageId());
            BlockPos pos = virtual ? null
                    : AbstractContainerAdapter.parsePos(entry.storageId().location());
            if (!virtual && pos == null) {
                return StorageTransactionResult.failure(
                        "invalid_location", "storage location malformed");
            }
            if (!virtual) {
                double distanceSq = player.distanceToSqr(
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                double maxSq = MAX_OPERATION_DISTANCE_BLOCKS * MAX_OPERATION_DISTANCE_BLOCKS;
                if (distanceSq > maxSq) {
                    return StorageTransactionResult.failure(
                            "distance_exceeded", "too far from storage");
                }
            }
        }
        if (player.containerMenu instanceof TransmutationTableMenu menu) {
            return menu.runSell(player, packet);
        }
        if (player.containerMenu instanceof ExchangeMenu exchangeMenu) {
            return exchangeMenu.runSell(player, packet);
        }
        return StorageTransactionResult.failure(
                "invalid_menu", "sell requires transmutation table or exchange menu");
    }

    private static boolean isBlankOrTooLong(String value) {
        return value == null || value.isBlank() || value.length() > MAX_SESSION_FIELD_LENGTH;
    }
}
