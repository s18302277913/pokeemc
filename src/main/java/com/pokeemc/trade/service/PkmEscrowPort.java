package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.OperationLedger;
import com.pokeemc.trade.asset.Outcome;
import com.pokeemc.trade.asset.WalletPort;
import com.pokeemc.trade.model.PkmAsset;

import java.util.UUID;

/**
 * PKM 托管端口（Task 6）：TradeService 依赖的托管抽象，测试注入 fake。
 */
public interface PkmEscrowPort {

    Outcome<PkmAsset> escrow(WalletPort port, OperationLedger ledger, UUID tradeId,
                             UUID owner, long amount, String operationId, long now);

    Outcome<Void> settle(WalletPort port, OperationLedger ledger, PkmAsset asset, UUID recipient,
                         UUID tradeId, String operationId, long now);

    Outcome<Void> refund(WalletPort port, OperationLedger ledger, PkmAsset asset,
                         UUID tradeId, String operationId, long now);
}
