package com.pokeemc.item;

import com.pokeemc.blockentity.TransmutationTableBlockEntity;
import com.pokeemc.menu.ExchangeMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * [CHANGED] 会话 #28：便携式转化桌（手持右键打开交易所三栏界面）。
 * <p>
 * 复用转化桌方块的 {@link ExchangeMenu}/{@code ExchangeScreen} 界面：右键在服务端
 * 打开 {@link ExchangeMenu}，第三参传 {@code null}（无方块可达性校验、无 8 格距离限制）。
 * 服务端构造（{@code player instanceof ServerPlayer}）负责同步钱包与初始化交易结果码，
 * 由 {@link ExchangeMenu} 内部判定，本类只做打开动作。客户端返回 SUCCESS 以播放手臂摆动。
 */
public class PortableTransmutationTableItem extends Item {

    public static final Component TITLE =
            Component.translatable("item.poketrade.portable_transmutation_table");

    public PortableTransmutationTableItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, p) -> new ExchangeMenu(
                            containerId, inventory, (TransmutationTableBlockEntity) null),
                    TITLE));
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }
}
