package com.pokeemc.emc;

import com.pixelmonmod.pixelmon.api.pokemon.item.pokeball.PokeBall;
import com.pixelmonmod.pixelmon.api.pokemon.item.pokeball.PokeBallRegistry;
import com.pixelmonmod.pixelmon.items.PokeBallItem;
import com.pixelmonmod.pixelmon.items.PokeBallPart;
import com.pokeemc.PokeEMC;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PKMManager {

    public static final long NO_VALUE = -1;
    public static final long FREE = 0;

    private static final Object2LongOpenHashMap<ResourceLocation> PKM_VALUES = new Object2LongOpenHashMap<>();
    private static final Object2LongOpenHashMap<ResourceLocation> MANUAL_VALUES = new Object2LongOpenHashMap<>();
    private static final Object2LongOpenHashMap<String> BALL_VALUES = new Object2LongOpenHashMap<>();
    /**
     * 球种级手工 PKM 值（数据键 {@code pixelmon:poke_ball#<球种>}，经 {@link #setBallManual} 写入）。
     * [CHANGED] 会话 #16：与 {@code MANUAL_VALUES} 镜像，供 {@link #clearComputed()} 重灌球层，
     * 避免球价在清除合成树计算时丢失（同时保持旧 {@code pixelmon:<球种>} 幽灵键写法兼容）。
     */
    private static final Object2LongOpenHashMap<String> BALL_MANUAL_VALUES = new Object2LongOpenHashMap<>();

    /**
     * PKM 快照版本号：任何价值变更（setManual/setComputed/clearComputed）都会递增，
     * 供 {@link com.pokeemc.exchange.price.ExchangePriceService#catalog()} 检测快照变化后自动重建目录
     * （修复 Bug A/B：合成树计算发生在服务端启动后期，数据包 reload 时构建的目录早于计算被冻结）。
     */
    private static volatile long VERSION = 0;

    static {
        PKM_VALUES.defaultReturnValue(NO_VALUE);
        MANUAL_VALUES.defaultReturnValue(NO_VALUE);
        BALL_VALUES.defaultReturnValue(NO_VALUE);
        BALL_MANUAL_VALUES.defaultReturnValue(NO_VALUE);
    }

    private PKMManager() {}

    public static void init() {
        Map<String, Long> defaults = DefaultPkmValues.VANILLA_BASE;
        for (Map.Entry<String, Long> e : defaults.entrySet()) {
            ResourceLocation key = ResourceLocation.tryParse(e.getKey());
            if (key != null) {
                setManual(key, e.getValue());
            }
        }
        PokeEMC.LOGGER.info("PokeEMC: initialized {} manual vanilla base values", defaults.size());
    }

    public static void setManual(ResourceLocation item, long value) {
        // [DEPRECATED] 会话 #16：球种级 PKM 值请改用数据键 `pixelmon:poke_ball#<球种>` + setBallManual
        //（旧 `pixelmon:<球种>` 幽灵键会同时污染 MANUAL_VALUES/PKM_VALUES，本分支仅作向后兼容保留）。
        if (isPokeBallVariant(item)) {
            BALL_VALUES.put(item.getPath(), value);
        }
        MANUAL_VALUES.put(item, value);
        PKM_VALUES.put(item, value);
        VERSION++;
    }

    /**
     * 球种级 PKM 值（数据键 {@code pixelmon:poke_ball#<球种>}）。
     * [CHANGED] 会话 #16：只写球层（BALL_VALUES + BALL_MANUAL_VALUES），不进 PKM_VALUES/MANUAL_VALUES，
     * 从根上杜绝 `pixelmon:<球种>` 幽灵 id 流入 {@link com.pokeemc.exchange.price.ExchangePriceService#pkmFallback}
     * 的目录兜底路径（大师球 tooltip 显示 256 修复）。
     */
    public static void setBallManual(String ballName, long value) {
        BALL_MANUAL_VALUES.put(ballName, value);
        BALL_VALUES.put(ballName, value);
        VERSION++;
    }

    /** 球种 PKM 值（缺省返回 {@link #NO_VALUE}；供数据校验/单测使用）。 */
    public static long getBallValue(String ballName) {
        return BALL_VALUES.getLong(ballName);
    }

    public static void setComputed(ResourceLocation item, long value) {
        if (MANUAL_VALUES.containsKey(item)) {
            return;
        }
        long old = PKM_VALUES.getLong(item);
        if (old == NO_VALUE || value < old) {
            PKM_VALUES.put(item, value);
            VERSION++;
        }
    }

    /** PKM 快照版本号：目录重建后快照发生变化时可据此自动重建（Bug A/B 修复）。 */
    public static long version() {
        return VERSION;
    }

    public static long getPkm(ItemStack stack) {
        if (stack.isEmpty()) {
            return NO_VALUE;
        }
        if (pixelmonLoaded()) {
            Optional<PokeBall> ball = PokeBallPart.getPokeBall(stack);
            if (ball.isPresent()) {
                long value = BALL_VALUES.getLong(ball.get().getName());
                if (value != NO_VALUE) {
                    return value;
                }
            }
        }
        return getPkm(stack.getItem());
    }

    public static long getPkm(Item item) {
        // [CHANGED] 官方 API：builtInRegistryHolder() 已弃用，改用 BuiltInRegistries.ITEM.getKey
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return getPkm(key);
    }

    public static long getPkm(ResourceLocation item) {
        return PKM_VALUES.getLong(item);
    }

    public static boolean hasPkm(ItemStack stack) {
        return !stack.isEmpty() && getPkm(stack) >= 0;
    }

    public static boolean canBeOutput(ItemStack stack) {
        return hasPkm(stack);
    }

    public static Object2LongOpenHashMap<ResourceLocation> snapshot() {
        return new Object2LongOpenHashMap<>(PKM_VALUES);
    }

    /**
     * 球层快照（球种名 → PKM 值，如 {@code master_ball -> 5000000}）。
     * [CHANGED] 会话 #17（bug A/E）：{@link #snapshot()} 只含 PKM_VALUES，不含球层
     * （{@link #setBallManual} 仅写 BALL_VALUES），导致 ExchangePriceService.pkmFallback
     * 目录兜底缺全部球类条目 → 任何球都无法贩卖。目录重建需额外并入球层
     * 生成 {@code pixelmon:poke_ball#<球种>} 条目。
     */
    public static Object2LongOpenHashMap<String> ballSnapshot() {
        return new Object2LongOpenHashMap<>(BALL_VALUES);
    }

    public static List<PricedStack> snapshotStacks() {
        List<PricedStack> result = new ArrayList<>();
        ResourceLocation pokeBallId = ResourceLocation.fromNamespaceAndPath("pixelmon", "poke_ball");
        for (ResourceLocation key : PKM_VALUES.keySet()) {
            if (key.equals(pokeBallId) || isPokeBallVariant(key)) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(key);
            if (item != null && !BuiltInRegistries.ITEM.getKey(item).equals(ResourceLocation.withDefaultNamespace("air"))) {
                result.add(new PricedStack(new ItemStack(item), PKM_VALUES.getLong(key)));
            }
        }
        for (String name : BALL_VALUES.keySet()) {
            Optional<PokeBall> ball = PokeBallRegistry.getPokeBall(name).getValue();
            ball.ifPresent(value -> result.add(new PricedStack(PokeBallItem.of(value), BALL_VALUES.getLong(name))));
        }
        return result;
    }

    public static void clearComputed() {
        PKM_VALUES.clear();
        PKM_VALUES.putAll(MANUAL_VALUES);
        BALL_VALUES.clear();
        // [CHANGED] 会话 #16：先重灌球种级手工值（setBallManual 写入的 BALL_MANUAL_VALUES），
        // 再灌旧 `pixelmon:<球种>` 幽灵键写法（经 setManual 写入 MANUAL_VALUES 的球条目），
        // 保持「后写覆盖」语义——clearComputed 不再丢失球价。
        BALL_VALUES.putAll(BALL_MANUAL_VALUES);
        // [CHANGED] 官方 API：Object2LongMap 的 entrySet()/put(K,Long) 装箱变体已弃用，
        // 改用 object2LongEntrySet() + getLongValue() 原语访问避免自动装箱
        for (Object2LongMap.Entry<ResourceLocation> entry : MANUAL_VALUES.object2LongEntrySet()) {
            if (isPokeBallVariant(entry.getKey())) {
                BALL_VALUES.put(entry.getKey().getPath(), entry.getLongValue());
            }
        }
        VERSION++;
    }

    public static int size() {
        return PKM_VALUES.size();
    }

    private static boolean isPokeBallVariant(ResourceLocation item) {
        return item.getNamespace().equals("pixelmon")
                && pixelmonLoaded()
                && PokeBallRegistry.getPokeBall(item.getPath()).getValue().isPresent();
    }

    /** Pixelmon 为可选依赖；未加载时避免触碰其类（否则类加载会抛 ClassNotFoundException）。 */
    private static boolean pixelmonLoaded() {
        return ModList.get().isLoaded("pixelmon");
    }

    public record PricedStack(ItemStack stack, long value) {
        public PricedStack {
            stack = stack.copyWithCount(1);
        }
    }
}
