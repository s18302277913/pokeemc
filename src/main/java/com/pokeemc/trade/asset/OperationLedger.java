package com.pokeemc.trade.asset;

import com.pokeemc.trade.persistence.OperationEntry;

import java.util.Optional;

/**
 * 资金操作 ledger 窄接口（Task 4）：{@link PkmEscrowGateway} 依赖此抽象实现
 * 预写日志（write-ahead），避免与 {@code TradeSavedData} 直接耦合，JVM 可单测。
 */
public interface OperationLedger {

    /** 按稳定 operation id 查询条目 */
    Optional<OperationEntry> get(String operationId);

    /** 写入新条目（初始 PENDING） */
    void record(OperationEntry entry);

    /** 更新条目状态（APPLIED / ROLLED_BACK） */
    void update(OperationEntry entry);
}
