package com.pokeemc.trade.service;

import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeStatus;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 崩溃恢复与超时（Task 7）：在服务端启动、玩家登录与定时扫描三个时机驱动
 * {@link TradeService#recover} / {@link TradeService#claim} 推进非终态交易。
 *
 * <p>纯调度职责：所有状态机与资产迁移逻辑在 {@link TradeServiceImpl} 内，
 * 本类只负责“何时处理哪些交易”与批处理限流，JVM 测试可覆盖。</p>
 */
public final class TradeRecoveryService {

    /** 每次定时扫描最多处理的过期交易数（防服务器 tick 卡顿） */
    public static final int DEFAULT_BATCH_LIMIT = 100;

    private final TradeService service;
    private final TradeRepository repo;
    private final Clock clock;
    private final ThreadChecker threadChecker;
    private final int batchLimit;

    public TradeRecoveryService(TradeService service, TradeRepository repo,
                                Clock clock, ThreadChecker threadChecker) {
        this(service, repo, clock, threadChecker, DEFAULT_BATCH_LIMIT);
    }

    public TradeRecoveryService(TradeService service, TradeRepository repo,
                                Clock clock, ThreadChecker threadChecker, int batchLimit) {
        this.service = Objects.requireNonNull(service, "service");
        this.repo = Objects.requireNonNull(repo, "repo");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.threadChecker = Objects.requireNonNull(threadChecker, "threadChecker");
        if (batchLimit <= 0) {
            throw new IllegalArgumentException("batchLimit must be positive");
        }
        this.batchLimit = batchLimit;
    }

    /**
     * 服务端启动恢复：对所有活动（非终态）交易执行 {@link TradeService#recover}。
     * 每个崩溃点（COMMITTING/COMMITTED/DELIVERING/CANCELLING/LOCKED/过期）由服务按状态推进。
     */
    public RecoveryReport recoverAll() {
        threadChecker.check();
        List<PlayerTrade> active = repo.activeTrades();
        int recovered = 0;
        int deferred = 0;
        int failed = 0;
        for (PlayerTrade trade : active) {
            TradeResult r = service.recover(trade.tradeId());
            if (r.success()) {
                recovered++;
            } else if (r.error() == com.pokeemc.trade.model.TradeError.INVALID_STATE
                    || r.error() == com.pokeemc.trade.model.TradeError.TRADE_NOT_FOUND) {
                // 终态/未到期/已消失：保持现状，不视为故障
                deferred++;
            } else {
                failed++;
            }
        }
        return new RecoveryReport(active.size(), recovered, deferred, failed);
    }

    /**
     * 玩家登录交付：延后一 tick 调用（由服务端接线层安排），
     * 尝试交付该玩家收件箱中的所有待投递条目。
     */
    public void onLogin(UUID playerId) {
        threadChecker.check();
        service.claim(playerId);
    }

    /**
     * 定时过期扫描：仅处理已过期的 INVITED/OPEN 交易（交给 {@link TradeService#recover}
     * 自动取消并归还资产）。每 tick 最多处理 {@code batchLimit} 个，防卡顿。
     */
    public int sweepExpired() {
        threadChecker.check();
        long now = clock.millis();
        int processed = 0;
        for (PlayerTrade trade : repo.activeTrades()) {
            if (processed >= batchLimit) {
                break;
            }
            TradeStatus status = trade.status();
            if ((status == TradeStatus.INVITED || status == TradeStatus.OPEN) && trade.expired(now)) {
                service.recover(trade.tradeId());
                processed++;
            }
        }
        return processed;
    }

    public TradeService service() {
        return service;
    }

    /** 启动恢复统计 */
    public record RecoveryReport(int total, int recovered, int deferred, int failed) {
    }
}
