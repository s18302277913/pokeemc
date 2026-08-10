package com.pokeemc.exchange;

import com.pixelmonmod.api.registry.RegistryValue;
import com.pixelmonmod.pixelmon.api.pokemon.item.pokeball.PokeBall;
import com.pixelmonmod.pixelmon.api.pokemon.item.pokeball.PokeBallRegistry;
import com.pixelmonmod.pixelmon.init.registry.PixelmonDataComponents;
import com.pixelmonmod.pixelmon.items.PokeBallItem;
import com.pokeemc.emc.PKMManager;
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

    /**
     * 会话 #14 回归：球种感知键 pixelmon:poke_ball#master_ball 覆盖价 500 万
     * （买=卖）必须可报价且条目出现在目录——此前幽灵键 pixelmon:master_ball 因
     * 注册表不存在被 isObtainable 剔除，大师球「暂无定价」、交易列表不全。
     */
    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void masterBallBallVariantKeyIsPricedInCatalog(GameTestHelper helper) {
        TradeItemId masterBall = TradeItemId.parse("pixelmon:poke_ball#master_ball");
        Map<TradeItemId, OfficialPriceParser.DoublePrice> official = new HashMap<>();
        Map<TradeItemId, PriceOverrides.OverridePrice> overrides = new HashMap<>();
        overrides.put(masterBall, new PriceOverrides.OverridePrice(5_000_000L, 5_000_000L));
        ExchangePriceService svc = new ExchangePriceService(official, overrides);

        PriceQuote q = svc.quote(masterBall).orElse(null);
        check(helper, q != null, "球种感知键覆盖价应有报价，实际 null");
        if (q != null) {
            check(helper, q.buyPrice() == 5_000_000L && q.sellPrice() == 5_000_000L,
                    "大师球覆盖价应为买=卖=5,000,000，实际 buy=" + q.buyPrice() + " sell=" + q.sellPrice());
            check(helper, q.buyAvailable(), "大师球应可买入");
        }
        boolean inCatalog = svc.catalog().entries().stream()
                .anyMatch(e -> e.quote().itemId().equals(masterBall));
        check(helper, inCatalog, "球种感知键条目应出现在目录");
        helper.succeed();
    }

    /**
     * 会话 #16：balls.json（`#` 球种级键）必须已装载球层（getBallValue 返回覆盖价），
     * 且不产生 `pixelmon:<球种>` 幽灵 id（snapshot 不含幽灵键）——大师球 tooltip
     * 显示 256 修复的数据层验证（数据包在服务器启动时由 PkmDataLoader 装载）。
     */
    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void ballPkmDataLoadedWithoutGhostIds(GameTestHelper helper) {
        check(helper, PKMManager.getBallValue("master_ball") == 5_000_000L,
                "master_ball PKM 应为 5,000,000，实际 " + PKMManager.getBallValue("master_ball"));
        check(helper, PKMManager.getBallValue("ultra_ball") == 1024L,
                "ultra_ball PKM 应为 1024，实际 " + PKMManager.getBallValue("ultra_ball"));
        boolean ghost = PKMManager.snapshot().containsKey(
                ResourceLocation.fromNamespaceAndPath("pixelmon", "master_ball"));
        check(helper, !ghost, "pixelmon:master_ball 幽灵键不应进入 PKM 快照");
        helper.succeed();
    }

    /**
     * 会话 #14 回归：买入球种感知键必须还原 POKE_BALL 组件（BuyMarketService.buyBatch
     * 交付改经 PokeballIdentity.decode）——买入大师球到手是大师球，不降级成精灵球。
     */
    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void buyMasterBallKeepsVariant(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        MemoryWallet wallet = new MemoryWallet();
        wallet.add(p, 5_000_000L);
        TradeItemId masterBall = TradeItemId.parse("pixelmon:poke_ball#master_ball");
        Map<TradeItemId, OfficialPriceParser.DoublePrice> official = new HashMap<>();
        Map<TradeItemId, PriceOverrides.OverridePrice> overrides = new HashMap<>();
        overrides.put(masterBall, new PriceOverrides.OverridePrice(5_000_000L, 5_000_000L));
        TradeResult r = new TradeMarketService(new ExchangePriceService(official, overrides), wallet)
                .buyBatch(p, List.of(new CartLine(masterBall, 1)), "mb-var-buy-" + System.nanoTime());
        check(helper, r == TradeResult.SUCCESS, "球种感知键买入应成功，实际 " + r);
        check(helper, wallet.getBalance(p) == 0L, "买入后钱包应扣 5,000,000，实际 " + wallet.getBalance(p));
        boolean found = false;
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty() || !(s.getItem() instanceof PokeBallItem)) {
                continue;
            }
            RegistryValue<PokeBall> ball = s.get(PixelmonDataComponents.POKE_BALL.get());
            if (ball != null && "master_ball".equals(ball.get().getName())) {
                found = true;
                break;
            }
        }
        check(helper, found, "买入到手应是带 master_ball 组件的球，而非普通精灵球");
        helper.succeed();
    }

    /**
     * 会话 #16 组 5（任务 E）：expansion.json（80 条原版高频品，分层价 128~294912）
     * 必须已装载进 PKM 快照，且块价 = 组分 × 合成数（防「买块拆卖」套利）——
     * 交易所 buy=sell 与数据自洽性由 PkmDataLoader 在服务器启动时保证。
     */
    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void expansionPricesLoadedWithSelfConsistentBlockValues(GameTestHelper helper) {
        // 补齐的矿石/锭/战利品
        check(helper, pkm("minecraft:copper_ingot") == 256L,
                "copper_ingot PKM 应为 256（对齐 iron），实际 " + pkm("minecraft:copper_ingot"));
        check(helper, pkm("minecraft:iron_ore") == 256L,
                "iron_ore PKM 应为 256（=iron_ingot，防买矿炼锭卖套利），实际 " + pkm("minecraft:iron_ore"));
        check(helper, pkm("minecraft:diamond_ore") == 8192L,
                "diamond_ore PKM 应为 8192（=diamond），实际 " + pkm("minecraft:diamond_ore"));
        check(helper, pkm("minecraft:elytra") == 65536L,
                "elytra PKM 应为 65536，实际 " + pkm("minecraft:elytra"));
        check(helper, pkm("minecraft:enchanted_golden_apple") == 32768L,
                "enchanted_golden_apple PKM 应为 32768，实际 " + pkm("minecraft:enchanted_golden_apple"));
        // 块价 = 组分 × 合成数（无套利）
        check(helper, pkm("minecraft:iron_block") == 9L * pkm("minecraft:iron_ingot"),
                "iron_block 应为 9×iron_ingot（2304），实际 " + pkm("minecraft:iron_block"));
        check(helper, pkm("minecraft:gold_block") == 9L * pkm("minecraft:gold_ingot"),
                "gold_block 应为 9×gold_ingot（18432），实际 " + pkm("minecraft:gold_block"));
        check(helper, pkm("minecraft:diamond_block") == 9L * pkm("minecraft:diamond"),
                "diamond_block 应为 9×diamond（73728，非 32768 防拆卖套利），实际 "
                        + pkm("minecraft:diamond_block"));
        check(helper, pkm("minecraft:netherite_block") == 4L * pkm("minecraft:netherite_ingot"),
                "netherite_block 应为 4×netherite_ingot（294912），实际 " + pkm("minecraft:netherite_block"));
        helper.succeed();
    }

    /**
     * 会话 #16（bug 5 防御）：整组数量（64）批量出售必须成功——客户端 SellPreview
     * 按 itemId 聚合（非堆叠物品如精灵球 64 个 = 64 槽 → 一行 count=64），服务端
     * sellFromInventory 以 countInInventory 全背包统计校验，不得误报「数量无效」。
     */
    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void sellFromInventoryAcceptsFullStackQuantity(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        MemoryWallet wallet = new MemoryWallet();
        p.getInventory().add(stackOf("minecraft:stick", 64));
        TradeResult r = new TradeMarketService(testPrices(), wallet).sellFromInventory(
                p, List.of(new CartLine(STICK, 64)), "full-stack-" + System.nanoTime());
        check(helper, r == TradeResult.SUCCESS, "整组 64 出售应成功，实际 " + r);
        check(helper, wallet.getBalance(p) == 64L * 650L,
                "钱包应 +64×650，实际 " + wallet.getBalance(p));
        check(helper, countInInventory(p, "minecraft:stick") == 0,
                "出售后背包应清空，剩余 " + countInInventory(p, "minecraft:stick"));
        helper.succeed();
    }

    /**
     * 会话 #17（bug A/E 根治回归）：生产目录必须包含全部球种（普通精灵球 base 与
     * 全部 `pixelmon:poke_ball#<球种>`），且 buy=sell=PKM 值（无套利）——此前
     * BALL_VALUES 不进 pkmFallback，目录无球条目，任何球都无法贩卖；balls.json
     * 缺 base 普通精灵球，仓储普通精灵球「暂无定价」。数据经 PkmDataLoader 装载。
     */
    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void allBallVariantsPricedInServerCatalog(GameTestHelper helper) {
        ExchangePriceService svc = ExchangePriceService.forServer();
        for (String ball : List.of("poke_ball", "ultra_ball", "master_ball", "dream_ball")) {
            TradeItemId id = TradeItemId.parse("pixelmon:poke_ball#" + ball);
            PriceQuote q = svc.quote(id).orElse(null);
            check(helper, q != null, "球种 " + ball + " 目录应有报价，实际 null");
            if (q != null) {
                check(helper, q.buyPrice() > 0 && q.buyPrice() == q.sellPrice(),
                        "球种 " + ball + " 应 buy=sell>0（可买可卖无套利），实际 buy="
                                + q.buyPrice() + " sell=" + q.sellPrice());
            }
        }
        // base 普通精灵球（无组件 `pixelmon:poke_ball`）同样应可报价（balls.json base 键）
        PriceQuote base = svc.quote(TradeItemId.parse("pixelmon:poke_ball")).orElse(null);
        check(helper, base != null && base.sellPrice() > 0,
                "base 普通精灵球应可回收，实际 " + base);
        helper.succeed();
    }

    /**
     * 会话 #17（bug B 服务端逻辑验证）：背包同时存在普通精灵球与大师球时，
     * 出售大师球必须只扣大师球（球种精确识别），普通精灵球保留——
     * 批量出售逐槽 itemId（pixelmon:poke_ball#<球种>）不得互相混淆。
     */
    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void sellFromInventoryDistinguishesBallVariants(GameTestHelper helper) {
        RegistryValue<PokeBall> poke = PokeBallRegistry.getPokeBall("poke_ball");
        RegistryValue<PokeBall> master = PokeBallRegistry.getPokeBall("master_ball");
        check(helper, poke != null && poke.isInitialized() && master != null && master.isInitialized(),
                "Pixelmon poke_ball/master_ball 应已注册");
        ServerPlayer p = player(helper);
        MemoryWallet wallet = new MemoryWallet();
        p.getInventory().add(PokeBallItem.of(poke.get(), 1));
        p.getInventory().add(PokeBallItem.of(master.get(), 1));

        Map<TradeItemId, OfficialPriceParser.DoublePrice> official = new HashMap<>();
        Map<TradeItemId, PriceOverrides.OverridePrice> overrides = new HashMap<>();
        TradeItemId pokeBall = TradeItemId.parse("pixelmon:poke_ball#poke_ball");
        TradeItemId masterBall = TradeItemId.parse("pixelmon:poke_ball#master_ball");
        overrides.put(pokeBall, new PriceOverrides.OverridePrice(256L, 256L));
        overrides.put(masterBall, new PriceOverrides.OverridePrice(5_000_000L, 5_000_000L));
        TradeResult r = new TradeMarketService(new ExchangePriceService(official, overrides), wallet)
                .sellFromInventory(p, List.of(new CartLine(masterBall, 1)), "ball-sell-" + System.nanoTime());
        check(helper, r == TradeResult.SUCCESS, "出售大师球应成功，实际 " + r);
        check(helper, wallet.getBalance(p) == 5_000_000L,
                "钱包应 +5,000,000（大师球），实际 " + wallet.getBalance(p));
        boolean masterGone = true;
        boolean pokeKept = false;
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty() || !(s.getItem() instanceof PokeBallItem)) {
                continue;
            }
            RegistryValue<PokeBall> ball = s.get(PixelmonDataComponents.POKE_BALL.get());
            if (ball != null && "master_ball".equals(ball.get().getName())) {
                masterGone = false; // 大师球不应残留
            } else {
                pokeKept = true; // 普通精灵球应保留
            }
        }
        check(helper, masterGone, "大师球应已被出售扣除");
        check(helper, pokeKept, "普通精灵球应保留（未与大师球混淆）");
        helper.succeed();
    }

    /** PKM 快照查询 helper（未命中返回 -1）。 */
    private static long pkm(String id) {
        return PKMManager.getPkm(ResourceLocation.parse(id));
    }
}
