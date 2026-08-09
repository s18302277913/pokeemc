package com.pokeemc.network;

import com.poketrade.api.price.PriceSort;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 交易所目录包的 codec round-trip：新加入的黑白名单字段（{@code blockedItems} /
 * {@code allowedItems} / {@code allowlistEnabled}）必须与发送端字段顺序一致，
 * 发与收用同一 record，往返后字段完全一致。
 */
class ExchangeCatalogPacketTest {

    @Test
    void responseRoundTripsEntriesCategoriesAndSellRules() {
        ExchangeCatalogPacket.Response original = new ExchangeCatalogPacket.Response(
                "session-1",
                List.of(new ExchangeCatalogPacket.EntryWire(
                        "minecraft:diamond", 100L, 50L, "矿物", "common", "minecraft")),
                List.of("矿物", "unknown"),
                100_000L,
                List.of("pixelmon:master_ball"),
                List.of("pixelmon:poke_ball"),
                true, true, true);

        ExchangeCatalogPacket.Response decoded = roundTrip(original);

        assertEquals(original, decoded);
    }

    @Test
    void responseRoundTripsEmptySellRules() {
        ExchangeCatalogPacket.Response original = new ExchangeCatalogPacket.Response(
                "session-2", List.of(), List.of("unknown"), 0L,
                List.of(), List.of(), false, false, false);

        ExchangeCatalogPacket.Response decoded = roundTrip(original);

        assertEquals(original, decoded);
        assertEquals(List.of(), decoded.blockedItems());
        assertEquals(List.of(), decoded.allowedItems());
        assertEquals(false, decoded.allowlistEnabled());
        assertEquals(false, decoded.buyEnabled());
        assertEquals(false, decoded.sellEnabled());
    }

    @Test
    void requestRoundTrips() {
        ExchangeCatalogPacket.Request original = new ExchangeCatalogPacket.Request(
                "session-3", "球", "战斗用品", PriceSort.CATEGORY);

        ExchangeCatalogPacket.Request decoded = roundTrip(original);

        assertEquals(original, decoded);
    }

    private static <T> T roundTrip(T payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        if (payload instanceof ExchangeCatalogPacket.Response p) {
            ExchangeCatalogPacket.Response.STREAM_CODEC.encode(buf, p);
            return cast(ExchangeCatalogPacket.Response.STREAM_CODEC.decode(buf));
        }
        if (payload instanceof ExchangeCatalogPacket.Request p) {
            ExchangeCatalogPacket.Request.STREAM_CODEC.encode(buf, p);
            return cast(ExchangeCatalogPacket.Request.STREAM_CODEC.decode(buf));
        }
        throw new AssertionError("unhandled payload " + payload);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }
}
