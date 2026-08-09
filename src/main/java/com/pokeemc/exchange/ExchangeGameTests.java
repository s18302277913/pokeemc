package com.pokeemc.exchange;

import com.pokeemc.exchange.market.ExchangeWallet;
import com.pokeemc.exchange.market.TradeMarketService;
import com.pokeemc.exchange.price.ExchangePriceService;
import com.pokeemc.exchange.price.OfficialPriceParser;
import com.pokeemc.exchange.price.PriceOverrides;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.TradeResult;
import com.poketrade.api.market.MarketTradeService.CartLine;
import com.poketrade.api.price.PriceQuote;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 交易所经济 GameTest：官方双价 ×10、大师球 500 万、买入/出售端到端、无套利、
 * 重复行虚报总量拒绝（C1 修复回归）。
 *
 * <p>GameTest 服务器（runGameTestServer）不加载 Pixelmon 运行时（注册表、钱包、
 * 商店数据均不可用），因此测试通过注入的方式构造价格与钱包：
 * <ul>
 *   <li>价格：{@link ExchangePriceService} 测试构造注入官方价/覆盖价快照；</li>
 *   <li>钱包：{@link MemoryWallet} 内存实现，通过 {@link TradeMarketService} 注入构造绑定；</li>
 *   <li>物品：使用原版物品（stick/diamond），因为 Pixelmon 物品注册表不可用。</li>
 * </ul>
 * 玩家用 {@link FakePlayerFactory} 创建（GameTest 服务器无在线玩家）。</p>
 */
@GameTestHolder("poketrade")
@PrefixGameTestTemplate(false)
public class ExchangeGameTests {

    private static final String BATCH = "exchange";
    private static final TradeItemId STICK = TradeItemId.parse("minecraft:stick");
    private static final TradeItemId DIAMOND = TradeItemId.parse("minecraft:diamond");

    private static FakePlayer player(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer p = FakePlayerFactory.getMinecraft(level);
        // FakePlayerFactory 按 level 缓存同一实例，测试间共享背包；
        // 每个测试开始前清空，避免前一测试买入/放入的物品污染后续断言。
        p.getInventory().clearContent();
        return p;
    }

    private static ItemStack stackOf(String itemId, int count) {
        ResourceLocation rl = ResourceLocation.parse(itemId);
        return new ItemStack(BuiltInRegistries.ITEM.get(rl), count);
    }

    private static boolean hasInInventory(ServerPlayer p, String itemId) {
        for (ItemStack s : p.getInventory().items) {
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    private static int countInInventory(ServerPlayer p, String itemId) {
        int total = 0;
        for (ItemStack s : p.getInventory().items) {
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(itemId)) {
                total += s.getCount();
            }
        }
        return total;
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    /** 注入官方价（×10 前的原值）与覆盖价，构造测试价格服务。 */
    private static ExchangePriceService testPrices() {
        Map<TradeItemId, OfficialPriceParser.DoublePrice> official = new HashMap<>();
        official.put(STICK, new OfficialPriceParser.DoublePrice(200.0, 65.0)); // 买 2000 / 卖 650
        Map<TradeItemId, PriceOverrides.OverridePrice> overrides = new HashMap<>();
        overrides.put(DIAMOND, new PriceOverrides.OverridePrice(5_000_000L, 0L)); // 只买不卖，固定 500 万
        return new ExchangePriceService(official, overrides);
    }

    /** 内存钱包：GameTest 无 Pixelmon 运行时，用 Map 模拟。 */
    private static final class MemoryWallet implements ExchangeWallet {
        private final Map<UUID, Long> balances = new HashMap<>();

        @Override
        public long getBalance(ServerPlayer player) {
            return balances.getOrDefault(player.getUUID(), 0L);
        }

        @Override
        public boolean add(ServerPlayer player, long amount) {
            if (amount < 0) {
                return false;
            }
            balances.merge(player.getUUID(), amount, Long::sum);
            return true;
        }

        @Override
        public boolean take(ServerPlayer player, long amount) {
            if (amount < 0) {
                return false;
            }
            long b = getBalance(player);
            if (b < amount) {
                return false;
            }
            balances.put(player.getUUID(), b - amount);
            return true;
        }
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void catalogHasOfficialPricesMultiplied(GameTestHelper helper) {
        PriceQuote stick = testPrices().quote(STICK).orElse(null);
        check(helper, stick != null, "stick 应有定价");
        if (stick != null) {
            check(helper, stick.buyPrice() == 2000L, "stick 买价应为 200×10");
            check(helper, stick.sellPrice() == 650L, "stick 卖价应为 65×10");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void masterBallFixedAtFiveMillion(GameTestHelper helper) {
        PriceQuote mb = testPrices().quote(DIAMOND).orElse(null);
        check(helper, mb != null, "diamond 应有覆盖定价");
        if (mb != null) {
            check(helper, mb.buyPrice() == 5_000_000L, "覆盖价购买价固定 5,000,000");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void noArbitrageAcrossCatalog(GameTestHelper helper) {
        for (PriceQuote q : testPrices().catalog().entries()
                .stream().map(e -> e.quote()).toList()) {
            // 不可买入条目（buy=0，sell-only）合法，不参与套利断言
            check(helper, q.buyPrice() == 0 || q.buyPrice() >= q.sellPrice(),
                    q.itemId() + " 可买入价低于卖价（存在套利）");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void buyDeductsWalletAndGrantsItem(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        MemoryWallet wallet = new MemoryWallet();
        wallet.add(p, 10_000L);
        TradeResult r = new TradeMarketService(testPrices(), wallet).buyBatch(
                p, List.of(new CartLine(STICK, 1)), "buy-test-" + System.nanoTime());
        check(helper, r == TradeResult.SUCCESS, "买入应成功，实际 " + r);
        check(helper, wallet.getBalance(p) == 10_000L - 2_000L, "买入后钱包应扣 2000");
        check(helper, hasInInventory(p, "minecraft:stick"), "背包应获得 stick");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void sellFromInventoryCreditsWallet(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        MemoryWallet wallet = new MemoryWallet();
        p.getInventory().add(stackOf("minecraft:stick", 1));
        TradeResult r = new TradeMarketService(testPrices(), wallet).sellFromInventory(
                p, List.of(new CartLine(STICK, 1)), "sell-test-" + System.nanoTime());
        check(helper, r == TradeResult.SUCCESS, "出售应成功，实际 " + r);
        check(helper, wallet.getBalance(p) == 650L, "出售后钱包应 +650，实际 " + wallet.getBalance(p));
        check(helper, countInInventory(p, "minecraft:stick") == 0,
                "出售后背包应扣物品，剩余 " + countInInventory(p, "minecraft:stick"));
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void duplicateLinesCannotInflateSellValue(GameTestHelper helper) {
        // C1 回归：背包仅 40 个 stick，两条重复行各 30（总量虚报 60）必须被拒绝，
        // 否则钱包会按 60 入账而背包实际只扣 40（少扣多付）。
        ServerPlayer p = player(helper);
        MemoryWallet wallet = new MemoryWallet();
        p.getInventory().add(stackOf("minecraft:stick", 40));
        TradeResult r = new TradeMarketService(testPrices(), wallet).sellFromInventory(
                p, List.of(new CartLine(STICK, 30), new CartLine(STICK, 30)),
                "dup-test-" + System.nanoTime());
        check(helper, r == TradeResult.INVALID_QUANTITY, "跨行累计超过背包数量必须拒绝，实际 " + r);
        check(helper, wallet.getBalance(p) == 0L, "拒绝后钱包不应入账，实际 " + wallet.getBalance(p));
        check(helper, countInInventory(p, "minecraft:stick") == 40,
                "拒绝后背包物品不应被扣减，剩余 " + countInInventory(p, "minecraft:stick"));
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void masterBallBuyDeductsFiveMillion(GameTestHelper helper) {
        // 覆盖价 buy=5,000,000 / sell=0（只买不卖）：买入扣 500 万、物品入包
        ServerPlayer p = player(helper);
        MemoryWallet wallet = new MemoryWallet();
        wallet.add(p, 5_000_000L);
        TradeResult r = new TradeMarketService(testPrices(), wallet).buyBatch(
                p, List.of(new CartLine(DIAMOND, 1)), "mb-buy-" + System.nanoTime());
        check(helper, r == TradeResult.SUCCESS, "覆盖价买入应成功，实际 " + r);
        check(helper, wallet.getBalance(p) == 0L, "买入后钱包应扣 5,000,000");
        check(helper, hasInInventory(p, "minecraft:diamond"), "背包应获得 diamond");
        helper.succeed();
    }
}
