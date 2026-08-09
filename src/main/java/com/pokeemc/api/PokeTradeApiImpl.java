package com.pokeemc.api;

import com.pokeemc.exchange.market.TradeMarketService;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.TradeQuote;
import com.poketrade.api.TradeResult;
import com.poketrade.api.TradeService;
import com.poketrade.api.market.MarketTradeService.CartLine;
import com.poketrade.api.price.PriceQuote;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 根模组对 {@link TradeService} 的实现。
 * 核心 {@code quote}/{@code purchase} 只依赖注入的纯 Java 函数，
 * 游戏绑定全部收敛在 {@link #forGame()} 的默认环境中，便于测试替换。
 */
public final class PokeTradeApiImpl implements TradeService {

    @FunctionalInterface
    public interface UnitPriceSource {
        Optional<Long> unitValue(String itemId);
    }

    @FunctionalInterface
    public interface OnlinePlayerSource {
        Optional<UUID> session(UUID playerId);
    }

    @FunctionalInterface
    public interface PurchaseDelegate {
        TradeResult purchase(UUID playerId, TradeQuote quote);
    }

    private final UnitPriceSource prices;
    private final OnlinePlayerSource players;
    private final PurchaseDelegate purchases;

    public PokeTradeApiImpl(UnitPriceSource prices, OnlinePlayerSource players, PurchaseDelegate purchases) {
        this.prices = Objects.requireNonNull(prices, "prices");
        this.players = Objects.requireNonNull(players, "players");
        this.purchases = Objects.requireNonNull(purchases, "purchases");
    }

    /** 生产默认环境：Minecraft 在线玩家 + 交易所市场报价与购买入口 */
    public static PokeTradeApiImpl forGame() {
        return new PokeTradeApiImpl(
                PokeTradeApiImpl::gamePriceOf,
                PokeTradeApiImpl::gamePlayerSession,
                PokeTradeApiImpl::gamePurchase
        );
    }

    @Override
    public Optional<TradeQuote> quote(TradeItemId itemId, int quantity) {
        if (quantity <= 0) {
            return Optional.empty();
        }
        return prices.unitValue(itemId.toString())
                .filter(value -> value > 0)
                .map(value -> TradeQuote.of(itemId, quantity, value));
    }

    @Override
    public TradeResult purchase(UUID playerId, TradeQuote quote) {
        if (players.session(playerId).isEmpty()) {
            return TradeResult.INTERNAL_ERROR;
        }
        return purchases.purchase(playerId, quote);
    }

    private static Optional<Long> gamePriceOf(String itemId) {
        TradeItemId id;
        try {
            id = TradeItemId.parse(itemId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // 与交易所买价同源（官方 buyPrice ×10），保证 quote 与 purchase 实际扣款一致
        return TradeMarketService.forServer().quote(id)
                .filter(PriceQuote::buyAvailable)
                .map(PriceQuote::buyPrice)
                .filter(value -> value > 0);
    }

    private static Optional<UUID> gamePlayerSession(UUID playerId) {
        ServerPlayer player = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(playerId);
        return player != null ? Optional.of(playerId) : Optional.empty();
    }

    private static TradeResult gamePurchase(UUID playerId, TradeQuote quote) {
        ServerPlayer player = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(playerId);
        if (player == null) {
            return TradeResult.INTERNAL_ERROR;
        }
        if (quote.quantity() <= 0) {
            return TradeResult.INVALID_QUANTITY;
        }
        // 直接走交易所市场买入（钱包扣款、物品进主背包），不再依赖玩家当前打开的菜单
        List<CartLine> lines = List.of(new CartLine(quote.itemId(), quote.quantity()));
        return TradeMarketService.forServer().buyBatch(
                playerId, lines, "api-" + UUID.randomUUID());
    }
}
