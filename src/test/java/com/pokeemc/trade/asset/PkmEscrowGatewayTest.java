package com.pokeemc.trade.asset;

import com.pokeemc.trade.model.PkmAsset;
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.persistence.OperationEntry;
import com.pokeemc.trade.persistence.OperationEntry.OperationState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4：PKM 托管 gateway 测试（计划 3.3 / Task 4 步骤 2）。
 * 覆盖余额不足、负数/零、超上限、借记失败、取消贷记、成交贷记、
 * 重复 operation 幂等、非幂等后端拒绝、未知借记结果进入人工处理。
 */
class PkmEscrowGatewayTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPIENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TRADE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final long NOW = 1_000_000L;

    // ------------------------------------------------------------------ escrow（预写借记）

    @Test
    void escrowDebitsWalletAndWritesApplied() {
        FakeLedger ledger = new FakeLedger();
        FakeWalletPort port = new FakeWalletPort();
        FakeAccount account = port.add(OWNER, 100);

        var out = PkmEscrowGateway.escrow(port, ledger, TRADE_ID, OWNER, 30, "op-debit", NOW);
        assertTrue(out.ok());
        PkmAsset asset = out.value();
        assertEquals(OWNER, asset.originalOwner());
        assertEquals(30, asset.amount());
        assertTrue(asset.debited());
        assertEquals(70, account.balance);
        assertEquals(OperationState.APPLIED, ledger.get("op-debit").orElseThrow().state());
    }

    @Test
    void insufficientBalanceRejectedWithoutMutation() {
        FakeLedger ledger = new FakeLedger();
        FakeWalletPort port = new FakeWalletPort();
        FakeAccount account = port.add(OWNER, 20);

        var out = PkmEscrowGateway.escrow(port, ledger, TRADE_ID, OWNER, 30, "op-debit", NOW);
        assertFalse(out.ok());
        assertEquals(TradeError.PKM_INSUFFICIENT_BALANCE, out.error());
        assertEquals(20, account.balance);
    }

    @Test
    void nonPositiveAmountRejected() {
        FakeWalletPort port = new FakeWalletPort();
        port.add(OWNER, 100);
        assertEquals(TradeError.PKM_INVALID_AMOUNT,
                PkmEscrowGateway.escrow(port, new FakeLedger(), TRADE_ID, OWNER, 0, "op", NOW).error());
        assertEquals(TradeError.PKM_INVALID_AMOUNT,
                PkmEscrowGateway.escrow(port, new FakeLedger(), TRADE_ID, OWNER, -5, "op", NOW).error());
    }

    @Test
    void amountAboveLimitRejected() {
        FakeWalletPort port = new FakeWalletPort();
        port.add(OWNER, PkmEscrowGateway.MAX_PKM_AMOUNT + 1);
        assertEquals(TradeError.PKM_INVALID_AMOUNT,
                PkmEscrowGateway.escrow(port, new FakeLedger(), TRADE_ID, OWNER,
                        PkmEscrowGateway.MAX_PKM_AMOUNT + 1, "op", NOW).error());
    }

    @Test
    void nonIdempotentBackendRejected() {
        FakeLedger ledger = new FakeLedger();
        FakeWalletPort port = new FakeWalletPort();
        FakeAccount account = port.add(OWNER, 100);
        account.idempotent = false;

        var out = PkmEscrowGateway.escrow(port, ledger, TRADE_ID, OWNER, 30, "op-debit", NOW);
        assertFalse(out.ok());
        assertEquals(TradeError.PKM_ESCROW_UNSUPPORTED, out.error());
        assertEquals(100, account.balance);
    }

    @Test
    void debitFailureRollsBackEntry() {
        FakeLedger ledger = new FakeLedger();
        FakeWalletPort port = new FakeWalletPort();
        FakeAccount account = port.add(OWNER, 100);
        account.failDebit = true;

        var out = PkmEscrowGateway.escrow(port, ledger, TRADE_ID, OWNER, 30, "op-debit", NOW);
        assertFalse(out.ok());
        assertEquals(TradeError.PKM_DEBIT_FAILED, out.error());
        assertEquals(OperationState.ROLLED_BACK, ledger.get("op-debit").orElseThrow().state());
        assertEquals(100, account.balance);
    }

    @Test
    void missingWalletRejected() {
        var out = PkmEscrowGateway.escrow(new FakeWalletPort(), new FakeLedger(),
                TRADE_ID, OWNER, 30, "op-debit", NOW);
        assertFalse(out.ok());
        assertEquals(TradeError.PKM_DEBIT_FAILED, out.error());
    }

    @Test
    void repeatedOperationIsIdempotent() {
        FakeLedger ledger = new FakeLedger();
        FakeWalletPort port = new FakeWalletPort();
        FakeAccount account = port.add(OWNER, 100);

        var first = PkmEscrowGateway.escrow(port, ledger, TRADE_ID, OWNER, 30, "op-debit", NOW);
        assertTrue(first.ok());
        // 第二次同一 operationId：不重复扣款，返回同一资产
        var second = PkmEscrowGateway.escrow(port, ledger, TRADE_ID, OWNER, 30, "op-debit", NOW);
        assertTrue(second.ok());
        assertEquals(first.value().assetId(), second.value().assetId());
        assertEquals(70, account.balance);
    }

    @Test
    void pendingOperationRequiresAdmin() {
        FakeLedger ledger = new FakeLedger();
        // 预先写入 PENDING（模拟崩溃遗留：借记是否已应用未知）
        ledger.record(OperationEntry.record(
                "op-debit", PkmEscrowGateway.OP_DEBIT, TRADE_ID,
                UUID.randomUUID(), OWNER, 30, "escrow-debit", NOW));
        FakeWalletPort port = new FakeWalletPort();
        port.add(OWNER, 100);

        var out = PkmEscrowGateway.escrow(port, ledger, TRADE_ID, OWNER, 30, "op-debit", NOW);
        assertFalse(out.ok());
        assertEquals(TradeError.REQUIRES_ADMIN, out.error());
    }

    @Test
    void rolledBackEntryAllowsRetry() {
        FakeLedger ledger = new FakeLedger();
        ledger.record(OperationEntry.record(
                "op-debit", PkmEscrowGateway.OP_DEBIT, TRADE_ID,
                UUID.randomUUID(), OWNER, 30, "escrow-debit", NOW)
                .withState(OperationState.ROLLED_BACK));
        FakeWalletPort port = new FakeWalletPort();
        FakeAccount account = port.add(OWNER, 100);

        var out = PkmEscrowGateway.escrow(port, ledger, TRADE_ID, OWNER, 30, "op-debit", NOW);
        assertTrue(out.ok());
        assertEquals(70, account.balance);
        assertEquals(OperationState.APPLIED, ledger.get("op-debit").orElseThrow().state());
    }

    // ------------------------------------------------------------------ settle / refund（贷记）

    @Test
    void settleCreditsRecipient() {
        FakeLedger ledger = new FakeLedger();
        FakeWalletPort port = new FakeWalletPort();
        port.add(OWNER, 100);
        FakeAccount recipient = port.add(RECIPIENT, 0);

        PkmAsset asset = escrow(port, ledger, OWNER, 30);
        var out = PkmEscrowGateway.settle(port, ledger, asset, RECIPIENT, TRADE_ID, "op-credit", NOW);
        assertTrue(out.ok());
        assertEquals(30, recipient.balance);
        assertEquals(OperationState.APPLIED, ledger.get("op-credit").orElseThrow().state());
    }

    @Test
    void refundCreditsOriginalOwner() {
        FakeLedger ledger = new FakeLedger();
        FakeWalletPort port = new FakeWalletPort();
        FakeAccount owner = port.add(OWNER, 100);

        PkmAsset asset = escrow(port, ledger, OWNER, 30);
        assertEquals(70, owner.balance);
        var out = PkmEscrowGateway.refund(port, ledger, asset, TRADE_ID, "op-refund", NOW);
        assertTrue(out.ok());
        assertEquals(100, owner.balance);
        assertEquals(OperationState.APPLIED, ledger.get("op-refund").orElseThrow().state());
    }

    @Test
    void repeatedCreditIsIdempotent() {
        FakeLedger ledger = new FakeLedger();
        FakeWalletPort port = new FakeWalletPort();
        port.add(OWNER, 100);
        FakeAccount recipient = port.add(RECIPIENT, 0);

        PkmAsset asset = escrow(port, ledger, OWNER, 30);
        assertTrue(PkmEscrowGateway.settle(port, ledger, asset, RECIPIENT, TRADE_ID, "op-credit", NOW).ok());
        assertTrue(PkmEscrowGateway.settle(port, ledger, asset, RECIPIENT, TRADE_ID, "op-credit", NOW).ok());
        assertEquals(30, recipient.balance); // 只入账一次
    }

    @Test
    void undebitedAssetCannotBeCredited() {
        PkmAsset asset = new PkmAsset(UUID.randomUUID(), OWNER, 30, "op-debit", false);
        FakeWalletPort port = new FakeWalletPort();
        port.add(RECIPIENT, 0);
        var out = PkmEscrowGateway.settle(port, new FakeLedger(), asset, RECIPIENT, TRADE_ID, "op-credit", NOW);
        assertFalse(out.ok());
        assertEquals(TradeError.PKM_DEBIT_FAILED, out.error());
    }

    @Test
    void creditRejectsNonIdempotentBackend() {
        FakeLedger ledger = new FakeLedger();
        FakeWalletPort port = new FakeWalletPort();
        port.add(OWNER, 100);
        FakeAccount recipient = port.add(RECIPIENT, 0);
        recipient.idempotent = false;

        PkmAsset asset = escrow(port, ledger, OWNER, 30);
        var out = PkmEscrowGateway.settle(port, ledger, asset, RECIPIENT, TRADE_ID, "op-credit", NOW);
        assertFalse(out.ok());
        assertEquals(TradeError.PKM_ESCROW_UNSUPPORTED, out.error());
        assertEquals(0, recipient.balance);
    }

    @Test
    void creditFailureRollsBackEntry() {
        FakeLedger ledger = new FakeLedger();
        FakeWalletPort port = new FakeWalletPort();
        port.add(OWNER, 100);
        FakeAccount recipient = port.add(RECIPIENT, 0);
        recipient.failCredit = true;

        PkmAsset asset = escrow(port, ledger, OWNER, 30);
        var out = PkmEscrowGateway.settle(port, ledger, asset, RECIPIENT, TRADE_ID, "op-credit", NOW);
        assertFalse(out.ok());
        assertEquals(TradeError.PKM_DEBIT_FAILED, out.error());
        assertEquals(OperationState.ROLLED_BACK, ledger.get("op-credit").orElseThrow().state());
        assertEquals(0, recipient.balance);
    }

    // ------------------------------------------------------------------ helpers

    private static PkmAsset escrow(FakeWalletPort port, FakeLedger ledger, UUID owner, long amount) {
        return PkmEscrowGateway.escrow(port, ledger, TRADE_ID, owner, amount, "op-debit-" + owner, NOW)
                .value();
    }

    // ------------------------------------------------------------------ fakes

    private static final class FakeLedger implements OperationLedger {
        private final Map<String, OperationEntry> entries = new HashMap<>();

        @Override
        public Optional<OperationEntry> get(String operationId) {
            return Optional.ofNullable(entries.get(operationId));
        }

        @Override
        public void record(OperationEntry entry) {
            entries.put(entry.operationId(), entry);
        }

        @Override
        public void update(OperationEntry entry) {
            entries.put(entry.operationId(), entry);
        }
    }

    private static final class FakeWalletPort implements WalletPort {
        private final Map<UUID, FakeAccount> accounts = new HashMap<>();

        FakeAccount add(UUID playerId, long balance) {
            FakeAccount account = new FakeAccount(balance);
            accounts.put(playerId, account);
            return account;
        }

        @Override
        public Optional<WalletAccount> find(UUID playerId) {
            return Optional.ofNullable(accounts.get(playerId));
        }
    }

    private static final class FakeAccount implements WalletAccount {
        long balance;
        boolean idempotent = true;
        boolean failDebit;
        boolean failCredit;

        FakeAccount(long balance) {
            this.balance = balance;
        }

        @Override
        public long balance() {
            return balance;
        }

        @Override
        public boolean debit(long amount) {
            if (failDebit) {
                return false;
            }
            if (balance < amount) {
                return false;
            }
            balance -= amount;
            return true;
        }

        @Override
        public boolean credit(long amount) {
            if (failCredit) {
                return false;
            }
            balance += amount;
            return true;
        }

        @Override
        public boolean supportsIdempotency() {
            return idempotent;
        }
    }
}
