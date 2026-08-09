package com.pokeemc.trade.client;

import com.pokeemc.trade.model.AssetPageKind;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.TradeCapability;
import com.pokeemc.trade.model.TradeStatus;
import com.pokeemc.trade.network.TradeAssetPagePacket;
import com.pokeemc.trade.network.TradeDirectoryPacket;
import com.pokeemc.trade.network.TradeSnapshotPacket;
import com.pokeemc.trade.service.TradeAssetPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 9-1：客户端交易缓存状态验证（计划 5.4）。
 * <p>
 * 断言：快照按 revision 防覆盖；资产页按 assetRevision + kind/page 防覆盖；
 * 目录缓存；退出世界 {@link TradeClientState#clear()} 清空全部（不跨服务器泄漏）。
 */
class TradeClientStateTest {

    private static final UUID SELF = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TRADE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final TradeClientState state = new TradeClientState();

    @Test
    void newerSnapshotOverridesOlder() {
        assertTrue(state.snapshot().isEmpty());
        state.onSnapshot(snapshot(3L));
        assertEquals(3L, state.snapshot().orElseThrow().revision());
        state.onSnapshot(snapshot(4L));
        assertEquals(4L, state.snapshot().orElseThrow().revision());
        assertEquals(SELF, state.selfPlayerId());
        assertTrue(state.hasActiveTrade());
    }

    @Test
    void staleSnapshotDoesNotOverrideNewer() {
        state.onSnapshot(snapshot(4L));
        state.onSnapshot(snapshot(3L)); // 旧包
        assertEquals(4L, state.snapshot().orElseThrow().revision());
        state.onSnapshot(snapshot(4L)); // 相等不覆盖
        assertEquals(4L, state.snapshot().orElseThrow().revision());
    }

    @Test
    void staleAssetPageDoesNotOverrideNewer() {
        state.onAssetPage(assetPage(AssetPageKind.ITEMS, 0, 10L));
        state.onAssetPage(assetPage(AssetPageKind.ITEMS, 0, 9L)); // 旧 assetRevision
        TradeAssetPagePacket got = state.assetPage(AssetPageKind.ITEMS, 0);
        assertEquals(10L, got.assetRevision());
        state.onAssetPage(assetPage(AssetPageKind.ITEMS, 0, 11L));
        assertEquals(11L, state.assetPage(AssetPageKind.ITEMS, 0).assetRevision());
    }

    @Test
    void assetPagesKeyedByKindAndPage() {
        state.onAssetPage(assetPage(AssetPageKind.ITEMS, 0, 1L));
        state.onAssetPage(assetPage(AssetPageKind.ITEMS, 1, 1L));
        state.onAssetPage(assetPage(AssetPageKind.PARTY, 0, 1L));
        assertEquals(0, state.assetPage(AssetPageKind.ITEMS, 0).page());
        assertEquals(1, state.assetPage(AssetPageKind.ITEMS, 1).page());
        assertEquals(AssetPageKind.PARTY, state.assetPage(AssetPageKind.PARTY, 0).kind());
        assertNull(state.assetPage(AssetPageKind.PC, 0));
    }

    @Test
    void directoryCachedAndReplaced() {
        assertTrue(state.directory().isEmpty());
        state.onDirectory(new TradeDirectoryPacket(UUID.randomUUID(),
                List.of(new TradeDirectoryPacket.PlayerDirectoryEntry(OTHER, "other", TradeCapability.AVAILABLE)),
                1, 0, 20));
        assertEquals(1, state.directory().orElseThrow().entries().size());
        state.onDirectory(new TradeDirectoryPacket(UUID.randomUUID(), List.of(), 0, 0, 20));
        assertTrue(state.directory().orElseThrow().entries().isEmpty());
    }

    @Test
    void clearDropsAllCaches() {
        state.onSnapshot(snapshot(3L));
        state.onAssetPage(assetPage(AssetPageKind.PC, 2, 1L));
        state.onDirectory(new TradeDirectoryPacket(UUID.randomUUID(),
                List.of(new TradeDirectoryPacket.PlayerDirectoryEntry(OTHER, "other", TradeCapability.AVAILABLE)),
                1, 0, 20));
        state.clear();
        assertTrue(state.snapshot().isEmpty());
        assertTrue(state.directory().isEmpty());
        assertNull(state.assetPage(AssetPageKind.PC, 2));
        assertNull(state.selfPlayerId());
        assertFalse(state.hasActiveTrade());
    }

    private static TradeSnapshotPacket snapshot(long revision) {
        return new TradeSnapshotPacket(
                TRADE, revision, TradeStatus.OPEN,
                new TradeSnapshotPacket.PlayerSummary(SELF, "self"),
                new TradeSnapshotPacket.PlayerSummary(OTHER, "other"),
                TradeSnapshotPacket.OfferSummary.empty(), TradeSnapshotPacket.OfferSummary.empty(),
                false, false, 1_000L, 0L,
                TradeCapability.BUSY, TradeCapability.BUSY,
                new DeliveryPreference(DeliveryPreference.ItemDestination.AUTO,
                        DeliveryPreference.PokemonDestination.AUTO),
                null);
    }

    private static TradeAssetPagePacket assetPage(AssetPageKind kind, int page, long assetRevision) {
        return new TradeAssetPagePacket(UUID.randomUUID(), SELF, assetRevision, kind, page, 20, 0, List.of());
    }
}
