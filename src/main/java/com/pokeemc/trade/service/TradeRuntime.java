package com.pokeemc.trade.service;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.UUID;

/**
 * 交易恢复服务的服务端接线门面（Task 7）：
 * 由 {@code PokeEMC} 在服务端启动/登录/tick 事件中调用，
 * 实例由 Task 11 生产装配完成时通过 {@link #install} 注入。
 * 未装配时所有入口安全 no-op（客户端或早期启动阶段）。
 */
public final class TradeRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile TradeRecoveryService recovery;
    private static volatile TradeService service;
    private static volatile PlayerStorageResolver resolver;

    private TradeRuntime() {
    }

    /** 注入生产装配的恢复服务（幂等，重复调用以最后一次为准） */
    public static void install(TradeRecoveryService recoveryService) {
        recovery = recoveryService;
    }

    /** 注入生产装配的交易服务（Task 8 网络 handler 使用；Task 11 完成装配） */
    public static void install(TradeService tradeService) {
        service = tradeService;
    }

    /** 注入生产装配的玩家存储解析器（Task 8 网络 handler 快照投影用；Task 11 完成装配） */
    public static void install(PlayerStorageResolver playerResolver) {
        resolver = playerResolver;
    }

    /** 当前装配的交易服务；未装配返回 null（客户端/早期启动阶段） */
    public static TradeService service() {
        return service;
    }

    /** 玩家当前公开名称（快照投影 / 目录用；未装配返回 UUID 稳定占位） */
    public static String displayName(UUID playerId) {
        PlayerStorageResolver r = resolver;
        return r == null ? "Player" + playerId.toString().substring(0, 8) : r.displayName(playerId);
    }

    /** 服务端启动完成后：恢复所有活动交易 */
    public static void recoverAllOnStartup() {
        TradeRecoveryService r = recovery;
        if (r == null) {
            return;
        }
        try {
            TradeRecoveryService.RecoveryReport report = r.recoverAll();
            if (report.failed() > 0) {
                LOGGER.warn("PokeEMC: trade recovery finished with failures: {}", report);
            } else {
                LOGGER.info("PokeEMC: trade recovery done: {}", report);
            }
        } catch (Exception e) {
            LOGGER.error("PokeEMC: trade recovery crashed", e);
        }
    }

    /** 玩家登录（延后一 tick 调用）：尝试交付收件箱 */
    public static void deliverOnLogin(UUID playerId) {
        TradeRecoveryService r = recovery;
        if (r == null) {
            return;
        }
        try {
            r.onLogin(playerId);
        } catch (Exception e) {
            LOGGER.error("PokeEMC: inbox delivery on login failed for {}", playerId, e);
        }
    }

    /** 服务端 tick：定时过期扫描（内部按批处理限流），返回本次处理数 */
    public static int sweepExpired() {
        TradeRecoveryService r = recovery;
        if (r == null) {
            return 0;
        }
        try {
            return r.sweepExpired();
        } catch (Exception e) {
            LOGGER.error("PokeEMC: trade expiry sweep crashed", e);
            return 0;
        }
    }
}
