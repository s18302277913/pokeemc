package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.Outcome;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.TradeId;

import java.util.Optional;
import java.util.UUID;

/**
 * 玩家交易服务（计划 5.3，Task 6）：全部业务入口。
 *
 * <p>为在 JVM 单测驱动，方法签名使用 {@link UUID} 与 {@link PokemonLocator}，
 * 不直接依赖 {@code ServerPlayer}；网络层（Task 8）把玩家解析为 UUID 后调用。
 * 所有写方法第一行断言服务端主线程（{@link ThreadChecker}）。</p>
 *
 * <p>返回 {@link TradeResult} 而非 boolean：携带稳定错误码、tradeId 与最新 revision，
 * 供网络、命令与测试共享。</p>
 */
public interface TradeService {

    /** 创建 1v1 交易邀请：initiator -> target */
    TradeResult invite(UUID initiatorId, UUID targetId);

    /** 接受邀请（仅被邀请方）：INVITED -> OPEN */
    TradeResult accept(UUID playerId, TradeId tradeId, long revision);

    /** 托管物品：从背包槽位移出指定数量加入本人报价 */
    TradeResult offerItem(UUID playerId, TradeId tradeId, long revision, int slot, int count);

    /** 托管 PKM：从钱包借记指定金额加入本人报价 */
    TradeResult offerPkm(UUID playerId, TradeId tradeId, long revision, long amount);

    /** 托管宝可梦：从 Party/PC 指定位置移出加入本人报价 */
    TradeResult offerPokemon(UUID playerId, TradeId tradeId, long revision, PokemonLocator locator);

    /** 从本人报价移除资产（归还本人存储/钱包，失败进收件箱） */
    TradeResult removeAsset(UUID playerId, TradeId tradeId, long revision, UUID assetId);

    /** 修改本人收货偏好：revision + 1、清空确认、持久化全局偏好 */
    TradeResult setDeliveryPreference(UUID playerId, TradeId tradeId, long revision, DeliveryPreference preference);

    /** 确认报价；双方确认同一 revision 后进入 LOCKED 并冻结手续费 quote */
    TradeResult confirm(UUID playerId, TradeId tradeId, long revision);

    /** 取消交易：INVITED/OPEN/LOCKED，全部资产进入原所有者收件箱 */
    TradeResult cancel(UUID playerId, TradeId tradeId, long revision);

    /** 提交锁定期到期的交易：重新校验并完成所有权切换（由调度器调用） */
    TradeResult commit(TradeId tradeId);

    /** 领取收件箱：按冻结偏好交付本人待领取条目，并推进交易到终态 */
    TradeResult claim(UUID playerId);

    /** 玩家当前交易快照（无交易返回 empty） */
    Optional<TradeSnapshot> snapshot(UUID playerId);

    /**
     * 崩溃恢复（Task 7）：按持久化状态推进单个非终态交易
     * （COMMITTING 完成所有权切换、COMMITTED/DELIVERING 重试交付、
     * CANCELLING 完成取消、LOCKED 按 deadline 恢复、过期 INVITED/OPEN 自动取消）。
     */
    TradeResult recover(TradeId tradeId);

    /**
     * 在线玩家目录（Task 8）：只列出当前在线且未隐藏交易入口的玩家，
     * 不含任何资产统计；能力状态为短期提示（Task 11 由 TradeCapabilityService 增强）。
     */
    TradeDirectoryPage directory(UUID viewerId, String query, int page, int pageSize);

    /**
     * 本人资产页（Task 8）：请求者必须是该交易参与者，且只能请求自己的
     * 库存/钱包/队伍/PC；不存在查看对手资产页的协议分支。
     * 校验失败（交易不存在/非参与者/过期 revision）返回稳定错误码。
     */
    Outcome<TradeAssetPage> ownAssets(UUID viewerId, UUID tradeId, long revision,
                                      com.pokeemc.trade.model.AssetPageKind kind, int page, int pageSize);
}
