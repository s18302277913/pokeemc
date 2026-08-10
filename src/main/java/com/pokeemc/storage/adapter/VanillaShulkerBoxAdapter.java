package com.pokeemc.storage.adapter;

import static com.poketrade.api.storage.StorageCapability.AUTOMATION;
import static com.poketrade.api.storage.StorageCapability.EXTRACT;
import static com.poketrade.api.storage.StorageCapability.INSERT;
import static com.poketrade.api.storage.StorageCapability.SELL_SOURCE;
import static com.poketrade.api.storage.StorageCapability.SNAPSHOT;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 原版潜影盒适配器（typeId {@code vanilla_shulker_box} / {@code vanilla_<色>_shulker_box}）。
 *
 * <p>素盒与 16 色染色盒各对应一个适配器实例（共 17 个），按方块颜色互斥匹配：
 * {@code matches} 要求位置方块是 {@link ShulkerBoxBlock} 且其颜色与本实例一致
 * （素盒 {@code getColor()} 返回 {@code null}）。容量固定 27 格
 * （{@link ShulkerBoxBlockEntity#getContainerSize()}）。</p>
 */
public final class VanillaShulkerBoxAdapter extends AbstractContainerAdapter {

    /** 素盒类型 ID。 */
    public static final String TYPE_ID = "vanilla_shulker_box";

    private static final String COLORED_PREFIX = "vanilla_";
    private static final String COLORED_SUFFIX = "_shulker_box";

    private final DyeColor color;

    public VanillaShulkerBoxAdapter(DyeColor color) {
        super(typeIdFor(color), SNAPSHOT, INSERT, EXTRACT, SELL_SOURCE, AUTOMATION);
        this.color = color;
    }

    /** 全部 17 个实例：素盒 + 16 色染色盒。 */
    public static List<VanillaShulkerBoxAdapter> all() {
        List<VanillaShulkerBoxAdapter> out = new ArrayList<>(17);
        out.add(new VanillaShulkerBoxAdapter(null));
        for (DyeColor c : DyeColor.values()) {
            out.add(new VanillaShulkerBoxAdapter(c));
        }
        return out;
    }

    /** 颜色 → 类型 ID；素盒（{@code null}）返回 {@code vanilla_shulker_box}。 */
    public static String typeIdFor(DyeColor color) {
        if (color == null) {
            return TYPE_ID;
        }
        return COLORED_PREFIX + color.getSerializedName() + COLORED_SUFFIX;
    }

    @Override
    protected boolean matches(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ShulkerBoxBlock shulker)) {
            return false;
        }
        return Objects.equals(shulker.getColor(), this.color);
    }

    @Override
    protected Optional<ContainerAccess> resolve(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ShulkerBoxBlockEntity shulker)) {
            return Optional.empty();
        }
        return Optional.of(simple(shulker));
    }
}
