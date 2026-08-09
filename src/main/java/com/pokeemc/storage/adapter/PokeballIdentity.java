package com.pokeemc.storage.adapter;

import com.pixelmonmod.api.registry.RegistryValue;
import com.pixelmonmod.pixelmon.api.pokemon.item.pokeball.PokeBall;
import com.pixelmonmod.pixelmon.api.pokemon.item.pokeball.PokeBallRegistry;
import com.pixelmonmod.pixelmon.init.registry.PixelmonDataComponents;
import com.pixelmonmod.pixelmon.items.PokeBallItem;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Pixelmon 球类物品的身份编解码（Bug 1「大师球变精灵球」根治）。
 *
 * <p>Pixelmon（Reforged 9.x）的所有精灵球（精灵球/高级球/大师球…）共用同一个
 * 注册表键 {@code pixelmon:poke_ball}，球种由 {@code PokeBall} DataComponent
 * （{@link PixelmonDataComponents#POKE_BALL}）区分。若仓储链路只按注册表键读写
 * （{@code new ItemStack(item)}），大师球会被静默降级成普通精灵球——既损坏真实
 * 存储（set 重建丢组件），又误导显示（图标/名称错误）。</p>
 *
 * <p>因此仓储 itemId 对球类编码为 {@code "pixelmon:poke_ball#<球种名>"}
 * （如 {@code "pixelmon:poke_ball#master_ball"}），读写时经 {@link PokeBallItem#of}
 * 还原组件。`#` 非法于 ResourceLocation path，天然与普通物品 id 隔离；
 * 非球物品仍用注册表键。</p>
 */
public final class PokeballIdentity {

    public static final String POKE_BALL_ID = "pixelmon:poke_ball";
    public static final char SEP = '#';

    private PokeballIdentity() {
    }

    /** 编码 ItemStack → 仓储 itemId（球类含球种后缀；普通物品为注册表键）。 */
    public static String encode(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (item instanceof PokeBallItem) {
            String ball = pokeballKey(stack);
            if (ball != null && !ball.isEmpty()) {
                return POKE_BALL_ID + SEP + ball;
            }
        }
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** 读取 ItemStack 的球种名（{@code PokeBall#getName}）；非球/无组件/异常返回 null。 */
    private static String pokeballKey(ItemStack stack) {
        DataComponentType<RegistryValue<PokeBall>> type = PixelmonDataComponents.POKE_BALL.get();
        RegistryValue<PokeBall> value = stack.get(type);
        if (value == null) {
            return null;
        }
        try {
            PokeBall ball = value.get();
            return ball == null ? null : ball.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** itemId 的 base 注册表键部分（去掉球种后缀）；不可解析返回 null。 */
    public static Item baseItem(String itemId) {
        if (itemId == null) {
            return null;
        }
        int idx = itemId.indexOf(SEP);
        String base = idx < 0 ? itemId : itemId.substring(0, idx);
        ResourceLocation rl = ResourceLocation.tryParse(base);
        if (rl == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        return (item == null || item == Items.AIR) ? null : item;
    }

    /**
     * 解码 itemId → 带组件 ItemStack（含数量）。球类还原球种；未知球种/非法编码
     * 返回 null —— 调用方应拒绝操作而非静默降级成普通物品。
     */
    public static ItemStack decode(String itemId, int count) {
        if (itemId == null || count <= 0) {
            return null;
        }
        int idx = itemId.indexOf(SEP);
        String base = idx < 0 ? itemId : itemId.substring(0, idx);
        ResourceLocation rl = ResourceLocation.tryParse(base);
        if (rl == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == null || item == Items.AIR) {
            return null;
        }
        if (idx >= 0) {
            if (!POKE_BALL_ID.equals(base)) {
                return null; // 非球带后缀：非法编码
            }
            String ballKey = itemId.substring(idx + 1);
            RegistryValue<PokeBall> value = PokeBallRegistry.getPokeBall(ballKey);
            if (value == null || !value.isInitialized()) {
                return null; // 未知球种：拒绝降级成精灵球
            }
            return PokeBallItem.of(value.get(), count);
        }
        ItemStack s = new ItemStack(item);
        s.setCount(count);
        return s;
    }

    /** 显示名（球类还原球种后取 hoverName）；失败返回 null。 */
    public static String displayName(String itemId) {
        ItemStack s = decode(itemId, 1);
        return (s == null || s.isEmpty()) ? null : s.getHoverName().getString();
    }
}
