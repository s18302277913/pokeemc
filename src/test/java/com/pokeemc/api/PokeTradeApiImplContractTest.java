package com.pokeemc.api;

import com.poketrade.api.TradeItemId;
import com.poketrade.api.TradeResult;
import com.poketrade.api.TradeService;
import com.poketrade.api.testkit.TradeServiceContract;

import java.util.Optional;
import java.util.UUID;

/**
 * 复用 testkit 契约测试验证根模组实现，不启动完整游戏：
 * 已知物品固定为 minecraft:diamond（单价 8192），固定玩家购买返回 SUCCESS。
 */
class PokeTradeApiImplContractTest extends TradeServiceContract {
    private static final TradeItemId DIAMOND = TradeItemId.parse("minecraft:diamond");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Override
    protected TradeService createService() {
        return new PokeTradeApiImpl(
                itemId -> DIAMOND.toString().equals(itemId) ? Optional.of(8_192L) : Optional.empty(),
                playerId -> PLAYER.equals(playerId) ? Optional.of(playerId) : Optional.empty(),
                (playerId, quote) -> DIAMOND.equals(quote.itemId())
                        ? TradeResult.SUCCESS
                        : TradeResult.UNKNOWN_ITEM
        );
    }

    @Override
    protected TradeItemId knownItem() {
        return DIAMOND;
    }

    @Override
    protected UUID fundedPlayer() {
        return PLAYER;
    }
}
