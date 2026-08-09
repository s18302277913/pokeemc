package com.pokeemc.trade.service;

import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.TradeCapability;
import com.pokeemc.trade.model.TradeStatus;
import com.pokeemc.trade.persistence.InboxEntry;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 能力状态计算实现（Task 11）。
 * <p>
 * 优先级（满足任一即短路返回）：SELF → OFFLINE → DISABLED_BY_SERVER →
 * DISABLED_BY_PLAYER → RATE_LIMITED → RECOVERY_REQUIRED → INVITE_PENDING →
 * BUSY → (PKM_UNSUPPORTED | AVAILABLE)。
 */
public final class TradeCapabilityServiceImpl implements TradeCapabilityService {

    private final PlayerStorageResolver resolver;
    private final TradeRepository repo;
    private final TradeCapabilitySettings settings;

    public TradeCapabilityServiceImpl(PlayerStorageResolver resolver, TradeRepository repo,
                                      TradeCapabilitySettings settings) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.repo = Objects.requireNonNull(repo, "repo");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public TradeCapability capabilityOf(UUID viewerId, UUID otherId) {
        if (otherId.equals(viewerId)) {
            return TradeCapability.SELF;
        }
        if (!resolver.isOnline(otherId)) {
            return TradeCapability.OFFLINE;
        }
        if (!settings.tradingEnabled()) {
            return TradeCapability.DISABLED_BY_SERVER;
        }
        if (!settings.playerTradingEnabled(otherId)) {
            return TradeCapability.DISABLED_BY_PLAYER;
        }
        if (settings.isRateLimited(viewerId)) {
            return TradeCapability.RATE_LIMITED;
        }
        if (recoveryPending(otherId)) {
            return TradeCapability.RECOVERY_REQUIRED;
        }
        Optional<PlayerTrade> mine = repo.findTradeOf(viewerId);
        if (mine.isPresent()) {
            PlayerTrade trade = mine.get();
            if (trade.status() == TradeStatus.INVITED && otherId.equals(trade.counterpartOf(viewerId))) {
                return TradeCapability.INVITE_PENDING;
            }
        }
        if (repo.findTradeOf(otherId).isPresent()) {
            return TradeCapability.BUSY;
        }
        // PKM 后端能力：仅标记，不阻塞邀请（invitable 允许 PKM_UNSUPPORTED）
        boolean pkmSupported = resolver.wallet().find(otherId)
                .map(account -> account.supportsIdempotency())
                .orElse(false);
        return pkmSupported ? TradeCapability.AVAILABLE : TradeCapability.PKM_UNSUPPORTED;
    }

    /** 玩家有收件箱 FAILED 条目（交付失败未恢复）时需要先处理 */
    private boolean recoveryPending(UUID playerId) {
        for (InboxEntry entry : repo.inboxOf(playerId)) {
            if (entry.state() == InboxEntry.InboxState.FAILED) {
                return true;
            }
        }
        return false;
    }
}
