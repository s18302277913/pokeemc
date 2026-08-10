package com.pokeemc.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 目录变更推送包（会话 #16）：单字段 VAR_LONG round-trip。
 * 服务端 rebuild 后广播给所有玩家，客户端对开着的交易所屏触发重拉。
 */
class CatalogChangedPacketTest {

    @Test
    void roundTripsCatalogVersion() {
        CatalogChangedPacket original = new CatalogChangedPacket(123_456L);

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        CatalogChangedPacket.STREAM_CODEC.encode(buf, original);
        CatalogChangedPacket decoded = CatalogChangedPacket.STREAM_CODEC.decode(buf);

        assertEquals(original, decoded);
        assertEquals(123_456L, decoded.catalogVersion());
    }

    @Test
    void roundTripsZeroVersion() {
        CatalogChangedPacket original = new CatalogChangedPacket(0L);

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        CatalogChangedPacket.STREAM_CODEC.encode(buf, original);
        CatalogChangedPacket decoded = CatalogChangedPacket.STREAM_CODEC.decode(buf);

        assertEquals(original, decoded);
    }
}
