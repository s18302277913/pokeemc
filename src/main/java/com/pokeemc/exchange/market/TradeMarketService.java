package com.pokeemc.exchange.market;

import com.pokeemc.PokeEMC;
import com.pokeemc.exchange.history.SalesHistory;
import com.pokeemc.config.PokeTradeConfig;
import com.pokeemc.economy.PixelmonWallet;
import com.pokeemc.exchange.price.ExchangePriceService;
import com.pokeemc.storage.adapter.PokeballIdentity;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.TradeResult;
import com.poketrade.api.market.MarketTradeService;
import com.poketrade.api.price.PriceCatalog;
import com.poketrade.api.price.PriceQuote;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 交易所市场服务实现：买入（扣钱包 → 物品进背包）与背包出售（扣物品 → 钱包入账）。
 *
 * <ul>
 *   <li>服务端每次执行重新报价（{@link ExchangePriceService}），不信任客户端价格；</li>
 *   <li>金额 long 精确乘法，溢出即失败；</li>
 *   <li>批量买入全成或全败：先模拟余额与背包空间，任一不满足整体失败；</li>
 *   <li>幂等键 {@code playerId|operationId}：成功结果 LRU 缓存（容量上限 1024），失败不缓存可重试；</li>
 *   <li>本服务仅限服务端主线程调用（幂等缓存非线程安全）。</li>
 * </ul>
 */
public final class TradeMarketService implements MarketTradeService {

    private static final int MAX_LINES = 27; // 购物车 27 格上限
    /** 单行购买数量上限：超出物品堆叠（64）时按最大堆叠自动拆成多个栈交付。 */
    private static final int MAX_QTY_PER_LINE = 1024; // 批量上限 1024

    private static volatile TradeMarketService serverInstance;

    private final ExchangePriceService prices;
    private final ExchangeWallet wallet;
    private final Map<String, TradeResult> idempotent = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TradeResult> eldest) {
            return size() > 1024;
        }
    };

    /** 测试用构造（注入价格与钱包）。 */
    public TradeMarketService(ExchangePriceService prices, ExchangeWallet wallet) {
        this.prices = Objects.requireNonNull(prices, "prices");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
    }

    /** 测试用构造（默认绑定 Pixelmon 钱包）。 */
    public TradeMarketService(ExchangePriceService prices) {
        this(prices, PIXELMON_WALLET);
    }

    private static final ExchangeWallet PIXELMON_WALLET = new ExchangeWallet() {
        @Override
        public long getBalance(ServerPlayer player) {
            return PixelmonWallet.getBalance(player);
        }

        @Override
        public boolean add(ServerPlayer player, long amount) {
            return PixelmonWallet.add(player, amount);
        }

        @Override
        public boolean take(ServerPlayer player, long amount) {
            return PixelmonWallet.take(player, amount);
        }
    };

    public static TradeMarketService forServer() {
        TradeMarketService current = serverInstance;
        if (current == null) {
            synchronized (TradeMarketService.class) {
                current = serverInstance;
                if (current == null) {
                    current = new TradeMarketService(ExchangePriceService.forServer());
                    serverInstance = current;
                }
            }
        }
        return current;
    }

    @Override
    public Optional<PriceCatalog> catalog() {
        PriceCatalog c = prices.catalog();
        return c.size() == 0 ? Optional.empty() : Optional.of(c);
    }

    @Override
    public Optional<PriceQuote> quote(TradeItemId itemId) {
        return prices.quote(itemId);
    }

    @Override
    public TradeResult buy(UUID playerId, TradeItemId itemId, int count) {
        if (count <= 0) {
            return TradeResult.INVALID_QUANTITY;
        }
        return buyBatch(playerId, List.of(new CartLine(itemId, count)), UUID.randomUUID().toString());
    }

    @Override
    public TradeResult buyBatch(UUID playerId, List<CartLine> lines, String operationId) {
        Objects.requireNonNull(playerId, "playerId");
        ServerPlayer player = playerOf(playerId);
        if (player == null) {
            return TradeResult.INTERNAL_ERROR;
        }
        return buyBatch(player, lines, operationId);
    }

    /** 测试/服务端直接入口：以给定玩家执行批量买入（GameTest 无在线玩家列表时传 FakePlayer）。 */
    public TradeResult buyBatch(ServerPlayer player, List<CartLine> lines, String operationId) {
        Objects.requireNonNull(player, "player");
        if (!PokeTradeConfig.exchangeBuyEnabled()) {
            return TradeResult.UNKNOWN_ITEM; // 服务端买入总开关关闭
        }
        if (lines == null || lines.isEmpty()) {
            return TradeResult.INVALID_QUANTITY;
        }
        if (lines.size() > MAX_LINES) {
            return TradeResult.INVALID_QUANTITY;
        }
        if (operationId == null || operationId.isBlank()) {
            return TradeResult.INVALID_QUANTITY;
        }
        UUID playerId = player.getUUID();
        String key = playerId + "|" + operationId;
        TradeResult cached = idempotent.get(key);
        if (cached != null) {
            return cached;
        }
        // 1) 服务端重新报价并模拟余额
        List<ItemStack> out = new ArrayList<>();
        long totalCost = 0L;
        for (CartLine line : lines) {
            if (line.count() <= 0) {
                return TradeResult.INVALID_QUANTITY;
            }
            PriceQuote q = prices.quote(line.itemId()).orElse(null);
            if (q == null || !q.buyAvailable()) {
                return TradeResult.UNKNOWN_ITEM;
            }
            Item item = itemOf(line.itemId());
            if (item == null) {
                return TradeResult.UNKNOWN_ITEM;
            }
            if (line.count() > MAX_QTY_PER_LINE) {
                return TradeResult.INVALID_QUANTITY;
            }
            long cost;
            try {
                cost = Math.multiplyExact(q.buyPrice(), line.count());
                totalCost = Math.addExact(totalCost, cost);
            } catch (ArithmeticException e) {
                return TradeResult.INVALID_QUANTITY;
            }
            // 购物车单行上限 64 与客户端一致；物品堆叠上限可能小于 64
            // （如末影珍珠 16、非堆叠 1），按物品最大堆叠拆成多个栈交付。
            // [CHANGED] 会话 #14：买入重建必须经 PokeballIdentity.decode 还原球种组件——
            // 原 new ItemStack(item) 对球类丢失 POKE_BALL 组件，买入大师球会降级成精灵球。
            int remaining = line.count();
            int maxStack = Math.max(1, item.getDefaultMaxStackSize());
            while (remaining > 0) {
                int part = Math.min(remaining, maxStack);
                ItemStack produced = PokeballIdentity.decode(line.itemId().toString(), part);
                if (produced == null || produced.isEmpty()) {
                    return TradeResult.UNKNOWN_ITEM;
                }
                out.add(produced);
                remaining -= part;
            }
        }
        // 2) 模拟背包空间（主 36 格，语义与 Inventory.add 一致）
        Inventory inv = player.getInventory();
        if (!canFit(inv, out)) {
            return TradeResult.OUTPUT_BLOCKED;
        }
        // 3) 扣钱包（拒绝则整体失败）
        if (!wallet.take(player, totalCost)) {
            return TradeResult.INSUFFICIENT_FUNDS;
        }
        // 4) 发物品（空间已精确模拟；此处仅防御极端边界，剩余物品掉落给玩家，绝不丢失）
        for (ItemStack stack : out) {
            if (!inv.add(stack) && !stack.isEmpty()) {
                player.drop(stack, false);
                PokeEMC.LOGGER.error("PokeEMC: buy output overflowed for {} op {}", playerId, operationId);
            }
        }
        TradeResult result = TradeResult.SUCCESS;
        idempotent.put(key, result);
        return result;
    }

    /**
     * 中栏单个卖出：直接从鼠标携带栈扣减出售。
     *
     * <p>物品可能来自仓储（拿起后尚未放入背包），背包统计里没有，走背包出售会误报
     * 「数量无效」；因此按携带栈重新报价、校验并扣减。成功后钱包入账并收缩携带栈。</p>
     */
    public TradeResult sellFromCarried(ServerPlayer player, AbstractContainerMenu menu, CartLine line) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(line, "line");
        if (!PokeTradeConfig.exchangeSellEnabled()) {
            return TradeResult.UNKNOWN_ITEM; // 服务端出售总开关关闭
        }
        if (line.count() <= 0) {
            return TradeResult.INVALID_QUANTITY;
        }
        PriceQuote q = prices.quote(line.itemId()).orElse(null);
        if (q == null || !q.sellAvailable()) {
            return TradeResult.UNKNOWN_ITEM;
        }
        if (!SellRules.current().canSell(line.itemId())) {
            return TradeResult.UNKNOWN_ITEM;
        }
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty() || line.count() > carried.getCount()
                || !itemIdOf(carried).equals(line.itemId())) {
            return TradeResult.INVALID_QUANTITY;
        }
        long value;
        try {
            value = Math.multiplyExact(q.sellPrice(), line.count());
        } catch (ArithmeticException e) {
            return TradeResult.INVALID_QUANTITY;
        }
        if (!wallet.add(player, value)) {
            return TradeResult.OUTPUT_BLOCKED;
        }
        carried.shrink(line.count());
        menu.broadcastChanges();
        // [NEW] 会话 #21-H 修订：成功出售 → 记入该玩家学习模式出售历史
        SalesHistory.record(player.getUUID(), line.itemId());
        return TradeResult.SUCCESS;
    }

    @Override
    public TradeResult sellFromInventory(UUID playerId, List<CartLine> lines, String operationId) {
        Objects.requireNonNull(playerId, "playerId");
        ServerPlayer player = playerOf(playerId);
        if (player == null) {
            return TradeResult.INTERNAL_ERROR;
        }
        return sellFromInventory(player, lines, operationId);
    }

    /** 测试/服务端直接入口：以给定玩家执行背包出售（GameTest 无在线玩家列表时传 FakePlayer）。 */
    public TradeResult sellFromInventory(ServerPlayer player, List<CartLine> lines, String operationId) {
        Objects.requireNonNull(player, "player");
        if (!PokeTradeConfig.exchangeSellEnabled()) {
            return TradeResult.UNKNOWN_ITEM; // 服务端出售总开关关闭
        }
        if (lines == null || lines.isEmpty()) {
            return TradeResult.INVALID_QUANTITY;
        }
        if (lines.size() > MAX_LINES) {
            return TradeResult.INVALID_QUANTITY;
        }
        if (operationId == null || operationId.isBlank()) {
            return TradeResult.INVALID_QUANTITY;
        }
        UUID playerId = player.getUUID();
        String key = playerId + "|" + operationId;
        TradeResult cached = idempotent.get(key);
        if (cached != null) {
            return cached;
        }
        Inventory inv = player.getInventory();
        // 1) 重新报价并模拟从背包扣除（逐行累减剩余数量，防止重复行虚报总量导致少扣多付）
        long totalValue = 0L;
        List<CartLine> validated = new ArrayList<>();
        Map<TradeItemId, Integer> remaining = new HashMap<>();
        for (CartLine line : lines) {
            if (line.count() <= 0) {
                return TradeResult.INVALID_QUANTITY;
            }
            PriceQuote q = prices.quote(line.itemId()).orElse(null);
            if (q == null || !q.sellAvailable()) {
                return TradeResult.UNKNOWN_ITEM;
            }
            if (!SellRules.current().canSell(line.itemId())) {
                return TradeResult.UNKNOWN_ITEM;
            }
            int have = remaining.computeIfAbsent(line.itemId(), id -> countInInventory(inv, id));
            if (have < line.count()) {
                return TradeResult.INVALID_QUANTITY; // 背包数量不足（含跨行累计）
            }
            remaining.put(line.itemId(), have - line.count());
            long value;
            try {
                value = Math.multiplyExact(q.sellPrice(), line.count());
                totalValue = Math.addExact(totalValue, value);
            } catch (ArithmeticException e) {
                return TradeResult.INVALID_QUANTITY;
            }
            validated.add(line);
        }
        // 2) 扣物品（保留组件快照用于回滚，避免回滚后物品数据降级；钱包入账失败则原样退回）
        List<ItemStack> removed = new ArrayList<>();
        for (CartLine line : validated) {
            removed.addAll(removeFromInventory(inv, line.itemId(), line.count()));
        }
        // 3) 钱包入账（拒绝则回滚物品）
        if (!wallet.add(player, totalValue)) {
            for (ItemStack st : removed) {
                if (st.isEmpty()) {
                    continue;
                }
                if (!inv.add(st) && !st.isEmpty()) {
                    player.drop(st, false); // 兜底：空间异常时掉落给玩家，绝不丢失
                }
            }
            return TradeResult.OUTPUT_BLOCKED;
        }
        TradeResult result = TradeResult.SUCCESS;
        idempotent.put(key, result);
        // [NEW] 会话 #21-H 修订：成功出售 → 记入该玩家学习模式出售历史（逐行记录，集合去重）
        for (CartLine line : validated) {
            SalesHistory.record(playerId, line.itemId());
        }
        return result;
    }

    // ================= 辅助 =================

    private static ServerPlayer playerOf(UUID playerId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(playerId);
    }

    /**
     * id → 基础 Item（用于 maxStack 判断与买入物品重建）。
     * [CHANGED] 会话 #14：球类 itemId 含 '#'（pixelmon:poke_ball#master_ball），
     * ResourceLocation.tryBuild 对 '#' 抛异常/返回 null → 大师球买入报 UNKNOWN_ITEM。
     * 改经 {@link PokeballIdentity#baseItem} 拆分 base 部分取基础 Item；未知/非法返回 null。
     */
    private static Item itemOf(TradeItemId id) {
        return PokeballIdentity.baseItem(id.toString());
    }

    /**
     * 主 36 格能否容纳全部物品（精确模拟 {@link Inventory#add}：先合并同物品槽位、再填空槽，可跨槽拆分）。
     * 只模拟 {@code Inventory.items}（主背包）：{@code Inventory.add} 不会放入盔甲/副手槽，计入会误判可容纳。
     */
    private static boolean canFit(Inventory inv, List<ItemStack> stacks) {
        List<ItemStack> items = inv.items;
        // 虚拟槽状态：槽位当前栈（引用原背包只读 count；空槽为 null）与已占用容量。
        ItemStack[] virtual = new ItemStack[items.size()];
        int[] used = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ItemStack slot = items.get(i);
            if (!slot.isEmpty()) {
                virtual[i] = slot;
                used[i] = slot.getCount();
            }
        }
        for (ItemStack s : stacks) {
            int remaining = s.getCount();
            // 1) 合并到同物品槽位（同组件，可跨槽拆分，容量逐槽扣减避免重复容纳）
            for (int i = 0; i < virtual.length && remaining > 0; i++) {
                ItemStack v = virtual[i];
                if (v == null || !ItemStack.isSameItemSameComponents(v, s)) {
                    continue;
                }
                int space = v.getMaxStackSize() - used[i];
                if (space <= 0) {
                    continue;
                }
                int take = Math.min(remaining, space);
                used[i] += take;
                remaining -= take;
            }
            // 2) 剩余放入空槽（每槽上限为该物品最大堆叠，可跨槽拆分）
            for (int i = 0; i < virtual.length && remaining > 0; i++) {
                if (virtual[i] != null) {
                    continue;
                }
                int take = Math.min(remaining, s.getMaxStackSize());
                virtual[i] = s; // 该空槽现被该物品占用，后续同物品行可继续合并
                used[i] = take;
                remaining -= take;
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    /** 主 36 格内该物品总数（与可出售范围一致，不含盔甲/副手）。 */
    private static int countInInventory(Inventory inv, TradeItemId id) {
        int total = 0;
        for (ItemStack slot : inv.items) {
            if (!slot.isEmpty() && itemIdOf(slot).equals(id)) {
                total += slot.getCount();
            }
        }
        for (ItemStack slot : inv.offhand) {
            if (!slot.isEmpty() && itemIdOf(slot).equals(id)) {
                total += slot.getCount();
            }
        }
        return total;
    }

    /**
     * 从主 36 格与副手槽按 id 扣除物品，返回实际扣减的栈列表（保留组件，用于失败回滚）。
     * 客户端出售预览同样扫描主背包与副手槽，两端保持一致。
     */
    private static List<ItemStack> removeFromInventory(Inventory inv, TradeItemId id, int count) {
        List<ItemStack> removed = new ArrayList<>();
        int remaining = count;
        for (ItemStack slot : inv.items) {
            if (remaining <= 0) {
                break;
            }
            if (!slot.isEmpty() && itemIdOf(slot).equals(id)) {
                int take = Math.min(slot.getCount(), remaining);
                ItemStack taken = slot.copy();
                taken.setCount(take);
                removed.add(taken);
                slot.shrink(take);
                remaining -= take;
            }
        }
        for (ItemStack slot : inv.offhand) {
            if (remaining <= 0) {
                break;
            }
            if (!slot.isEmpty() && itemIdOf(slot).equals(id)) {
                int take = Math.min(slot.getCount(), remaining);
                ItemStack taken = slot.copy();
                taken.setCount(take);
                removed.add(taken);
                slot.shrink(take);
                remaining -= take;
            }
        }
        return removed;
    }

    /**
     * ItemStack → TradeItemId（球类含球种后缀）。
     * [CHANGED] 会话 #14：球类 itemId 必须经 {@link PokeballIdentity#encode} 编码球种——
     * 原 BuiltInRegistries.ITEM.getKey 只给注册键 pixelmon:poke_ball，与出售请求的
     * 球种键 pixelmon:poke_ball#master_ball 永不相等 → 卖出大师球报「数量无效」。
     */
    private static TradeItemId itemIdOf(ItemStack stack) {
        String encoded = PokeballIdentity.encode(stack);
        return encoded == null ? TradeItemId.parse("minecraft:air")
                : TradeItemId.parse(encoded);
    }
}
