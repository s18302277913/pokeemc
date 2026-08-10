package com.pokeemc.network;

import com.poketrade.api.storage.StorageId;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 仓储批量操作包（会话 #16）：8 字段手动 StreamCodec round-trip + ordinal 越界防护 +
 * 可空字段（storageId / expectedRevisions）编解码一致性。
 */
class StorageBatchPacketTest {

    @Test
    void roundTripsNearbySellItemWithNullables() {
        StorageBatchPacket original = new StorageBatchPacket(
                "session-1", "op-1",
                StorageBatchPacket.Action.SELL_ITEM, StorageBatchPacket.Scope.NEARBY,
                null, "minecraft:diamond", 64, null);

        StorageBatchPacket decoded = roundTrip(original);

        assertEquals(original, decoded);
        assertNull(decoded.storageId());
        assertNull(decoded.expectedRevisions());
    }

    @Test
    void roundTripsCurrentWithdrawAllWithAllFields() {
        Map<StorageId, Long> revisions = new LinkedHashMap<>();
        revisions.put(new StorageId("minecraft:overworld", "vanilla_chest",
                "0;64;0,0,0"), 7L);
        StorageBatchPacket original = new StorageBatchPacket(
                "session-2", "op-2",
                StorageBatchPacket.Action.WITHDRAW_ALL, StorageBatchPacket.Scope.CURRENT,
                new StorageId("minecraft:overworld", "vanilla_chest", "0;64;0,0,0"),
                "minecraft:iron_ingot", 0, revisions);

        StorageBatchPacket decoded = roundTrip(original);

        assertEquals(original, decoded);
        assertEquals("minecraft:iron_ingot", decoded.itemId());
        assertEquals(7L, decoded.expectedRevisions().get(
                new StorageId("minecraft:overworld", "vanilla_chest", "0;64;0,0,0")));
    }

    @Test
    void roundTripsSellAllCurrent() {
        StorageBatchPacket original = new StorageBatchPacket(
                "session-3", "op-3",
                StorageBatchPacket.Action.SELL_ALL, StorageBatchPacket.Scope.CURRENT,
                new StorageId("minecraft:overworld", "vanilla_barrel", "0;64;5,0,5"),
                null, 0, Map.of());

        StorageBatchPacket decoded = roundTrip(original);

        assertEquals(original, decoded);
    }

    @Test
    void maliciousActionOrdinalFallsBackToSellItem() {
        // 手动编一个 action.ordinal=99 的包，decode 应回退 SELL_ITEM 而非抛数组越界
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        ByteBufCodecs.STRING_UTF8.encode(buf, "session-x");
        ByteBufCodecs.STRING_UTF8.encode(buf, "op-x");
        ByteBufCodecs.VAR_INT.encode(buf, 99); // 越界 action
        ByteBufCodecs.VAR_INT.encode(buf, 1); // NEARBY
        ByteBufCodecs.BOOL.encode(buf, false); // storageId = null
        ByteBufCodecs.BOOL.encode(buf, true); // itemId = "minecraft:coal"
        ByteBufCodecs.STRING_UTF8.encode(buf, "minecraft:coal");
        ByteBufCodecs.VAR_INT.encode(buf, 16);
        ByteBufCodecs.BOOL.encode(buf, false); // expectedRevisions = null

        StorageBatchPacket decoded = StorageBatchPacket.STREAM_CODEC.decode(buf);

        assertEquals(StorageBatchPacket.Action.SELL_ITEM, decoded.action());
        assertEquals(StorageBatchPacket.Scope.NEARBY, decoded.scope());
        assertEquals("minecraft:coal", decoded.itemId());
        assertNull(decoded.storageId());
        assertNull(decoded.expectedRevisions());
    }

    private static StorageBatchPacket roundTrip(StorageBatchPacket payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        StorageBatchPacket.STREAM_CODEC.encode(buf, payload);
        return StorageBatchPacket.STREAM_CODEC.decode(buf);
    }
}
