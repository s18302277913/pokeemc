package com.pokeemc.blockentity;

import com.pokeemc.menu.ExchangeMenu;
import com.pokeemc.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 转化桌方块实体。
 * <p>
 * 本桌不再存储自己的余额：PKM 余额即玩家的 Pixelmon 钱包
 * （见 {@link com.pokeemc.economy.PixelmonWallet}），由
 * {@link ExchangeMenu} 在服务端读写。
 * <p>
 * 打开入口自 Task 8 起切换到交易所菜单（三栏 UI）；旧 {@code TransmutationTableMenu}/
 * {@code TransmutationTableScreen} 类保留不删，避免破坏既有 GameTest 与迁移。
 */
public class TransmutationTableBlockEntity extends BlockEntity {

    public TransmutationTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TRANSMUTATION_TABLE.get(), pos, state);
    }

    public MenuProvider getMenuProvider() {
        return new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("block.poketrade.exchange");
            }

            @Override
            @Nullable
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
                return new ExchangeMenu(containerId, playerInventory, TransmutationTableBlockEntity.this);
            }
        };
    }

    public void tickServer() {
        // 预留：未来可在此处理自动补充等逻辑
    }
}
