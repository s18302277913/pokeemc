package com.pokeemc.trade.service;

import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.PkmAsset;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.TradeCapability;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.persistence.InboxEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 11：能力服务测试（计划 5.x / Task 11 步骤 2）。
 * 覆盖全矩阵分支：SELF → OFFLINE → DISABLED_BY_SERVER → DISABLED_BY_PLAYER →
 * RATE_LIMITED → RECOVERY_REQUIRED → INVITE_PENDING → BUSY → (PKM_UNSUPPORTED | AVAILABLE)。
 */
class TradeCapabilityServiceTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final long NOW = 1_000_000L;

    private TradeServiceTest.FakeRepo repo;
    private TradeServiceTest.FakeResolver resolver;
    private MutableSettings settings;
    private TradeCapabilityServiceImpl capability;

    @BeforeEach
    void setUp() {
        repo = new TradeServiceTest.FakeRepo();
        resolver = new TradeServiceTest.FakeResolver();
        settings = new MutableSettings();
        capability = new TradeCapabilityServiceImpl(resolver, repo, settings);
        resolver.online.add(A);
        resolver.online.add(B);
        resolver.wallet().add(B, 100); // 默认 B 有 PKM 账户 -> 可达 AVAILABLE
    }

    @Test
    void selfWinsOverEveryOtherState() {
        // 即使 B 离线/限流/被禁用，SELF 仍然优先
        settings.playerTradingEnabled = false;
        settings.limitPlayers.add(A);
        resolver.online.remove(B);
        assertEquals(TradeCapability.SELF, capability.capabilityOf(A, A));
    }

    @Test
    void offlineWhenTargetNotOnline() {
        resolver.online.remove(B);
        assertEquals(TradeCapability.OFFLINE, capability.capabilityOf(A, B));
    }

    @Test
    void disabledByServerWhenTradingOff() {
        settings.tradingEnabled = false;
        assertEquals(TradeCapability.DISABLED_BY_SERVER, capability.capabilityOf(A, B));
    }

    @Test
    void disabledByPlayerWhenPlayerTradingOff() {
        settings.playerTradingEnabled = false;
        assertEquals(TradeCapability.DISABLED_BY_PLAYER, capability.capabilityOf(A, B));
    }

    @Test
    void rateLimitedAppliesToViewerOnly() {
        settings.limitPlayers.add(A);
        assertEquals(TradeCapability.RATE_LIMITED, capability.capabilityOf(A, B));
        // 限流只影响请求者视角，不影响其他 viewer
        resolver.online.add(C);
        assertEquals(TradeCapability.AVAILABLE, capability.capabilityOf(C, B));
    }

    @Test
    void recoveryRequiredWhenTargetHasFailedInbox() {
        PkmAsset asset = new PkmAsset(UUID.randomUUID(), B, 10, "op-1", true);
        repo.addInboxEntry(InboxEntry.pending(UUID.randomUUID(), B, asset,
                DeliveryPreference.defaults(), 1, NOW).withState(InboxEntry.InboxState.FAILED));
        assertEquals(TradeCapability.RECOVERY_REQUIRED, capability.capabilityOf(A, B));
    }

    @Test
    void pendingInviteShownToInvitee() {
        // A 邀请 B（INVITED 未接受）：B 视角看 A -> INVITE_PENDING（优先于 BUSY）
        repo.addTrade(PlayerTrade.invited(TradeId.random(), A, B, NOW));
        assertEquals(TradeCapability.INVITE_PENDING, capability.capabilityOf(B, A));
    }

    @Test
    void busyWhenOtherInActiveTrade() {
        // B 与 C 已进入 OPEN 交易：A 视角看 B -> BUSY
        PlayerTrade trade = PlayerTrade.invited(TradeId.random(), C, B, NOW);
        trade.accept(NOW);
        repo.addTrade(trade);
        assertEquals(TradeCapability.BUSY, capability.capabilityOf(A, B));
    }

    @Test
    void pkmUnsupportedWhenWalletHasNoAccount() {
        // 独立 resolver：B 钱包无账户（Pixelmon 后端不可用）：仅标记 PKM_UNSUPPORTED
        TradeServiceTest.FakeResolver noWallet = new TradeServiceTest.FakeResolver();
        noWallet.online.add(A);
        noWallet.online.add(B);
        TradeCapabilityService noWalletCapability =
                new TradeCapabilityServiceImpl(noWallet, repo, settings);
        assertEquals(TradeCapability.PKM_UNSUPPORTED, noWalletCapability.capabilityOf(A, B));
        // 物品/宝可梦交易不受影响：仍可邀请
        assertTrue(TradeCapability.PKM_UNSUPPORTED.invitable());
    }

    @Test
    void pkmUnsupportedWhenWalletNotIdempotent() {
        TradeServiceTest.FakeAccount account = resolver.wallet().add(B, 100);
        account.idempotent = false;
        assertEquals(TradeCapability.PKM_UNSUPPORTED, capability.capabilityOf(A, B));
    }

    @Test
    void availableWhenEverythingPasses() {
        assertEquals(TradeCapability.AVAILABLE, capability.capabilityOf(A, B));
        assertTrue(TradeCapability.AVAILABLE.invitable());
    }

    @Test
    void basicFactoryUsesDefaultsAndAllows() {
        TradeCapabilityService basic = TradeCapabilityService.basic(resolver, repo);
        assertEquals(TradeCapability.AVAILABLE, basic.capabilityOf(A, B));
    }

    /** 可变的开关/限流配置（测试注入） */
    private static final class MutableSettings implements TradeCapabilitySettings {
        boolean tradingEnabled = true;
        boolean playerTradingEnabled = true;
        final java.util.Set<UUID> limitPlayers = new java.util.HashSet<>();

        @Override
        public boolean tradingEnabled() {
            return tradingEnabled;
        }

        @Override
        public boolean playerTradingEnabled(UUID playerId) {
            return playerTradingEnabled;
        }

        @Override
        public boolean isRateLimited(UUID playerId) {
            return limitPlayers.contains(playerId);
        }
    }
}
