package com.pokeemc.trade.service;

import com.pokeemc.trade.model.TradeCapability;

import java.util.UUID;

/**
 * 玩家交易能力服务（Task 11）：把"该玩家现在能不能被邀请交易"聚合为
 * {@link TradeCapability} 枚举，供交易目录展示与邀请前预检复用。
 * <p>
 * 能力矩阵按优先级短路：SELF → OFFLINE → DISABLED_BY_SERVER →
 * DISABLED_BY_PLAYER → RATE_LIMITED → RECOVERY_REQUIRED → INVITE_PENDING →
 * BUSY → (PKM_UNSUPPORTED | AVAILABLE)。PKM 后端不可用时总体仍可邀请
 * （物品/宝可梦交易安全），仅标记 PKM_UNSUPPORTED 供资产类型能力单独提示。
 */
public interface TradeCapabilityService {

    /** viewer 视角下 other 的能力状态 */
    TradeCapability capabilityOf(UUID viewerId, UUID otherId);

    /** 默认装配：服务器/玩家开关全开、不限流；恢复阻塞按收件箱失败条目计算 */
    static TradeCapabilityService basic(PlayerStorageResolver resolver, TradeRepository repo) {
        return new TradeCapabilityServiceImpl(resolver, repo, TradeCapabilitySettings.DEFAULTS);
    }
}
