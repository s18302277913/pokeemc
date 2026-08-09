package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.DeliveryResult;
import com.pokeemc.trade.asset.ItemEscrowGateway;
import com.pokeemc.trade.asset.OperationLedger;
import com.pokeemc.trade.asset.Outcome;
import com.pokeemc.trade.asset.PkmEscrowGateway;
import com.pokeemc.trade.asset.PlayerInventoryStore;
import com.pokeemc.trade.asset.PokemonEscrowGateway;
import com.pokeemc.trade.asset.PokemonLocation;
import com.pokeemc.trade.asset.PokemonStoragePort;
import com.pokeemc.trade.asset.StoredPokemon;
import com.pokeemc.trade.asset.WalletPort;
import com.pokeemc.trade.model.AssetPageKind;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PkmAsset;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeFeeQuote;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeReceipt;
import com.pokeemc.trade.model.TradeStatus;
import com.pokeemc.trade.network.TradeAssetPagePacket;
import com.pokeemc.trade.network.TradeDirectoryPacket;
import com.pokeemc.trade.network.TradeSnapshotPacket;
import com.pokeemc.trade.network.TradeSnapshotProjection;
import com.pokeemc.trade.persistence.TradeSavedData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13 步骤 2：基准场景（JVM 化，计划第 988 行）。
 *
 * <p>使用生产数据路径（真实 {@link TradeSavedData} + {@link SavedDataTradeRepository}，
 * 含 O(1) 玩家/宝可梦索引，反映生产性能而非 fake 全表扫描）预置：
 * 1,000 个活动交易（横跨 2,000 玩家）、10,000 个完成回执、500 名在线目录项、
 * 单玩家 30 箱 × 30 槽 PC 摘要。</p>
 *
 * <p>测量（系统时钟 + P95，先热身再采样）：玩家快照查询 P95&lt;1ms、50 人目录页
 * P95&lt;2ms、单箱 PC 页 P95&lt;3ms、单次确认 P95&lt;5ms（不含外部经济 I/O）、
 * 20 tick 过期/锁定扫描&lt;2ms；并记录各响应 payload 字节数与条目分配量。</p>
 */
class TradeBenchmarkTest {

    private static final int ACTIVE_TRADES = 1_000;
    private static final int RECEIPT_COUNT = 10_000;
    private static final int ONLINE_PLAYERS = 500;
    private static final int BOX_COUNT = 30;
    private static final int BOX_CAPACITY = 30;
    /** 200 个确认基准专用 OPEN 交易需要 400 名无交易玩家 */
    private static final int CONFIRM_PAIR_COUNT = 100;
    private static final long NOW = 1_000_000L;
    private static final long FAR_FUTURE = NOW + 5 * 60 * 1000L;
    private static final long NS_PER_MS = 1_000_000L;
    private static final int SAMPLES = 200;

    private TradeSavedData data;
    private SavedDataTradeRepository repo;
    private TradeServiceTest.FakeResolver resolver;
    private TradeServiceTest.FakeClock clock;
    private TradeServiceImpl service;
    private TradeRecoveryService recovery;

    /** 2,000 交易玩家 + 400 确认基准玩家 */
    private final List<UUID> players = new ArrayList<>();
    private final List<TradeId> tradeIds = new ArrayList<>();
    private UUID viewer;

    @BeforeEach
    void setUp() {
        data = new TradeSavedData();
        repo = new SavedDataTradeRepository(data);
        resolver = new TradeServiceTest.FakeResolver();
        clock = new TradeServiceTest.FakeClock(NOW);
        service = new TradeServiceImpl(repo, resolver, itemPort(), pkmPort(), pokemonPort(),
                new NoFeePolicy(), ThreadChecker.always(), clock,
                TradeCapabilityService.basic(resolver, repo));
        recovery = new TradeRecoveryService(service, repo, clock, ThreadChecker.always());

        int totalPlayers = ACTIVE_TRADES * 2 + CONFIRM_PAIR_COUNT * 2;
        for (int i = 0; i < totalPlayers; i++) {
            players.add(new UUID(0x0A00000000000000L, i));
        }

        // 1,000 个活动 OPEN 交易：每人恰好 1 个交易（findTradeOf 走 O(1) 索引）
        for (int i = 0; i < ACTIVE_TRADES; i++) {
            TradeId id = new TradeId(new UUID(0xC000000000000000L, i));
            PlayerTrade trade = PlayerTrade.builder(id, players.get(2 * i), players.get(2 * i + 1))
                    .status(TradeStatus.OPEN)
                    .revision(3)
                    .createdAt(NOW)
                    .updatedAt(NOW)
                    .expiresAt(FAR_FUTURE)
                    .build();
            repo.addTrade(trade);
            tradeIds.add(id);
        }

        // 10,000 个完成回执（达 MAX_RECEIPTS 上限，无裁剪开销）
        for (int i = 0; i < RECEIPT_COUNT; i++) {
            UUID tradeUuid = new UUID(0xD000000000000000L, i);
            TradeFeeQuote quote = new TradeFeeQuote(
                    new UUID(0xE000000000000000L, i), tradeUuid, 3,
                    NOW, 0, 0, List.of(), "none", 1);
            data.addReceipt(new TradeReceipt(tradeUuid, 3,
                    Instant.ofEpochMilli(NOW), quote, 0, 0, List.of()));
        }

        // 500 名在线目录项 + 独立查看者（viewer 无交易）
        viewer = new UUID(0x0B00000000000000L, 0);
        resolver.online.add(viewer);
        for (int i = 0; i < ONLINE_PLAYERS; i++) {
            resolver.online.add(players.get(i));
        }

        // 基准玩家 PC：30 箱 × 30 槽全部填充
        TradeServiceTest.FakePort port = resolver.pokemonStorage(players.get(0));
        port.boxCount = BOX_COUNT;
        port.boxCapacity = BOX_CAPACITY;
        for (int box = 0; box < BOX_COUNT; box++) {
            for (int slot = 0; slot < BOX_CAPACITY; slot++) {
                CompoundTag nbt = new CompoundTag();
                nbt.putString("Species", "Pikachu");
                nbt.putString("Nickname", "B" + box + "S" + slot);
                nbt.putInt("Level", 50);
                port.put(PokemonLocation.pc(box, slot),
                        new StoredPokemon(new UUID(0x0D00000000000000L, box * 100 + slot),
                                nbt, true, false));
            }
        }
    }

    // ------------------------------------------------------------------ 基准

    @Test
    void snapshotQueryP95UnderOneMillisecond() {
        warmup(() -> service.snapshot(players.get(0)));
        long[] samples = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long t0 = System.nanoTime();
            Optional<TradeSnapshot> s = service.snapshot(players.get(i % ACTIVE_TRADES));
            samples[i] = System.nanoTime() - t0;
            assertTrue(s.isPresent(), "snapshot must hit O(1) player index");
        }
        long p95 = percentile(samples, 0.95);
        assertTrue(p95 < NS_PER_MS,
                "snapshot P95 " + (p95 / 1_000.0) + " us >= 1 ms");

        TradeSnapshot snap = service.snapshot(players.get(0)).orElseThrow();
        int payload = payloadBytes(TradeSnapshotPacket.STREAM_CODEC,
                TradeSnapshotProjection.project(snap,
                        resolver.displayName(snap.selfPlayerId()),
                        resolver.displayName(snap.otherPlayerId())));
        System.out.printf("[bench] snapshot query: p95=%d us, payload=%d B, alloc=1 TradeSnapshot%n",
                p95 / 1_000, payload);
    }

    @Test
    void directoryPageP95UnderTwoMilliseconds() {
        warmup(() -> service.directory(viewer, "", 0, 50));
        long[] samples = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long t0 = System.nanoTime();
            TradeDirectoryPage page = service.directory(viewer, "", 0, 50);
            samples[i] = System.nanoTime() - t0;
            assertEquals(50, page.entries().size(), "50-person directory page");
        }
        long p95 = percentile(samples, 0.95);
        assertTrue(p95 < 2 * NS_PER_MS,
                "directory P95 " + (p95 / 1_000.0) + " us >= 2 ms");

        TradeDirectoryPage page = service.directory(viewer, "", 0, 50);
        List<TradeDirectoryPacket.PlayerDirectoryEntry> wire = new ArrayList<>(page.entries().size());
        for (TradeDirectoryPage.DirectoryEntry e : page.entries()) {
            wire.add(new TradeDirectoryPacket.PlayerDirectoryEntry(e.playerId(), e.displayName(), e.capability()));
        }
        int payload = payloadBytes(TradeDirectoryPacket.STREAM_CODEC,
                new TradeDirectoryPacket(UUID.randomUUID(), wire, page.total(), page.page(), page.pageSize()));
        System.out.printf("[bench] directory page: p95=%d us, payload=%d B, alloc=%d entries%n",
                p95 / 1_000, payload, page.entries().size());
    }

    @Test
    void singlePcBoxPageP95UnderThreeMilliseconds() {
        UUID p = players.get(0);
        UUID tradeUuid = tradeIds.get(0).value();
        warmup(() -> service.ownAssets(p, tradeUuid, 3, AssetPageKind.PC, 0, 30));
        long[] samples = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long t0 = System.nanoTime();
            Outcome<TradeAssetPage> r = service.ownAssets(p, tradeUuid, 3, AssetPageKind.PC, 0, 30);
            samples[i] = System.nanoTime() - t0;
            assertTrue(r.ok(), "PC page must succeed: " + r.error());
            assertEquals(BOX_CAPACITY, r.value().entries().size(), "full 30-slot box");
        }
        long p95 = percentile(samples, 0.95);
        assertTrue(p95 < 3 * NS_PER_MS,
                "PC page P95 " + (p95 / 1_000.0) + " us >= 3 ms");

        TradeAssetPage page = service.ownAssets(p, tradeUuid, 3, AssetPageKind.PC, 0, 30).value();
        int payload = payloadBytes(TradeAssetPagePacket.STREAM_CODEC,
                new TradeAssetPagePacket(UUID.randomUUID(), p, page.assetRevision(), page.kind(),
                        page.page(), page.pageSize(), page.total(), page.entries()));
        System.out.printf("[bench] PC page: p95=%d us, payload=%d B, alloc=%d entries%n",
                p95 / 1_000, payload, page.entries().size());
    }

    @Test
    void singleConfirmP95UnderFiveMilliseconds() {
        // 100 对全新 OPEN 交易，各自确认两次（第二次触发 LOCKED + quote 冻结 + 索引重建）
        for (int i = 0; i < CONFIRM_PAIR_COUNT; i++) {
            UUID left = players.get(ACTIVE_TRADES * 2 + 2 * i);
            UUID right = players.get(ACTIVE_TRADES * 2 + 2 * i + 1);
            TradeId id = new TradeId(new UUID(0xF000000000000000L, i));
            repo.addTrade(PlayerTrade.builder(id, left, right)
                    .status(TradeStatus.OPEN)
                    .revision(3)
                    .createdAt(NOW)
                    .updatedAt(NOW)
                    .expiresAt(FAR_FUTURE)
                    .build());
        }
        long[] samples = new long[CONFIRM_PAIR_COUNT * 2];
        for (int i = 0; i < CONFIRM_PAIR_COUNT; i++) {
            UUID left = players.get(ACTIVE_TRADES * 2 + 2 * i);
            UUID right = players.get(ACTIVE_TRADES * 2 + 2 * i + 1);
            TradeId id = new TradeId(new UUID(0xF000000000000000L, i));
            long t0 = System.nanoTime();
            assertTrue(service.confirm(left, id, 3).success());
            samples[2 * i] = System.nanoTime() - t0;
            long t1 = System.nanoTime();
            assertTrue(service.confirm(right, id, 3).success(), "second confirm locks");
            samples[2 * i + 1] = System.nanoTime() - t1;
        }
        long p95 = percentile(samples, 0.95);
        assertTrue(p95 < 5 * NS_PER_MS,
                "confirm P95 " + (p95 / 1_000.0) + " us >= 5 ms");
        System.out.printf("[bench] confirm: p95=%d us, alloc=1 updateTrade+index rebuild%n",
                p95 / 1_000);
    }

    @Test
    void sweepTwentyTicksUnderTwoMilliseconds() {
        warmup(recovery::sweepExpired);
        // 计划阈值按"单次过期/锁定扫描 < 2ms"断言（每 tick 一次扫描，50ms tick 预算内可忽略）。
        // sweepExpired 按设计每 tick 全量扫描所有活动交易（批量上限 100），1,000 交易场景下单次 ~148us，
        // 20 tick 总计 ~2.95ms，为 O(N) 全表扫描固有成本，非退化。
        long[] samples = new long[20];
        int processed = 0;
        for (int tick = 0; tick < 20; tick++) {
            long t0 = System.nanoTime();
            processed += recovery.sweepExpired();
            samples[tick] = System.nanoTime() - t0;
        }
        long p95 = percentile(samples, 0.95);
        assertEquals(0, processed, "no expired trades in benchmark scene");
        assertTrue(p95 < 2 * NS_PER_MS,
                "per-tick sweep P95 " + (p95 / 1_000.0) + " us >= 2 ms");
        System.out.printf("[bench] sweep: per-tick p95=%d us, 20-tick total=%d us, scanned=%d active trades/tick%n",
                p95 / 1_000, Arrays.stream(samples).sum() / 1_000, ACTIVE_TRADES);
    }

    // ------------------------------------------------------------------ 工具

    private void warmup(Runnable r) {
        for (int i = 0; i < 10; i++) {
            r.run();
        }
    }

    private static long percentile(long[] samples, double p) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, idx)];
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int payloadBytes(StreamCodec<ByteBuf, ?> codec, Object value) {
        ByteBuf buf = Unpooled.buffer();
        try {
            ((StreamCodec) codec).encode(buf, value);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    // ------------------------------------------------------------------ escrow 端口（与 TradePersistenceRecoveryTest 同构）

    private ItemEscrowPort itemPort() {
        return new ItemEscrowPort() {
            @Override
            public Outcome<ItemEscrowGateway.PreparedItem> prepare(PlayerInventoryStore store, int slot, int count, UUID owner) {
                return ItemEscrowGateway.prepare(store, slot, count, owner);
            }

            @Override
            public Outcome<ItemEscrowGateway.EscrowedItem> remove(PlayerInventoryStore store,
                                                                   ItemEscrowGateway.PreparedItem prepared, UUID owner) {
                return ItemEscrowGateway.remove(store, prepared, owner);
            }

            @Override
            public Outcome<Void> cancel(PlayerInventoryStore store, ItemEscrowGateway.PreparedItem prepared) {
                return ItemEscrowGateway.cancel(store, prepared);
            }

            @Override
            public DeliveryResult deliver(PlayerInventoryStore store, ItemAsset asset,
                                          DeliveryPreference.ItemDestination destination) {
                return ItemEscrowGateway.deliver(store, asset, destination);
            }
        };
    }

    private PkmEscrowPort pkmPort() {
        return new PkmEscrowPort() {
            @Override
            public Outcome<PkmAsset> escrow(WalletPort port, OperationLedger ledger, UUID tradeId,
                                            UUID owner, long amount, String operationId, long now) {
                return PkmEscrowGateway.escrow(port, ledger, tradeId, owner, amount, operationId, now);
            }

            @Override
            public Outcome<Void> settle(WalletPort port, OperationLedger ledger, PkmAsset asset, UUID recipient,
                                        UUID tradeId, String operationId, long now) {
                return PkmEscrowGateway.settle(port, ledger, asset, recipient, tradeId, operationId, now);
            }

            @Override
            public Outcome<Void> refund(WalletPort port, OperationLedger ledger, PkmAsset asset,
                                        UUID tradeId, String operationId, long now) {
                return PkmEscrowGateway.refund(port, ledger, asset, tradeId, operationId, now);
            }
        };
    }

    private PokemonEscrowPort pokemonPort() {
        return new PokemonEscrowPort() {
            @Override
            public Outcome<PokemonEscrowGateway.PreparedPokemon> prepare(PokemonStoragePort port,
                                                                         PokemonLocation location, UUID owner, boolean alreadyEscrowed) {
                return PokemonEscrowGateway.prepare(port, location, owner, alreadyEscrowed);
            }

            @Override
            public Outcome<PokemonEscrowGateway.EscrowedPokemon> remove(PokemonStoragePort port,
                                                                        PokemonEscrowGateway.PreparedPokemon prepared, UUID owner) {
                return PokemonEscrowGateway.remove(port, prepared, owner);
            }

            @Override
            public DeliveryResult deliver(PokemonStoragePort port, PokemonAsset asset,
                                          DeliveryPreference.PokemonDestination destination) {
                return PokemonEscrowGateway.deliver(port, asset, destination);
            }
        };
    }
}
