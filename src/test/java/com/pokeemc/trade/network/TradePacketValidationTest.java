package com.pokeemc.trade.network;

import com.pokeemc.trade.model.AssetPageKind;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PkmAsset;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeCapability;
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeFeeQuote;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeOffer;
import com.pokeemc.trade.model.TradeStatus;
import com.pokeemc.trade.network.TradeRequestCache.CachedResult;
import com.pokeemc.trade.service.TradeAssetPage;
import com.pokeemc.trade.service.TradeSnapshot;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 8-6：玩家交易网络层验证（计划 5.1/5.2/5.3）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>全部 C2S/S2C payload 的 codec round-trip；</li>
 *   <li>恶意输入：非法枚举 ordinal、超长列表、超长字符串、负长度，全部抛
 *       {@link CorruptedFrameException} 而非分配无界内存；</li>
 *   <li>边界校验：非法 slot/box/数量/翻页参数返回稳定错误码；</li>
 *   <li>限流：每类别秒配额、窗口切换、桶淘汰；</li>
 *   <li>请求去重：重复 requestId 返回缓存结果、容量上限；</li>
 *   <li>快照投影：对手报价只含展示摘要，不含 NBT。</li>
 * </ul>
 */
class TradePacketValidationTest {

    private static final UUID REQ = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TRADE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID LEFT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RIGHT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // ------------------------------------------------------------------
    // codec round-trip（C2S）
    // ------------------------------------------------------------------

    @Test
    void createTradePacketRoundTrips() {
        CreateTradePacket p = new CreateTradePacket(REQ, RIGHT);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void acceptTradePacketRoundTrips() {
        AcceptTradePacket p = new AcceptTradePacket(REQ, TRADE, 7L);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void offerItemPacketRoundTrips() {
        OfferItemPacket p = new OfferItemPacket(REQ, TRADE, 7L, 12, 64);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void offerPkmPacketRoundTrips() {
        OfferPkmPacket p = new OfferPkmPacket(REQ, TRADE, 7L, 100_000L);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void offerPokemonPartyPacketRoundTrips() {
        OfferPokemonPacket p = new OfferPokemonPacket(REQ, TRADE, 7L, "party", -1, 3);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void offerPokemonPcPacketRoundTrips() {
        OfferPokemonPacket p = new OfferPokemonPacket(REQ, TRADE, 7L, "pc", 12, 27);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void removeOfferAssetPacketRoundTrips() {
        RemoveOfferAssetPacket p = new RemoveOfferAssetPacket(REQ, TRADE, 7L, REQ);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void confirmTradePacketRoundTrips() {
        ConfirmTradePacket p = new ConfirmTradePacket(REQ, TRADE, 7L);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void cancelTradePacketRoundTrips() {
        CancelTradePacket p = new CancelTradePacket(REQ, TRADE, 7L);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void setDeliveryPreferencePacketRoundTrips() {
        DeliveryPreference pref = new DeliveryPreference(
                DeliveryPreference.ItemDestination.INVENTORY,
                DeliveryPreference.PokemonDestination.PC);
        SetDeliveryPreferencePacket p = new SetDeliveryPreferencePacket(REQ, TRADE, 7L, pref);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void requestTradeDirectoryPacketRoundTrips() {
        RequestTradeDirectoryPacket p = new RequestTradeDirectoryPacket(REQ, "pika", 1, 20);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void requestTradeAssetPagePacketRoundTrips() {
        RequestTradeAssetPagePacket p = new RequestTradeAssetPagePacket(REQ, TRADE, 7L, AssetPageKind.PC, 3, 30);
        assertEquals(p, roundTrip(p));
    }

    // ------------------------------------------------------------------
    // codec round-trip（S2C）
    // ------------------------------------------------------------------

    @Test
    void tradeResultPacketSuccessRoundTrips() {
        TradeResultPacket p = TradeResultPacket.ok(REQ, TRADE, 9L);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void tradeResultPacketFailureRoundTrips() {
        TradeResultPacket p = TradeResultPacket.fail(REQ, TradeError.RATE_LIMITED);
        assertEquals(p, roundTrip(p));
        assertNull(p.tradeId());
    }

    @Test
    void tradeSnapshotPacketRoundTrips() {
        TradeSnapshotPacket p = sampleSnapshotPacket();
        assertEquals(p, roundTrip(p));
    }

    @Test
    void tradeSnapshotPacketWithFeeQuoteRoundTrips() {
        TradeSnapshotPacket p = new TradeSnapshotPacket(
                TRADE, 5L, TradeStatus.LOCKED,
                new TradeSnapshotPacket.PlayerSummary(LEFT, "left"),
                new TradeSnapshotPacket.PlayerSummary(RIGHT, "right"),
                sampleOfferSummary(), TradeSnapshotPacket.OfferSummary.empty(),
                true, true, 1000L, 2000L,
                TradeCapability.BUSY, TradeCapability.BUSY,
                new DeliveryPreference(DeliveryPreference.ItemDestination.INBOX,
                        DeliveryPreference.PokemonDestination.INBOX),
                sampleFeeQuote());
        assertEquals(p, roundTrip(p));
    }

    @Test
    void tradeDirectoryPacketRoundTrips() {
        TradeDirectoryPacket p = new TradeDirectoryPacket(
                REQ,
                List.of(new TradeDirectoryPacket.PlayerDirectoryEntry(RIGHT, "right", TradeCapability.AVAILABLE)),
                1, 0, 20);
        assertEquals(p, roundTrip(p));
    }

    @Test
    void tradeAssetPagePacketRoundTripsAllEntryKinds() {
        TradeAssetPagePacket p = new TradeAssetPagePacket(
                REQ, LEFT, 5L, AssetPageKind.ITEMS, 0, 54, 3,
                List.of(
                        new TradeAssetPage.ItemEntry(UUID.randomUUID(), "minecraft:diamond", 16, 3),
                        new TradeAssetPage.PkmEntry(50_000L),
                        new TradeAssetPage.PokemonEntry(UUID.randomUUID(), UUID.randomUUID(),
                                "Pikachu", "alolan", 50, true, "Spark", "party", -1, 2)));
        assertEquals(p, roundTrip(p));
    }

    // ------------------------------------------------------------------
    // 恶意输入：非法枚举 / 超长列表 / 超长字符串 / 负长度
    // ------------------------------------------------------------------

    @Test
    void invalidEnumOrdinalThrowsCorruptedFrame() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(99); // TradeStatus 无此 ordinal
        assertThrows(CorruptedFrameException.class,
                () -> TradePayloadCodecs.TRADE_STATUS.decode(buf));
    }

    /** 通用 int codec（boundedList 恶意输入测试用，不依赖生产私有 codec） */
    private static final StreamCodec<ByteBuf, Integer> INT_CODEC = StreamCodec.of(
            (buf, v) -> buf.writeInt(v), buf -> buf.readInt());

    @Test
    void oversizedListLengthThrowsCorruptedFrame() {
        ByteBuf buf = Unpooled.buffer();
        VarInt.write(buf, 65); // boundedList(INT_CODEC, 64) 超上限
        assertThrows(CorruptedFrameException.class,
                () -> TradePayloadCodecs.boundedList(INT_CODEC, 64).decode(buf));
    }

    @Test
    void negativeListLengthThrowsCorruptedFrame() {
        ByteBuf buf = Unpooled.buffer();
        VarInt.write(buf, -1);
        assertThrows(CorruptedFrameException.class,
                () -> TradePayloadCodecs.boundedList(INT_CODEC, 64).decode(buf));
    }

    @Test
    void oversizedShortStringThrowsCorruptedFrame() {
        ByteBuf buf = Unpooled.buffer();
        VarInt.write(buf, 17); // SHORT_STRING 上限 16
        buf.writeCharSequence("12345678901234567", java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(CorruptedFrameException.class, () -> TradePayloadCodecs.SHORT_STRING.decode(buf));
    }

    @Test
    void oversizedDirectoryListThrowsCorruptedFrame() {
        ByteBuf buf = Unpooled.buffer();
        TradePayloadCodecs.UUID_CODEC.encode(buf, REQ); // 先写 requestId
        VarInt.write(buf, 51); // boundedList(ENTRY, 50) 超上限
        assertThrows(CorruptedFrameException.class, () -> TradeDirectoryPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void truncatedBufferThrows() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeLong(1); // 只有半个 UUID
        assertThrows(Exception.class, () -> TradePayloadCodecs.UUID_CODEC.decode(buf));
    }

    // ------------------------------------------------------------------
    // 边界校验（稳定错误码，绝不抛未捕获异常）
    // ------------------------------------------------------------------

    @Test
    void offerItemBoundaries() {
        assertEquals(TradeError.NONE,
                TradePacketBoundaries.checkOfferItem(new OfferItemPacket(REQ, TRADE, 1, 0, 1)));
        assertEquals(TradeError.INVALID_ITEM_SLOT,
                TradePacketBoundaries.checkOfferItem(new OfferItemPacket(REQ, TRADE, 1, 41, 1)));
        assertEquals(TradeError.INVALID_ITEM_SLOT,
                TradePacketBoundaries.checkOfferItem(new OfferItemPacket(REQ, TRADE, 1, -1, 1)));
        assertEquals(TradeError.INVALID_COUNT,
                TradePacketBoundaries.checkOfferItem(new OfferItemPacket(REQ, TRADE, 1, 0, 0)));
        assertEquals(TradeError.INVALID_COUNT,
                TradePacketBoundaries.checkOfferItem(new OfferItemPacket(REQ, TRADE, 1, 0, 128)));
    }

    @Test
    void offerPkmBoundaries() {
        assertEquals(TradeError.NONE,
                TradePacketBoundaries.checkOfferPkm(new OfferPkmPacket(REQ, TRADE, 1, 1)));
        assertEquals(TradeError.PKM_INVALID_AMOUNT,
                TradePacketBoundaries.checkOfferPkm(new OfferPkmPacket(REQ, TRADE, 1, 0)));
        assertEquals(TradeError.PKM_INVALID_AMOUNT,
                TradePacketBoundaries.checkOfferPkm(new OfferPkmPacket(REQ, TRADE, 1, -5)));
        assertEquals(TradeError.PKM_INVALID_AMOUNT,
                TradePacketBoundaries.checkOfferPkm(new OfferPkmPacket(REQ, TRADE, 1,
                        TradePacketLimits.MAX_PKM_AMOUNT + 1)));
    }

    @Test
    void offerPokemonBoundaries() {
        assertEquals(TradeError.NONE, TradePacketBoundaries.checkOfferPokemon(
                new OfferPokemonPacket(REQ, TRADE, 1, "party", -1, 5)));
        assertEquals(TradeError.NONE, TradePacketBoundaries.checkOfferPokemon(
                new OfferPokemonPacket(REQ, TRADE, 1, "pc", 0, 0)));
        assertEquals(TradeError.INVALID_INPUT, TradePacketBoundaries.checkOfferPokemon(
                new OfferPokemonPacket(REQ, TRADE, 1, "party", 0, 3))); // party 必须 box=-1
        assertEquals(TradeError.INVALID_INPUT, TradePacketBoundaries.checkOfferPokemon(
                new OfferPokemonPacket(REQ, TRADE, 1, "party", -1, 6))); // slot 超上限
        assertEquals(TradeError.INVALID_INPUT, TradePacketBoundaries.checkOfferPokemon(
                new OfferPokemonPacket(REQ, TRADE, 1, "pc", 256, 0))); // box 超上限
        assertEquals(TradeError.INVALID_INPUT, TradePacketBoundaries.checkOfferPokemon(
                new OfferPokemonPacket(REQ, TRADE, 1, "daycare", 0, 0))); // 未知存储
    }

    @Test
    void directoryBoundaries() {
        assertEquals(TradeError.NONE, TradePacketBoundaries.checkDirectory(
                new RequestTradeDirectoryPacket(REQ, "", 0, 10)));
        assertEquals(TradeError.INVALID_INPUT, TradePacketBoundaries.checkDirectory(
                new RequestTradeDirectoryPacket(REQ, "x".repeat(TradePacketLimits.MAX_SEARCH_LENGTH + 1), 0, 10)));
        assertEquals(TradeError.INVALID_INPUT, TradePacketBoundaries.checkDirectory(
                new RequestTradeDirectoryPacket(REQ, "", -1, 10)));
        assertEquals(TradeError.INVALID_INPUT, TradePacketBoundaries.checkDirectory(
                new RequestTradeDirectoryPacket(REQ, "", 0, 0)));
        assertEquals(TradeError.INVALID_INPUT, TradePacketBoundaries.checkDirectory(
                new RequestTradeDirectoryPacket(REQ, "", 0, TradePacketLimits.MAX_DIRECTORY_PAGE_SIZE + 1)));
    }

    @Test
    void assetPageBoundaries() {
        assertEquals(TradeError.NONE, TradePacketBoundaries.checkAssetPage(
                new RequestTradeAssetPagePacket(REQ, TRADE, 1, AssetPageKind.PARTY, 0, 10)));
        assertEquals(TradeError.INVALID_INPUT, TradePacketBoundaries.checkAssetPage(
                new RequestTradeAssetPagePacket(REQ, TRADE, 1, null, 0, 10)));
        assertEquals(TradeError.INVALID_INPUT, TradePacketBoundaries.checkAssetPage(
                new RequestTradeAssetPagePacket(REQ, TRADE, 1, AssetPageKind.ITEMS, 0,
                        TradePacketLimits.MAX_ASSET_PAGE_SIZE + 1)));
        assertEquals(TradeError.INVALID_INPUT, TradePacketBoundaries.checkAssetPage(
                new RequestTradeAssetPagePacket(REQ, TRADE, 1, AssetPageKind.PC,
                        TradePacketLimits.MAX_PAGE_NUMBER + 1, 10)));
    }

    // ------------------------------------------------------------------
    // 限流（固定窗口秒桶）
    // ------------------------------------------------------------------

    @Test
    void rateLimiterEnforcesCreateQuotaPerSecond() {
        TradeRateLimiter limiter = new TradeRateLimiter(2, 10, 5);
        UUID player = UUID.randomUUID();
        long t = 1_000_000L; // 同一秒窗口
        assertTrue(limiter.allow(player, TradeRateLimiter.Category.CREATE_OR_CONFIRM, t));
        assertTrue(limiter.allow(player, TradeRateLimiter.Category.CREATE_OR_CONFIRM, t + 100));
        assertFalse(limiter.allow(player, TradeRateLimiter.Category.CREATE_OR_CONFIRM, t + 200));
    }

    @Test
    void rateLimiterWindowRollsOver() {
        TradeRateLimiter limiter = new TradeRateLimiter(2, 10, 5);
        UUID player = UUID.randomUUID();
        long t0 = 1_000L;      // 秒 1
        long t1 = 2_000L;      // 秒 2（不同窗口）
        assertTrue(limiter.allow(player, TradeRateLimiter.Category.CREATE_OR_CONFIRM, t0));
        assertTrue(limiter.allow(player, TradeRateLimiter.Category.CREATE_OR_CONFIRM, t0 + 900));
        assertFalse(limiter.allow(player, TradeRateLimiter.Category.CREATE_OR_CONFIRM, t0 + 999));
        assertTrue(limiter.allow(player, TradeRateLimiter.Category.CREATE_OR_CONFIRM, t1)); // 新窗口重置
    }

    @Test
    void rateLimiterCategoriesAreIndependent() {
        TradeRateLimiter limiter = new TradeRateLimiter(2, 10, 5);
        UUID player = UUID.randomUUID();
        long t = 5_000L;
        assertTrue(limiter.allow(player, TradeRateLimiter.Category.CREATE_OR_CONFIRM, t));
        assertTrue(limiter.allow(player, TradeRateLimiter.Category.CREATE_OR_CONFIRM, t));
        assertFalse(limiter.allow(player, TradeRateLimiter.Category.CREATE_OR_CONFIRM, t));
        assertTrue(limiter.allow(player, TradeRateLimiter.Category.OFFER_CHANGE, t)); // 其他类别不受影响
        assertTrue(limiter.allow(player, TradeRateLimiter.Category.PAGE, t));
    }

    @Test
    void rateLimiterEvictsOldestBuckets() {
        TradeRateLimiter limiter = new TradeRateLimiter(2, 10, 5, 2); // 最多 2 个桶
        UUID player = UUID.randomUUID();
        limiter.allow(player, TradeRateLimiter.Category.PAGE, 1_000L);
        limiter.allow(player, TradeRateLimiter.Category.PAGE, 2_000L);
        limiter.allow(player, TradeRateLimiter.Category.PAGE, 3_000L); // 触发淘汰
        assertTrue(limiter.allow(player, TradeRateLimiter.Category.PAGE, 1_000L)); // 最旧桶已淘汰，重新计数
    }

    // ------------------------------------------------------------------
    // 请求去重（LRU requestId -> result）
    // ------------------------------------------------------------------

    @Test
    void requestCacheReturnsRememberedResult() {
        TradeRequestCache cache = new TradeRequestCache(128);
        assertTrue(cache.get(REQ).isEmpty());
        CachedResult ok = CachedResult.ok(TRADE, 3L);
        cache.remember(REQ, ok);
        assertEquals(ok, cache.get(REQ).orElseThrow());
    }

    @Test
    void requestCacheOverwriteSameRequestId() {
        TradeRequestCache cache = new TradeRequestCache(128);
        cache.remember(REQ, CachedResult.ok(TRADE, 3L));
        CachedResult updated = CachedResult.ok(TRADE, 4L);
        cache.remember(REQ, updated);
        assertEquals(updated, cache.get(REQ).orElseThrow());
    }

    @Test
    void requestCacheEvictsEldestAtCapacity() {
        TradeRequestCache cache = new TradeRequestCache(2);
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID r3 = UUID.randomUUID();
        cache.remember(r1, CachedResult.ok(TRADE, 1));
        cache.remember(r2, CachedResult.ok(TRADE, 2));
        cache.remember(r3, CachedResult.ok(TRADE, 3)); // 淘汰 r1（最旧）
        assertTrue(cache.get(r1).isEmpty());
        assertEquals(CachedResult.ok(TRADE, 2), cache.get(r2).orElseThrow());
        assertEquals(CachedResult.ok(TRADE, 3), cache.get(r3).orElseThrow());
    }

    @Test
    void requestCacheFailureResultCarriesNoTradeId() {
        TradeRequestCache cache = new TradeRequestCache(128);
        CachedResult fail = CachedResult.fail(TradeError.INVALID_INPUT);
        cache.remember(REQ, fail);
        CachedResult got = cache.get(REQ).orElseThrow();
        assertFalse(got.success());
        assertNull(got.tradeId());
        assertEquals(TradeError.INVALID_INPUT, got.error());
    }

    // ------------------------------------------------------------------
    // 快照投影：对手报价只含展示摘要，绝不含 NBT
    // ------------------------------------------------------------------

    @Test
    void snapshotProjectionStripsSensitiveFields() {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "minecraft:diamond");
        stack.putByte("Count", (byte) 16);
        stack.putString("tag", "{\"attack\":999}"); // 服务层完整 NBT 留在托管资产中

        CompoundTag pkmNbt = new CompoundTag();
        pkmNbt.putString("Species", "Pikachu");
        pkmNbt.putString("Form", "alolan");
        pkmNbt.putInt("Level", 50);
        pkmNbt.putByte("Shiny", (byte) 1);
        pkmNbt.putString("Nickname", "Spark");
        pkmNbt.putString("Moves", "thunderbolt"); // 隐私字段，投影不得透出
        pkmNbt.putInt("IVs", 31);

        TradeOffer selfOffer = TradeOffer.empty()
                .withAdded(new ItemAsset(UUID.randomUUID(), LEFT, stack))
                .withAdded(new PkmAsset(UUID.randomUUID(), LEFT, 10_000L, "op.1", true))
                .withAdded(new PokemonAsset(UUID.randomUUID(), LEFT, UUID.randomUUID(),
                        pkmNbt, "party", -1, 0));

        TradeSnapshot snap = new TradeSnapshot(
                new TradeId(TRADE), TradeStatus.OPEN, 4L, LEFT, RIGHT,
                selfOffer, TradeOffer.empty(),
                false, false, 1_000L, 0L,
                new DeliveryPreference(DeliveryPreference.ItemDestination.INVENTORY,
                        DeliveryPreference.PokemonDestination.PC),
                null);

        TradeSnapshotPacket projected = TradeSnapshotProjection.project(snap, "left", "right");

        // 报价摘要：物品只有 itemId+count，宝可梦只有展示字段
        assertEquals(1, projected.selfOffer().items().size());
        assertEquals("minecraft:diamond", projected.selfOffer().items().get(0).itemId());
        assertEquals(16, projected.selfOffer().items().get(0).count());
        assertEquals(10_000L, projected.selfOffer().pkmTotal());
        assertEquals(1, projected.selfOffer().pokemon().size());
        TradeSnapshotPacket.PokemonWire wire = projected.selfOffer().pokemon().get(0);
        assertEquals("Pikachu", wire.species());
        assertEquals("alolan", wire.form());
        assertEquals(50, wire.level());
        assertTrue(wire.shiny());
        assertEquals("Spark", wire.nickname());

        // 对手报价为空摘要
        assertEquals(0, projected.otherOffer().items().size());
        assertEquals(0, projected.otherOffer().pokemon().size());

        // 双方身份与状态透传
        assertEquals(LEFT, projected.selfPlayer().playerId());
        assertEquals("left", projected.selfPlayer().displayName());
        assertEquals(RIGHT, projected.otherPlayer().playerId());
        assertEquals("right", projected.otherPlayer().displayName());
        assertEquals(TradeStatus.OPEN, projected.status());
        assertEquals(4L, projected.revision());
    }

    @Test
    void snapshotProjectionPreservesFeeQuoteAndPreference() {
        TradeFeeQuote quote = sampleFeeQuote();
        TradeSnapshot snap = new TradeSnapshot(
                new TradeId(TRADE), TradeStatus.LOCKED, 5L, LEFT, RIGHT,
                TradeOffer.empty(), TradeOffer.empty(),
                true, true, 1_000L, 2_000L,
                new DeliveryPreference(DeliveryPreference.ItemDestination.INBOX,
                        DeliveryPreference.PokemonDestination.INBOX),
                quote);
        TradeSnapshotPacket projected = TradeSnapshotProjection.project(snap, "left", "right");
        assertEquals(quote, projected.feeQuote());
        assertEquals(DeliveryPreference.ItemDestination.INBOX,
                projected.selfDeliveryPreference().itemDestination());
        assertTrue(projected.selfConfirmed());
        assertTrue(projected.otherConfirmed());
        assertEquals(2_000L, projected.lockDeadlineEpochMillis());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 编码后立即解码，返回与输入类型相同的包 */
    private static <T> T roundTrip(T payload) {
        if (payload instanceof CreateTradePacket p) {
            return cast(CreateTradePacket.STREAM_CODEC.decode(wrap(CreateTradePacket.STREAM_CODEC, p)));
        }
        if (payload instanceof AcceptTradePacket p) {
            return cast(AcceptTradePacket.STREAM_CODEC.decode(wrap(AcceptTradePacket.STREAM_CODEC, p)));
        }
        if (payload instanceof OfferItemPacket p) {
            return cast(OfferItemPacket.STREAM_CODEC.decode(wrap(OfferItemPacket.STREAM_CODEC, p)));
        }
        if (payload instanceof OfferPkmPacket p) {
            return cast(OfferPkmPacket.STREAM_CODEC.decode(wrap(OfferPkmPacket.STREAM_CODEC, p)));
        }
        if (payload instanceof OfferPokemonPacket p) {
            return cast(OfferPokemonPacket.STREAM_CODEC.decode(wrap(OfferPokemonPacket.STREAM_CODEC, p)));
        }
        if (payload instanceof RemoveOfferAssetPacket p) {
            return cast(RemoveOfferAssetPacket.STREAM_CODEC.decode(wrap(RemoveOfferAssetPacket.STREAM_CODEC, p)));
        }
        if (payload instanceof ConfirmTradePacket p) {
            return cast(ConfirmTradePacket.STREAM_CODEC.decode(wrap(ConfirmTradePacket.STREAM_CODEC, p)));
        }
        if (payload instanceof CancelTradePacket p) {
            return cast(CancelTradePacket.STREAM_CODEC.decode(wrap(CancelTradePacket.STREAM_CODEC, p)));
        }
        if (payload instanceof SetDeliveryPreferencePacket p) {
            return cast(SetDeliveryPreferencePacket.STREAM_CODEC.decode(wrap(SetDeliveryPreferencePacket.STREAM_CODEC, p)));
        }
        if (payload instanceof RequestTradeDirectoryPacket p) {
            return cast(RequestTradeDirectoryPacket.STREAM_CODEC.decode(wrap(RequestTradeDirectoryPacket.STREAM_CODEC, p)));
        }
        if (payload instanceof RequestTradeAssetPagePacket p) {
            return cast(RequestTradeAssetPagePacket.STREAM_CODEC.decode(wrap(RequestTradeAssetPagePacket.STREAM_CODEC, p)));
        }
        if (payload instanceof TradeResultPacket p) {
            return cast(TradeResultPacket.STREAM_CODEC.decode(wrap(TradeResultPacket.STREAM_CODEC, p)));
        }
        if (payload instanceof TradeSnapshotPacket p) {
            return cast(TradeSnapshotPacket.STREAM_CODEC.decode(wrap(TradeSnapshotPacket.STREAM_CODEC, p)));
        }
        if (payload instanceof TradeDirectoryPacket p) {
            return cast(TradeDirectoryPacket.STREAM_CODEC.decode(wrap(TradeDirectoryPacket.STREAM_CODEC, p)));
        }
        if (payload instanceof TradeAssetPagePacket p) {
            return cast(TradeAssetPagePacket.STREAM_CODEC.decode(wrap(TradeAssetPagePacket.STREAM_CODEC, p)));
        }
        throw new AssertionError("unhandled payload " + payload);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }

    private static <T> ByteBuf wrap(net.minecraft.network.codec.StreamCodec<ByteBuf, T> codec, T payload) {
        ByteBuf buf = Unpooled.buffer();
        codec.encode(buf, payload);
        return buf;
    }

    private static TradeSnapshotPacket sampleSnapshotPacket() {
        return new TradeSnapshotPacket(
                TRADE, 4L, TradeStatus.OPEN,
                new TradeSnapshotPacket.PlayerSummary(LEFT, "left"),
                new TradeSnapshotPacket.PlayerSummary(RIGHT, "right"),
                sampleOfferSummary(), TradeSnapshotPacket.OfferSummary.empty(),
                false, false, 1_000L, 0L,
                TradeCapability.BUSY, TradeCapability.BUSY,
                new DeliveryPreference(DeliveryPreference.ItemDestination.AUTO,
                        DeliveryPreference.PokemonDestination.AUTO),
                null);
    }

    private static TradeSnapshotPacket.OfferSummary sampleOfferSummary() {
        return new TradeSnapshotPacket.OfferSummary(
                List.of(new TradeSnapshotPacket.ItemWire("minecraft:diamond", 16)),
                10_000L,
                List.of(new TradeSnapshotPacket.PokemonWire(
                        UUID.randomUUID(), "Pikachu", "", 50, false, "")));
    }

    private static TradeFeeQuote sampleFeeQuote() {
        return new TradeFeeQuote(
                UUID.randomUUID(), TRADE, 5L, 3_000L, 100L, 200L,
                List.of(new TradeFeeQuote.ItemFee("minecraft:emerald", 4, LEFT)),
                "flat", 1);
    }
}
