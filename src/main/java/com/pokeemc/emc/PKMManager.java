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

    static {
        PKM_VALUES.defaultReturnValue(NO_VALUE);
        MANUAL_VALUES.defaultReturnValue(NO_VALUE);
        BALL_VALUES.defaultReturnValue(NO_VALUE);
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
        if (isPokeBallVariant(item)) {
            BALL_VALUES.put(item.getPath(), value);
        }
        MANUAL_VALUES.put(item, value);
        PKM_VALUES.put(item, value);
    }

    public static void setComputed(ResourceLocation item, long value) {
        if (MANUAL_VALUES.containsKey(item)) {
            return;
        }
        long old = PKM_VALUES.getLong(item);
        if (old == NO_VALUE || value < old) {
            PKM_VALUES.put(item, value);
        }
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
        // [CHANGED] 官方 API：Object2LongMap 的 entrySet()/put(K,Long) 装箱变体已弃用，
        // 改用 object2LongEntrySet() + getLongValue() 原语访问避免自动装箱
        for (Object2LongMap.Entry<ResourceLocation> entry : MANUAL_VALUES.object2LongEntrySet()) {
            if (isPokeBallVariant(entry.getKey())) {
                BALL_VALUES.put(entry.getKey().getPath(), entry.getLongValue());
            }
        }
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
