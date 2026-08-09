package com.poketrade.api.market;

import com.poketrade.api.TradeItemId;
import com.poketrade.api.TradeResult;
import com.poketrade.api.price.PriceCatalog;
import com.poketrade.api.price.PriceQuote;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 交易所市场服务（阶段 2 公共契约）。
 *
 * <p>契约约束：</p>
 * <ul>
 *   <li>所有金额以 {@code long} 计算，使用 {@link Math#multiplyExact} 精确乘法，溢出即失败；</li>
 *   <li>服务端在每次 {@code buy}/{@code buyBatch}/{@code sellFromInventory} 执行时<b>重新报价</b>
 *       （内部走 {@link #quote}），绝不信任客户端价格；</li>
 *   <li>买价与卖价独立，禁止同一数值双向交易；</li>
 *   <li>批量买入 {@code buyBatch} 全成或全败（先模拟空间与余额，任一不满足则整体失败）。</li>
 * </ul>
 */
public interface MarketTradeService {

    /** 当前价格目录（服务端构建；数据包重载后更新）。空 Optional 表示目录尚未构建（服务端启动或数据包重载前）。 */
    Optional<PriceCatalog> catalog();

    /** 按物品 id 报价（服务端权威）。 */
    Optional<PriceQuote> quote(TradeItemId itemId);

    /** 买入单件物品（服务端重新报价、扣钱包、产出物品到背包）。{@code count} 需 &gt; 0，且受物品堆叠上限约束。 */
    TradeResult buy(UUID playerId, TradeItemId itemId, int count);

    /** 批量买入（购物车结算）。同一 playerId+operationId 的成功结果幂等缓存，重复调用返回首次结果；失败结果不缓存、可重试。 */
    TradeResult buyBatch(UUID playerId, List<CartLine> lines, String operationId);

    /** 从玩家背包出售物品（扣物品、钱包入账）。同一 playerId+operationId 的成功结果幂等缓存，重复调用返回首次结果；失败结果不缓存、可重试。 */
    TradeResult sellFromInventory(UUID playerId, List<CartLine> lines, String operationId);

    /** 购物车行：物品 + 数量。 */
    record CartLine(TradeItemId itemId, int count) {
        public CartLine {
            Objects.requireNonNull(itemId, "itemId");
            if (count <= 0) {
                throw new IllegalArgumentException("count must be > 0");
            }
        }
    }
}
