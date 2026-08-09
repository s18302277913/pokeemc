package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.DeliveryResult;
import com.pokeemc.trade.asset.ItemEscrowGateway;
import com.pokeemc.trade.asset.ItemSnapshot;
import com.pokeemc.trade.asset.PlayerInventoryStore;
import com.pokeemc.trade.asset.OperationLedger;
import com.pokeemc.trade.asset.Outcome;
import com.pokeemc.trade.asset.PlayerInventoryStore;
import com.pokeemc.trade.asset.PokemonEscrowGateway;
import com.pokeemc.trade.asset.PokemonLocation;
import com.pokeemc.trade.asset.PokemonStoragePort;
import com.pokeemc.trade.asset.PokemonSummaryReader;
import com.pokeemc.trade.asset.StoredPokemon;
import com.pokeemc.trade.asset.WalletAccount;
import com.pokeemc.trade.model.AssetPageKind;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PkmAsset;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeAsset;
import com.pokeemc.trade.model.TradeCapability;
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeFeeQuote;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeOffer;
import com.pokeemc.trade.model.TradeReceipt;
import com.pokeemc.trade.model.TradeSide;
import com.pokeemc.trade.model.TradeStatus;
import com.pokeemc.trade.persistence.InboxEntry;
import com.pokeemc.trade.persistence.OperationEntry;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 交易服务核心实现（Task 6，计划 4.1/5.3）。
 *
 * <p>全部写方法第一行断言服务端主线程（{@link ThreadChecker}）。所有对
 * Minecraft/Pixelmon 存储的读写都经 {@link PlayerStorageResolver} 与三个
 * escrow 端口抽象，可在 JVM 单测驱动（fake repo/resolver/ports）。</p>
 *
 * <p>提交（{@link #commit}）顺序（计划 Task 6 步骤 3）：先落盘 COMMITTING 提交意图，
 * 再在同一个持久化流程中把双方托管资产原子移入对手收件箱批次并标记 COMMITTED，
 * 随后写回执、进入 DELIVERING 并按冻结偏好尝试交付；外部交付不得夹在两步之间。</p>
 */
public final class TradeServiceImpl implements TradeService {

    private final TradeRepository repo;
    private final PlayerStorageResolver resolver;
    private final ItemEscrowPort itemEscrow;
    private final PkmEscrowPort pkmEscrow;
    private final PokemonEscrowPort pokemonEscrow;
    private final TradeFeePolicy feePolicy;
    private final ThreadChecker threadChecker;
    private final Clock clock;
    private final TradeCapabilityService capabilityService;

    public TradeServiceImpl(
            TradeRepository repo,
            PlayerStorageResolver resolver,
            ItemEscrowPort itemEscrow,
            PkmEscrowPort pkmEscrow,
            PokemonEscrowPort pokemonEscrow,
            TradeFeePolicy feePolicy,
            ThreadChecker threadChecker,
            Clock clock,
            TradeCapabilityService capabilityService) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.itemEscrow = Objects.requireNonNull(itemEscrow, "itemEscrow");
        this.pkmEscrow = Objects.requireNonNull(pkmEscrow, "pkmEscrow");
        this.pokemonEscrow = Objects.requireNonNull(pokemonEscrow, "pokemonEscrow");
        this.feePolicy = Objects.requireNonNull(feePolicy, "feePolicy");
        this.threadChecker = Objects.requireNonNull(threadChecker, "threadChecker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capabilityService = Objects.requireNonNull(capabilityService, "capabilityService");
    }

    private long now() {
        return clock.millis();
    }

    // ------------------------------------------------------------------ 邀请 / 接受

    @Override
    public TradeResult invite(UUID initiatorId, UUID targetId) {
        threadChecker.check();
        TradeError error = TradeValidator.validateInvite(initiatorId, targetId, repo, resolver);
        if (error != TradeError.NONE) {
            return TradeResult.fail(error);
        }
        PlayerTrade trade = PlayerTrade.invited(TradeId.random(), initiatorId, targetId, now());
        repo.addTrade(trade);
        return TradeResult.ok(trade.tradeId(), trade.revision());
    }

    @Override
    public TradeResult accept(UUID playerId, TradeId tradeId, long revision) {
        threadChecker.check();
        return withTrade(playerId, tradeId, trade -> {
            if (trade.status() != TradeStatus.INVITED) {
                return TradeResult.fail(tradeId, TradeError.INVALID_STATE);
            }
            // 只有被邀请方（RIGHT）能接受，发起方不能接受自己的邀请
            if (playerId.equals(trade.leftPlayerId())) {
                return TradeResult.fail(tradeId, TradeError.INVALID_STATE);
            }
            TradeError e = TradeValidator.validateRevision(trade, revision);
            if (e != TradeError.NONE) {
                return TradeResult.fail(tradeId, e);
            }
            e = TradeValidator.validateNotExpired(trade, now());
            if (e != TradeError.NONE) {
                return TradeResult.fail(tradeId, e);
            }
            trade.accept(now());
            return TradeResult.ok(tradeId, trade.revision());
        });
    }

    // ------------------------------------------------------------------ 报价

    @Override
    public TradeResult offerItem(UUID playerId, TradeId tradeId, long revision, int slot, int count) {
        threadChecker.check();
        return withTrade(playerId, tradeId, trade -> {
            TradeError pre = preflight(trade, revision);
            if (pre != TradeError.NONE) {
                return TradeResult.fail(tradeId, pre);
            }
            TradeSide side = TradeSide.of(playerId, trade);
            if (trade.offerOf(side).items().size() >= TradeOffer.MAX_ITEMS) {
                return TradeResult.fail(tradeId, TradeError.OFFER_LIMIT_REACHED);
            }
            PlayerInventoryStore store = resolver.inventory(playerId);
            Outcome<ItemEscrowGateway.PreparedItem> prepared = itemEscrow.prepare(store, slot, count, playerId);
            if (!prepared.ok()) {
                return TradeResult.fail(tradeId, prepared.error());
            }
            Outcome<ItemEscrowGateway.EscrowedItem> removed = itemEscrow.remove(store, prepared.value(), playerId);
            if (!removed.ok()) {
                return TradeResult.fail(tradeId, removed.error());
            }
            TradeOffer next;
            try {
                next = trade.offerOf(side).withAdded(removed.value().asset());
            } catch (IllegalArgumentException ex) {
                // 容量已预检，此分支仅防重复 assetId（不应发生）；归还已扣减的物品
                itemEscrow.cancel(store, prepared.value());
                return TradeResult.fail(tradeId, TradeError.OFFER_LIMIT_REACHED);
            }
            trade.replaceOffer(side, next, now());
            return TradeResult.ok(tradeId, trade.revision());
        });
    }

    @Override
    public TradeResult offerPkm(UUID playerId, TradeId tradeId, long revision, long amount) {
        threadChecker.check();
        return withTrade(playerId, tradeId, trade -> {
            TradeError pre = preflight(trade, revision);
            if (pre != TradeError.NONE) {
                return TradeResult.fail(tradeId, pre);
            }
            TradeSide side = TradeSide.of(playerId, trade);
            // 稳定幂等键：每方每交易至多一个 PKM 条目
            String operationId = tradeId.keyPrefix() + ":pkm:" + side.networkName();
            // 幂等短路：同一 operation 已应用且该资产已在报价中（客户端重复请求）
            Optional<OperationEntry> existing = repo.get(operationId);
            if (existing.isPresent()
                    && existing.get().state() == OperationEntry.OperationState.APPLIED
                    && trade.offerOf(side).find(existing.get().assetId()).isPresent()) {
                return TradeResult.ok(tradeId, trade.revision());
            }
            if (trade.offerOf(side).pkm().size() >= TradeOffer.MAX_PKM_ENTRIES) {
                return TradeResult.fail(tradeId, TradeError.OFFER_LIMIT_REACHED);
            }
            Outcome<PkmAsset> escrowed = pkmEscrow.escrow(
                    resolver.wallet(), repo, tradeId.value(), playerId, amount, operationId, now());
            if (!escrowed.ok()) {
                return TradeResult.fail(tradeId, escrowed.error());
            }
            PkmAsset asset = escrowed.value();
            TradeOffer next;
            try {
                next = trade.offerOf(side).withAdded(asset);
            } catch (IllegalArgumentException ex) {
                String opId = tradeId.keyPrefix() + ":refund:" + asset.assetId();
                pkmEscrow.refund(resolver.wallet(), repo, asset, tradeId.value(), opId, now());
                return TradeResult.fail(tradeId, TradeError.OFFER_LIMIT_REACHED);
            }
            trade.replaceOffer(side, next, now());
            return TradeResult.ok(tradeId, trade.revision());
        });
    }

    @Override
    public TradeResult offerPokemon(UUID playerId, TradeId tradeId, long revision, PokemonLocator locator) {
        threadChecker.check();
        return withTrade(playerId, tradeId, trade -> {
            TradeError pre = preflight(trade, revision);
            if (pre != TradeError.NONE) {
                return TradeResult.fail(tradeId, pre);
            }
            TradeSide side = TradeSide.of(playerId, trade);
            if (trade.offerOf(side).pokemon().size() >= TradeOffer.MAX_POKEMON) {
                return TradeResult.fail(tradeId, TradeError.OFFER_LIMIT_REACHED);
            }
            PokemonStoragePort storage = resolver.pokemonStorage(playerId);
            PokemonLocation location = locator.toLocation();
            // 幂等：报价已含该宝可梦（同 UUID）-> 直接成功
            Optional<StoredPokemon> current = storage.at(location);
            if (current.isPresent()) {
                UUID pid = current.get().pokemonId();
                boolean alreadyOffered = trade.offerOf(side).pokemon().stream()
                        .anyMatch(a -> a.pokemonId().equals(pid));
                if (alreadyOffered) {
                    return TradeResult.ok(tradeId, trade.revision());
                }
            }
            boolean alreadyEscrowed = current.isPresent()
                    && repo.findTradeByPokemon(current.get().pokemonId())
                    .map(t -> !t.equals(tradeId))
                    .orElse(false);
            Outcome<PokemonEscrowGateway.PreparedPokemon> prepared =
                    pokemonEscrow.prepare(storage, location, playerId, alreadyEscrowed);
            if (!prepared.ok()) {
                return TradeResult.fail(tradeId, prepared.error());
            }
            Outcome<PokemonEscrowGateway.EscrowedPokemon> removed =
                    pokemonEscrow.remove(storage, prepared.value(), playerId);
            if (!removed.ok()) {
                return TradeResult.fail(tradeId, removed.error());
            }
            TradeOffer next;
            try {
                next = trade.offerOf(side).withAdded(removed.value().asset());
            } catch (IllegalArgumentException ex) {
                // 容量已预检，此分支仅防重复 assetId；归还已移除的宝可梦
                returnToOwner(tradeId, removed.value().asset(), trade.revision(), now());
                return TradeResult.fail(tradeId, TradeError.OFFER_LIMIT_REACHED);
            }
            trade.replaceOffer(side, next, now());
            return TradeResult.ok(tradeId, trade.revision());
        });
    }

    @Override
    public TradeResult removeAsset(UUID playerId, TradeId tradeId, long revision, UUID assetId) {
        threadChecker.check();
        return withTrade(playerId, tradeId, trade -> {
            TradeError pre = preflight(trade, revision);
            if (pre != TradeError.NONE) {
                return TradeResult.fail(tradeId, pre);
            }
            TradeSide side = TradeSide.of(playerId, trade);
            TradeOffer offer = trade.offerOf(side);
            Optional<TradeAsset> found = offer.find(assetId);
            if (found.isEmpty()) {
                return TradeResult.fail(tradeId, TradeError.ASSET_NOT_OWNED);
            }
            TradeAsset asset = found.get();
            long now = now();
            trade.replaceOffer(side, offer.without(assetId), now);
            returnToOwner(tradeId, asset, trade.revision(), now);
            return TradeResult.ok(tradeId, trade.revision());
        });
    }

    @Override
    public TradeResult setDeliveryPreference(UUID playerId, TradeId tradeId, long revision,
                                             DeliveryPreference preference) {
        threadChecker.check();
        return withTrade(playerId, tradeId, trade -> {
            TradeError pre = preflight(trade, revision);
            if (pre != TradeError.NONE) {
                return TradeResult.fail(tradeId, pre);
            }
            trade.setDeliveryPreference(TradeSide.of(playerId, trade), preference, now());
            repo.setPreference(playerId, preference);
            return TradeResult.ok(tradeId, trade.revision());
        });
    }

    // ------------------------------------------------------------------ 确认 / 取消 / 提交 / 领取

    @Override
    public TradeResult confirm(UUID playerId, TradeId tradeId, long revision) {
        threadChecker.check();
        return withTrade(playerId, tradeId, trade -> {
            TradeError pre = preflight(trade, revision);
            if (pre != TradeError.NONE) {
                return TradeResult.fail(tradeId, pre);
            }
            long now = now();
            boolean locked = trade.confirm(TradeSide.of(playerId, trade), revision, now);
            if (locked) {
                TradeFeeQuote quote = feePolicy.quote(new TradeFeeContext(
                        tradeId.value(), trade.revision(),
                        trade.leftOffer(), trade.rightOffer(),
                        trade.leftPlayerId(), Instant.ofEpochMilli(now)));
                trade.freezeFeeQuote(quote, now);
            }
            return TradeResult.ok(tradeId, trade.revision());
        });
    }

    @Override
    public TradeResult cancel(UUID playerId, TradeId tradeId, long revision) {
        threadChecker.check();
        Optional<PlayerTrade> opt = repo.getTrade(tradeId);
        if (opt.isEmpty()) {
            return TradeResult.fail(tradeId, TradeError.TRADE_NOT_FOUND);
        }
        PlayerTrade trade = opt.get();
        if (!trade.isParticipant(playerId)) {
            return TradeResult.fail(tradeId, TradeError.NOT_PARTICIPANT);
        }
        if (revision != trade.revision()) {
            return TradeResult.fail(tradeId, TradeError.STALE_REVISION);
        }
        if (!trade.status().cancellable()) {
            return TradeResult.fail(tradeId, TradeError.INVALID_STATE);
        }
        return cancelInternal(trade, now());
    }

    /**
     * 取消核心（可从正常取消或崩溃恢复进入）：
     * 全部已托管资产幂等移入各自原所有者收件箱（附本人全局偏好，跳过已存在防重入）、
     * 标记 CANCELLED、移除交易并尝试交付给在线的原所有者。
     */
    private TradeResult cancelInternal(PlayerTrade trade, long now) {
        TradeId tradeId = trade.tradeId();
        if (trade.status().cancellable()) {
            trade.beginCancel(now);
        } else if (trade.status() != TradeStatus.CANCELLING) {
            return TradeResult.fail(tradeId, TradeError.INVALID_STATE);
        }
        List<TradeAsset> assets = new ArrayList<>();
        assets.addAll(trade.leftOffer().allAssets());
        assets.addAll(trade.rightOffer().allAssets());
        for (TradeAsset asset : assets) {
            if (!hasInboxEntry(trade, asset)) {
                inbox(tradeId, asset, asset.originalOwner(),
                        repo.getPreference(asset.originalOwner()), trade.revision(), now);
            }
        }
        trade.markCancelled(now);
        repo.removeTrade(tradeId);
        // 尝试交付给在线的原所有者
        Set<UUID> owners = new LinkedHashSet<>();
        for (TradeAsset asset : assets) {
            owners.add(asset.originalOwner());
        }
        for (UUID owner : owners) {
            claim(owner);
        }
        return TradeResult.ok(tradeId, trade.revision());
    }

    /** 收件箱是否已含该交易 + 该资产的待投递条目（幂等防重入） */
    private boolean hasInboxEntry(PlayerTrade trade, TradeAsset asset) {
        UUID tradeUuid = trade.tradeId().value();
        for (InboxEntry e : repo.inboxOf(trade.leftPlayerId())) {
            if (e.tradeId().equals(tradeUuid) && e.asset().assetId().equals(asset.assetId())) {
                return true;
            }
        }
        for (InboxEntry e : repo.inboxOf(trade.rightPlayerId())) {
            if (e.tradeId().equals(tradeUuid) && e.asset().assetId().equals(asset.assetId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public TradeResult commit(TradeId tradeId) {
        threadChecker.check();
        Optional<PlayerTrade> opt = repo.getTrade(tradeId);
        if (opt.isEmpty()) {
            return TradeResult.fail(tradeId, TradeError.TRADE_NOT_FOUND);
        }
        PlayerTrade trade = opt.get();
        long now = now();
        if (trade.status() != TradeStatus.LOCKED) {
            return TradeResult.fail(tradeId, TradeError.INVALID_STATE);
        }
        if (trade.lockRemainingMillis(now) > 0) {
            return TradeResult.fail(tradeId, TradeError.INVALID_STATE);
        }
        CommitPrep prep = revalidateLocked(trade, now);
        if (prep.error() != TradeError.NONE) {
            return TradeResult.fail(tradeId, prep.error());
        }
        trade.beginCommit(now);
        repo.updateTrade(tradeId, t -> trade);   // COMMITTING 提交意图落盘
        return finalizeCommit(trade, prep.reservation(), prep.quote(), now);
    }

    /**
     * 崩溃恢复（Task 7）：按持久化状态推进非终态交易。
     * <ul>
     *   <li>COMMITTING：崩溃在提交意图落盘后，幂等完成所有权切换（不重复入收件箱）；</li>
     *   <li>COMMITTED/DELIVERING：崩溃在交付前/中，重试交付，不回滚成交；</li>
     *   <li>CANCELLING：崩溃在取消迁移中，幂等完成取消并归还资产；</li>
     *   <li>LOCKED：按持久化 deadline 恢复；未到期保持锁定，到期后重新校验在线/quote 后提交或退回 OPEN；</li>
     *   <li>INVITED/OPEN：过期自动取消并归还资产；未过期保持。</li>
     * </ul>
     */
    @Override
    public TradeResult recover(TradeId tradeId) {
        threadChecker.check();
        Optional<PlayerTrade> opt = repo.getTrade(tradeId);
        if (opt.isEmpty()) {
            return TradeResult.fail(tradeId, TradeError.TRADE_NOT_FOUND);
        }
        PlayerTrade trade = opt.get();
        long now = now();
        switch (trade.status()) {
            case COMMITTING -> {
                TradeFeeQuote q = trade.feeQuote();
                if (q == null) {
                    // 提交意图缺少手续费 quote：无法安全自动结算，交人工处理
                    trade.failRequiresAdmin(TradeError.FEE_QUOTE_INVALID, "missing fee quote", now);
                    repo.updateTrade(tradeId, t -> trade);
                    return TradeResult.fail(tradeId, TradeError.FEE_QUOTE_INVALID);
                }
                FeeReservation reservation = feePolicy.reserve(q, trade);
                if (!reservation.ok()) {
                    // 提交意图已落盘但手续费预留失败（策略已自回滚部分扣费）：交人工处理
                    trade.failRequiresAdmin(reservation.error(), "fee reserve failed", now);
                    repo.updateTrade(tradeId, t -> trade);
                    return TradeResult.fail(tradeId, reservation.error());
                }
                return finalizeCommit(trade, reservation, q, now);
            }
            case COMMITTED, DELIVERING -> {
                // 崩溃在交付前/中：重试交付，不回滚成交
                if (trade.status() == TradeStatus.COMMITTED) {
                    trade.markDelivering(now);
                    repo.updateTrade(tradeId, t -> trade);
                }
                claim(trade.leftPlayerId());
                claim(trade.rightPlayerId());
                return TradeResult.ok(tradeId, trade.revision());
            }
            case CANCELLING -> {
                return cancelInternal(trade, now);
            }
            case LOCKED -> {
                if (trade.lockRemainingMillis(now) > 0) {
                    return TradeResult.ok(tradeId, trade.revision()); // 期限未到：保持锁定
                }
                CommitPrep prep = revalidateLocked(trade, now);
                if (prep.error() != TradeError.NONE) {
                    return TradeResult.fail(tradeId, prep.error());
                }
                trade.beginCommit(now);
                repo.updateTrade(tradeId, t -> trade);
                return finalizeCommit(trade, prep.reservation(), prep.quote(), now);
            }
            case INVITED, OPEN -> {
                if (!trade.expired(now)) {
                    return TradeResult.ok(tradeId, trade.revision());
                }
                return cancelInternal(trade, now);
            }
            default -> {
                return TradeResult.fail(tradeId, TradeError.INVALID_STATE);
            }
        }
    }

    /** LOCKED 到期后的重新校验：双方在线 + quote 未失效；失败回 OPEN 并持久化 */
    private CommitPrep revalidateLocked(PlayerTrade trade, long now) {
        TradeId tradeId = trade.tradeId();
        if (!resolver.isOnline(trade.leftPlayerId()) || !resolver.isOnline(trade.rightPlayerId())) {
            trade.unlockToOpen(now);
            repo.updateTrade(tradeId, t -> trade);
            return CommitPrep.fail(TradeError.TARGET_OFFLINE);
        }
        TradeFeeQuote quote = trade.feeQuote();
        if (quote == null || !quote.validFor(tradeId.value(), trade.revision(), now)) {
            trade.unlockToOpen(now);
            repo.updateTrade(tradeId, t -> trade);
            return CommitPrep.fail(TradeError.FEE_QUOTE_INVALID);
        }
        FeeReservation reservation = feePolicy.reserve(quote, trade);
        if (!reservation.ok()) {
            // 手续费预留失败（余额不足等）：策略已自回滚部分扣费，退回 OPEN 让玩家改价
            trade.unlockToOpen(now);
            repo.updateTrade(tradeId, t -> trade);
            return CommitPrep.fail(reservation.error());
        }
        return CommitPrep.ok(reservation, quote);
    }

    /**
     * 从 COMMITTING 继续完成提交（计划 4.2 原子提交点）：
     * 幂等迁移 offer 资产到对手收件箱批次（跳过已存在，防崩溃重入重复）、
     * 应用手续费、写回执、进入 DELIVERING 并尝试交付。
     */
    private TradeResult finalizeCommit(PlayerTrade trade, FeeReservation reservation,
                                       TradeFeeQuote quote, long now) {
        TradeId tradeId = trade.tradeId();
        for (TradeAsset asset : trade.leftOffer().allAssets()) {
            if (!hasInboxEntry(trade, asset)) {
                inbox(tradeId, asset, trade.rightPlayerId(), trade.rightPreference(), trade.revision(), now);
            }
        }
        for (TradeAsset asset : trade.rightOffer().allAssets()) {
            if (!hasInboxEntry(trade, asset)) {
                inbox(tradeId, asset, trade.leftPlayerId(), trade.leftPreference(), trade.revision(), now);
            }
        }
        // 应用手续费（NoFeePolicy 恒成功）；失败不可回滚所有权，进入人工处理
        FeeApplyResult applied = feePolicy.apply(reservation);
        if (!applied.success()) {
            trade.failRequiresAdmin(applied.error(), "fee apply failed", now);
            repo.updateTrade(tradeId, t -> trade);
            return TradeResult.fail(tradeId, applied.error());
        }
        trade.markCommitted(now);
        repo.addReceipt(new TradeReceipt(
                tradeId.value(), trade.revision(), Instant.ofEpochMilli(now),
                quote, quote.leftPkmFee(), quote.rightPkmFee(), List.of()));
        repo.updateTrade(tradeId, t -> trade);
        // 交付阶段：进入 DELIVERING 并尝试交付双方收件箱
        trade.markDelivering(now);
        repo.updateTrade(tradeId, t -> trade);
        claim(trade.leftPlayerId());
        claim(trade.rightPlayerId());
        return TradeResult.ok(tradeId, trade.revision());
    }

    /** LOCKED 到期重新校验的结果 */
    private record CommitPrep(TradeError error, FeeReservation reservation, TradeFeeQuote quote) {
        static CommitPrep ok(FeeReservation reservation, TradeFeeQuote quote) {
            return new CommitPrep(TradeError.NONE, reservation, quote);
        }

        static CommitPrep fail(TradeError error) {
            return new CommitPrep(error, null, null);
        }
    }

    @Override
    public TradeResult claim(UUID playerId) {
        threadChecker.check();
        long now = now();
        for (InboxEntry entry : repo.inboxOf(playerId)) {
            if (entry.state() == InboxEntry.InboxState.DELIVERED) {
                continue;
            }
            deliverEntry(entry, playerId, now);
        }
        // 推进该玩家参与的 DELIVERING 交易：双方批次全部交付 -> COMPLETED
        List<PlayerTrade> due = new ArrayList<>();
        for (PlayerTrade t : repo.activeTrades()) {
            if (t.isParticipant(playerId) && t.status() == TradeStatus.DELIVERING) {
                due.add(t);
            }
        }
        for (PlayerTrade t : due) {
            if (allDelivered(t.tradeId(), t.leftPlayerId()) && allDelivered(t.tradeId(), t.rightPlayerId())) {
                t.markCompleted(now);
                repo.updateTrade(t.tradeId(), x -> t);
                repo.removeTrade(t.tradeId());
            }
        }
        return TradeResult.ok(null, 0);
    }

    @Override
    public Optional<TradeSnapshot> snapshot(UUID playerId) {
        Optional<PlayerTrade> opt = repo.findTradeOf(playerId);
        return opt.map(trade -> {
            TradeSide side = TradeSide.of(playerId, trade);
            return new TradeSnapshot(
                    trade.tradeId(),
                    trade.status(),
                    trade.revision(),
                    playerId,
                    trade.counterpartOf(playerId),
                    trade.offerOf(side),
                    trade.offerOf(side.opposite()),
                    trade.confirmed(side),
                    trade.confirmed(side.opposite()),
                    trade.expiresAtEpochMillis(),
                    trade.lockDeadlineEpochMillis(),
                    trade.preferenceOf(side),
                    trade.feeQuote());
        });
    }

    // ------------------------------------------------------------------ 内部

    /** 目录能力（Task 11：委托 TradeCapabilityService 全矩阵计算） */
    private TradeCapability capabilityOf(UUID viewerId, UUID otherId) {
        return capabilityService.capabilityOf(viewerId, otherId);
    }

    @Override
    public TradeDirectoryPage directory(UUID viewerId, String query, int page, int pageSize) {
        threadChecker.check();
        String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        List<TradeDirectoryPage.DirectoryEntry> matched = new ArrayList<>();
        for (UUID id : resolver.onlinePlayers()) {
            if (id.equals(viewerId)) {
                continue; // 目录不展示自己（客户端用固定自身槽位）
            }
            String name = resolver.displayName(id);
            if (!needle.isEmpty()
                    && !name.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    && !id.toString().contains(needle)) {
                continue;
            }
            matched.add(new TradeDirectoryPage.DirectoryEntry(id, name, capabilityOf(viewerId, id)));
        }
        matched.sort(Comparator.comparing(TradeDirectoryPage.DirectoryEntry::displayName));
        int total = matched.size();
        int from = Math.min(page * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<TradeDirectoryPage.DirectoryEntry> slice = from < to
                ? new ArrayList<>(matched.subList(from, to))
                : List.of();
        return new TradeDirectoryPage(slice, total, page, pageSize);
    }

    @Override
    public Outcome<TradeAssetPage> ownAssets(UUID viewerId, UUID tradeId, long revision,
                                             AssetPageKind kind, int page, int pageSize) {
        threadChecker.check();
        Optional<PlayerTrade> opt = repo.getTrade(new TradeId(tradeId));
        if (opt.isEmpty()) {
            return Outcome.fail(TradeError.TRADE_NOT_FOUND);
        }
        PlayerTrade trade = opt.get();
        if (!trade.isParticipant(viewerId)) {
            return Outcome.fail(TradeError.NOT_PARTICIPANT);
        }
        TradeError revError = TradeValidator.validateRevision(trade, revision);
        if (revError != TradeError.NONE) {
            return Outcome.fail(revError);
        }
        long assetRevision = trade.revision();
        return switch (kind) {
            case ITEMS -> itemPage(viewerId, assetRevision, page, pageSize);
            case PKM -> pkmPage(viewerId, assetRevision);
            case PARTY -> pokemonPage(viewerId, assetRevision, AssetPageKind.PARTY, page, pageSize);
            case PC -> pokemonPage(viewerId, assetRevision, AssetPageKind.PC, page, pageSize);
        };
    }

    /** 背包物品页：遍历槽位取非空物品（不含 NBT 摘要） */
    private Outcome<TradeAssetPage> itemPage(UUID playerId, long assetRevision, int page, int pageSize) {
        PlayerInventoryStore inv = resolver.inventory(playerId);
        List<TradeAssetPage.TradeAssetEntry> entries = new ArrayList<>();
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemSnapshot snap = inv.get(slot);
            if (snap.isEmpty()) {
                continue;
            }
            entries.add(new TradeAssetPage.ItemEntry(
                    UUID.nameUUIDFromBytes(("item:" + playerId + ":" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    snap.itemId(), snap.count(), slot));
        }
        return slicePage(AssetPageKind.ITEMS, assetRevision, page, pageSize, entries);
    }

    /** PKM 页：钱包当前可报价余额（单条目） */
    private Outcome<TradeAssetPage> pkmPage(UUID playerId, long assetRevision) {
        long balance = resolver.wallet().find(playerId).map(WalletAccount::balance).orElse(0L);
        return Outcome.ok(new TradeAssetPage(
                AssetPageKind.PKM, assetRevision, 1, 0, 1,
                List.of(new TradeAssetPage.PkmEntry(balance))));
    }

    /**
     * 宝可梦页：PARTY 遍历队伍；PC 以页码为箱号（单箱一页，计划 5.2）。
     * 摘要不含招式/个体值/努力值/原训练家。
     */
    private Outcome<TradeAssetPage> pokemonPage(UUID playerId, long assetRevision, AssetPageKind kind,
                                                int page, int pageSize) {
        boolean party = kind == AssetPageKind.PARTY;
        PokemonStoragePort port = resolver.pokemonStorage(playerId);
        int box = party ? -1 : page; // PC 页：页码即箱号
        int capacity = party ? port.partyCapacity() : port.boxCapacity(box);
        List<TradeAssetPage.TradeAssetEntry> entries = new ArrayList<>();
        for (int slot = 0; slot < capacity; slot++) {
            PokemonLocation loc = party ? PokemonLocation.party(slot) : PokemonLocation.pc(box, slot);
            Optional<StoredPokemon> opt = port.at(loc);
            if (opt.isEmpty()) {
                continue;
            }
            StoredPokemon mon = opt.get();
            entries.add(new TradeAssetPage.PokemonEntry(
                    UUID.nameUUIDFromBytes((playerId + ":" + loc).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    mon.pokemonId(),
                    PokemonSummaryReader.species(mon.nbt()),
                    PokemonSummaryReader.form(mon.nbt()),
                    PokemonSummaryReader.level(mon.nbt()),
                    PokemonSummaryReader.shiny(mon.nbt()),
                    PokemonSummaryReader.nickname(mon.nbt()),
                    party ? "party" : "pc",
                    box,
                    slot));
        }
        if (party) {
            return slicePage(kind, assetRevision, page, pageSize, entries);
        }
        // PC：整箱一页，页码即箱号
        return Outcome.ok(new TradeAssetPage(kind, assetRevision, entries.size(), box,
                Math.max(1, entries.size()), entries));
    }

    /** 通用分页：total 为条目总数，page/pageSize 截取 */
    private Outcome<TradeAssetPage> slicePage(AssetPageKind kind, long assetRevision,
                                              int page, int pageSize,
                                              List<TradeAssetPage.TradeAssetEntry> entries) {
        int total = entries.size();
        int from = Math.min(page * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<TradeAssetPage.TradeAssetEntry> slice = from < to
                ? new ArrayList<>(entries.subList(from, to))
                : List.of();
        return Outcome.ok(new TradeAssetPage(kind, assetRevision, total, page, pageSize, slice));
    }

    /** 锁定交易并执行动作；动作失败（返回 fail）不持久化状态机变更 */
    private TradeResult withTrade(UUID playerId, TradeId tradeId,
                                  Function<PlayerTrade, TradeResult> action) {
        Optional<PlayerTrade> opt = repo.getTrade(tradeId);
        if (opt.isEmpty()) {
            return TradeResult.fail(tradeId, TradeError.TRADE_NOT_FOUND);
        }
        PlayerTrade trade = opt.get();
        if (!trade.isParticipant(playerId)) {
            return TradeResult.fail(tradeId, TradeError.NOT_PARTICIPANT);
        }
        try {
            TradeResult result = action.apply(trade);
            if (result.success()) {
                repo.updateTrade(tradeId, t -> trade);
            }
            return result;
        } catch (IllegalArgumentException ex) {
            // 状态机非法迁移等：不持久化，返回稳定错误
            return TradeResult.fail(tradeId, TradeError.INVALID_STATE);
        }
    }

    /** 报价操作公共前置校验；NONE 表示通过 */
    private TradeError preflight(PlayerTrade trade, long expectedRevision) {
        TradeError e = TradeValidator.validateRevision(trade, expectedRevision);
        if (e != TradeError.NONE) {
            return e;
        }
        e = TradeValidator.validateOpen(trade);
        if (e != TradeError.NONE) {
            return e;
        }
        return TradeValidator.validateNotExpired(trade, now());
    }

    /** 资产归还本人：交付优先，交付不下进入收件箱（绝不丢失） */
    private void returnToOwner(TradeId tradeId, TradeAsset asset, long revision, long now) {
        UUID owner = asset.originalOwner();
        DeliveryPreference pref = repo.getPreference(owner);
        switch (asset) {
            case ItemAsset ia -> {
                DeliveryResult r = deliverItem(owner, ia, pref.itemDestination());
                if (!r.allDelivered()) {
                    inbox(tradeId, asset, owner, pref, revision, now);
                }
            }
            case PkmAsset pa -> {
                String opId = tradeId.keyPrefix() + ":refund:" + pa.assetId();
                Outcome<Void> refunded = pkmEscrow.refund(
                        resolver.wallet(), repo, pa, tradeId.value(), opId, now);
                if (!refunded.ok()) {
                    inbox(tradeId, asset, owner, pref, revision, now);
                }
            }
            case PokemonAsset ka -> {
                DeliveryResult r = pokemonEscrow.deliver(
                        resolver.pokemonStorage(owner), ka, pref.pokemonDestination());
                if (!r.allDelivered()) {
                    inbox(tradeId, asset, owner, pref, revision, now);
                }
            }
        }
    }

    /** 写入收件箱待交付条目 */
    private void inbox(TradeId tradeId, TradeAsset asset, UUID recipientId,
                       DeliveryPreference preference, long revision, long now) {
        repo.addInboxEntry(InboxEntry.pending(
                tradeId.value(), recipientId, asset, preference, revision, now));
    }

    /** 交付单条收件箱条目；成功 -> DELIVERED，失败 -> FAILED（保留可重试） */
    private void deliverEntry(InboxEntry entry, UUID recipientId, long now) {
        TradeAsset asset = entry.asset();
        switch (asset) {
            case ItemAsset ia -> {
                DeliveryResult r = deliverItem(
                        recipientId, ia, entry.preference().itemDestination());
                repo.updateInboxEntry(entry.entryId(), e -> e.withState(
                        r.allDelivered() ? InboxEntry.InboxState.DELIVERED : InboxEntry.InboxState.FAILED));
            }
            case PkmAsset pa -> {
                String opId = entry.tradeId() + ":settle:" + pa.assetId();
                Outcome<Void> settled = pkmEscrow.settle(
                        resolver.wallet(), repo, pa, recipientId, entry.tradeId(), opId, now);
                repo.updateInboxEntry(entry.entryId(), e -> e.withState(
                        settled.ok() ? InboxEntry.InboxState.DELIVERED : InboxEntry.InboxState.FAILED));
            }
            case PokemonAsset ka -> {
                DeliveryResult r = pokemonEscrow.deliver(
                        resolver.pokemonStorage(recipientId), ka, entry.preference().pokemonDestination());
                repo.updateInboxEntry(entry.entryId(), e -> e.withState(
                        r.allDelivered() ? InboxEntry.InboxState.DELIVERED : InboxEntry.InboxState.FAILED));
            }
        }
    }

    /** 按物品交付偏好执行交付：末影箱作为个人容器，不可用时降级到收件箱。 */
    private DeliveryResult deliverItem(UUID recipientId, ItemAsset ia,
                                       DeliveryPreference.ItemDestination destination) {
        if (destination == DeliveryPreference.ItemDestination.ENDER_CHEST) {
            PlayerInventoryStore ender = resolver.enderChest(recipientId);
            if (ender != null) {
                return itemEscrow.deliver(ender, ia, DeliveryPreference.ItemDestination.INVENTORY);
            }
            return new DeliveryResult(0, ItemEscrowGateway.assetCount(ia));
        }
        return itemEscrow.deliver(resolver.inventory(recipientId), ia, destination);
    }

    /** 该玩家在指定交易的所有收件箱条目是否均已交付 */
    private boolean allDelivered(TradeId tradeId, UUID playerId) {
        return repo.inboxOf(playerId).stream()
                .filter(e -> e.tradeId().equals(tradeId.value()))
                .allMatch(e -> e.state() == InboxEntry.InboxState.DELIVERED);
    }
}
