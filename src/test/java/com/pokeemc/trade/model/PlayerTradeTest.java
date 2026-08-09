package com.pokeemc.trade.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 1：纯领域状态机测试（不依赖 Minecraft/Pixelmon）。
 * 覆盖计划 4.1 的正常流程、4.3 的 revision 竞态控制与终态不可变。
 */
class PlayerTradeTest {

    private static final UUID LEFT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RIGHT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final long NOW = 1_000_000L;

    private static PlayerTrade invitedTrade() {
        return PlayerTrade.invited(TradeId.random(), LEFT, RIGHT, NOW);
    }

    private static TradeOffer offerWithItem() {
        return TradeOffer.empty().withAdded(new ItemAsset(UUID.randomUUID(), LEFT, new net.minecraft.nbt.CompoundTag()));
    }

    // ------------------------------------------------------------------
    // 邀请 -> OPEN
    // ------------------------------------------------------------------

    @Test
    void acceptMovesInvitedToOpen() {
        PlayerTrade t = invitedTrade();
        assertEquals(TradeStatus.INVITED, t.status());
        t.accept(NOW + 1000);
        assertEquals(TradeStatus.OPEN, t.status());
        assertEquals(1, t.revision());
        // 接受后超时从邀请 30s 重置为默认 5 分钟
        assertEquals(NOW + 1000 + PlayerTrade.DEFAULT_TIMEOUT_MILLIS, t.expiresAtEpochMillis());
    }

    @Test
    void acceptFailsInWrongState() {
        PlayerTrade t = invitedTrade();
        t.accept(NOW + 1000);
        assertThrows(IllegalStateException.class, () -> t.accept(NOW + 2000));
    }

    // ------------------------------------------------------------------
    // 报价变化 -> revision + 1 并清空确认
    // ------------------------------------------------------------------

    @Test
    void offerChangeBumpsRevisionAndClearsConfirmations() {
        PlayerTrade t = invitedTrade();
        t.accept(NOW + 1000);
        long rev0 = t.revision();

        t.confirm(TradeSide.LEFT, rev0, NOW + 2000);
        assertTrue(t.confirmed(TradeSide.LEFT));
        assertFalse(t.confirmed(TradeSide.RIGHT));
        assertEquals(rev0, t.leftConfirmedRevision());

        t.replaceOffer(TradeSide.LEFT, offerWithItem(), NOW + 3000);
        assertEquals(rev0 + 1, t.revision());
        assertFalse(t.confirmed(TradeSide.LEFT));
        assertEquals(-1, t.leftConfirmedRevision());
        assertEquals(-1, t.rightConfirmedRevision());
    }

    @Test
    void offerChangeInLockedStateRejected() {
        PlayerTrade t = bothConfirmed(tr -> tr);
        assertThrows(IllegalStateException.class,
                () -> t.replaceOffer(TradeSide.LEFT, offerWithItem(), NOW + 5000));
    }

    // ------------------------------------------------------------------
    // 双方确认同一 revision -> LOCKED + 3 秒锁定
    // ------------------------------------------------------------------

    @Test
    void singleConfirmDoesNotLock() {
        PlayerTrade t = invitedTrade();
        t.accept(NOW + 1000);
        long rev = t.revision();
        t.confirm(TradeSide.LEFT, rev, NOW + 2000);
        assertEquals(TradeStatus.OPEN, t.status());
        assertFalse(t.hasLockDeadline());
    }

    @Test
    void bothConfirmsSameRevisionLocksWithDeadline() {
        PlayerTrade t = bothConfirmed(tt -> tt);
        assertEquals(TradeStatus.LOCKED, t.status());
        assertTrue(t.hasLockDeadline());
        // 第二位玩家确认发生在 NOW+3000，锁定 deadline = 确认时刻 + 3 秒
        assertEquals(NOW + 3000 + PlayerTrade.LOCK_DURATION_MILLIS, t.lockDeadlineEpochMillis());
        assertEquals(PlayerTrade.LOCK_DURATION_MILLIS, t.lockRemainingMillis(NOW + 3000));
    }

    @Test
    void staleConfirmIgnored() {
        PlayerTrade t = invitedTrade();
        t.accept(NOW + 1000);
        long rev0 = t.revision();
        t.replaceOffer(TradeSide.LEFT, offerWithItem(), NOW + 2000); // revision +1

        assertFalse(t.confirm(TradeSide.LEFT, rev0, NOW + 3000));
        assertFalse(t.confirmed(TradeSide.LEFT));
    }

    @Test
    void staleSecondConfirmDoesNotLock() {
        PlayerTrade t = invitedTrade();
        t.accept(NOW + 1000);
        long rev0 = t.revision();
        t.replaceOffer(TradeSide.RIGHT, offerWithItem(), NOW + 2000); // rev0 -> rev0+1

        t.confirm(TradeSide.LEFT, rev0 + 1, NOW + 3000); // 新 revision 确认
        // 对手用旧 revision 确认：不进入 LOCKED
        assertFalse(t.confirm(TradeSide.RIGHT, rev0, NOW + 3000));
        assertEquals(TradeStatus.OPEN, t.status());
    }

    // ------------------------------------------------------------------
    // 3 秒锁定 -> COMMITTING
    // ------------------------------------------------------------------

    @Test
    void beginCommitBeforeDeadlineRejected() {
        PlayerTrade t = bothConfirmed(tt -> tt);
        assertThrows(IllegalStateException.class, () -> t.beginCommit(NOW + 4000));
    }

    @Test
    void beginCommitAfterDeadlineSucceeds() {
        PlayerTrade t = bothConfirmed(tt -> tt);
        long afterDeadline = NOW + 4000 + PlayerTrade.LOCK_DURATION_MILLIS + 1;
        t.beginCommit(afterDeadline);
        assertEquals(TradeStatus.COMMITTING, t.status());
    }

    @Test
    void committingThenMarkCommittedThenDeliveringThenCompleted() {
        PlayerTrade t = bothConfirmed(tt -> tt);
        long afterDeadline = NOW + 4000 + PlayerTrade.LOCK_DURATION_MILLIS + 1;
        t.beginCommit(afterDeadline);
        t.markCommitted(afterDeadline + 1);
        assertEquals(TradeStatus.COMMITTED, t.status());
        t.markDelivering(afterDeadline + 2);
        assertEquals(TradeStatus.DELIVERING, t.status());
        t.markCompleted(afterDeadline + 3);
        assertEquals(TradeStatus.COMPLETED, t.status());
        assertTrue(t.status().terminal());
    }

    @Test
    void terminalStateCannotChange() {
        PlayerTrade t = bothConfirmed(tt -> tt);
        long afterDeadline = NOW + 4000 + PlayerTrade.LOCK_DURATION_MILLIS + 1;
        t.beginCommit(afterDeadline);
        t.markCommitted(afterDeadline + 1);
        t.markDelivering(afterDeadline + 2);
        t.markCompleted(afterDeadline + 3);
        assertThrows(IllegalStateException.class, () -> t.markCompleted(afterDeadline + 4));
    }

    // ------------------------------------------------------------------
    // 取消
    // ------------------------------------------------------------------

    @Test
    void cancelFromOpenReturnsAssets() {
        PlayerTrade t = invitedTrade();
        t.accept(NOW + 1000);
        t.beginCancel(NOW + 2000);
        assertEquals(TradeStatus.CANCELLING, t.status());
        t.markCancelled(NOW + 3000);
        assertEquals(TradeStatus.CANCELLED, t.status());
        assertTrue(t.status().terminal());
    }

    @Test
    void cancelFromLockedAllowed() {
        PlayerTrade t = bothConfirmed(tt -> tt);
        t.beginCancel(NOW + 4000);
        assertEquals(TradeStatus.CANCELLING, t.status());
    }

    @Test
    void cancelFromCommittingForbidden() {
        PlayerTrade t = bothConfirmed(tt -> tt);
        long afterDeadline = NOW + 4000 + PlayerTrade.LOCK_DURATION_MILLIS + 1;
        t.beginCommit(afterDeadline);
        assertThrows(IllegalStateException.class, () -> t.beginCancel(afterDeadline + 1));
    }

    @Test
    void cancelFromTerminalForbidden() {
        PlayerTrade t = invitedTrade();
        t.accept(NOW + 1000);
        t.beginCancel(NOW + 2000);
        t.markCancelled(NOW + 3000);
        assertThrows(IllegalStateException.class, () -> t.beginCancel(NOW + 4000));
    }

    // ------------------------------------------------------------------
    // 锁定取消（unlockToOpen）
    // ------------------------------------------------------------------

    @Test
    void unlockToOpenBumpsRevisionAndClears() {
        PlayerTrade t = bothConfirmed(tt -> tt);
        long lockedRev = t.revision();
        t.unlockToOpen(NOW + 4500);
        assertEquals(TradeStatus.OPEN, t.status());
        assertEquals(lockedRev + 1, t.revision());
        assertFalse(t.hasLockDeadline());
        assertFalse(t.confirmed(TradeSide.LEFT));
        assertFalse(t.confirmed(TradeSide.RIGHT));
        assertEquals(null, t.feeQuote());
    }

    @Test
    void unlockFromOpenForbidden() {
        PlayerTrade t = invitedTrade();
        t.accept(NOW + 1000);
        assertThrows(IllegalStateException.class, () -> t.unlockToOpen(NOW + 2000));
    }

    // ------------------------------------------------------------------
    // 手续费 quote 与收货偏好
    // ------------------------------------------------------------------

    @Test
    void setDeliveryPreferenceBumpsRevisionAndClearsQuote() {
        PlayerTrade t = invitedTrade();
        t.accept(NOW + 1000);
        t.setFeeQuote(feeQuote(t, t.revision()), NOW + 2000);
        assertEquals(2, t.revision());

        t.setDeliveryPreference(TradeSide.LEFT,
                new DeliveryPreference(DeliveryPreference.ItemDestination.INBOX, DeliveryPreference.PokemonDestination.INBOX),
                NOW + 3000);
        assertEquals(3, t.revision());
        assertEquals(null, t.feeQuote());
        assertFalse(t.confirmed(TradeSide.LEFT));
    }

    @Test
    void quoteBoundToOtherTradeRejected() {
        PlayerTrade t = invitedTrade();
        t.accept(NOW + 1000);
        TradeFeeQuote otherQuote = new TradeFeeQuote(
                UUID.randomUUID(), UUID.randomUUID(), t.revision(), NOW + 60000, 0, 0,
                java.util.List.of(), "no-fee", 1);
        assertThrows(IllegalArgumentException.class, () -> t.setFeeQuote(otherQuote, NOW + 2000));
    }

    // ------------------------------------------------------------------
    // 到期 / 帮助方法
    // ------------------------------------------------------------------

    @Test
    void expiredDetectsDeadline() {
        PlayerTrade t = invitedTrade();
        assertTrue(t.expired(NOW + PlayerTrade.INVITE_TIMEOUT_MILLIS + 1));
        assertFalse(t.expired(NOW));
    }

    @Test
    void failRequiresAdminSetsFailure() {
        PlayerTrade t = bothConfirmed(tt -> tt);
        t.failRequiresAdmin(TradeError.PKM_DEBIT_FAILED, "unknown outcome", NOW + 4000);
        assertEquals(TradeStatus.FAILED_REQUIRES_ADMIN, t.status());
        assertEquals(TradeError.PKM_DEBIT_FAILED, t.failureError());
        assertTrue(t.status().terminal());
    }

    @Test
    void counterpartResolution() {
        PlayerTrade t = invitedTrade();
        assertEquals(RIGHT, t.counterpartOf(LEFT));
        assertEquals(LEFT, t.counterpartOf(RIGHT));
        assertEquals(null, t.counterpartOf(UUID.randomUUID()));
        assertTrue(t.isParticipant(LEFT));
        assertTrue(t.isParticipant(RIGHT));
        assertFalse(t.isParticipant(UUID.randomUUID()));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 双方确认同一 revision 进入 LOCKED */
    private static PlayerTrade bothConfirmed(java.util.function.UnaryOperator<PlayerTrade> adjust) {
        PlayerTrade t = invitedTrade();
        t.accept(NOW + 1000);
        long rev = t.revision();
        t.confirm(TradeSide.LEFT, rev, NOW + 2000);
        t.confirm(TradeSide.RIGHT, rev, NOW + 3000);
        return adjust.apply(t);
    }

    private static TradeFeeQuote feeQuote(PlayerTrade t, long revision) {
        return new TradeFeeQuote(
                UUID.randomUUID(), t.tradeId().value(), revision, NOW + 60000, 0, 0,
                java.util.List.of(), "no-fee", 1);
    }
}
