package com.pokeemc.trade.menu;

import com.pokeemc.registry.ModMenuTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家交易菜单（计划 5.4，Task 9）。
 * <p>
 * 纯远程视图容器：无业务槽位、无绑定方块，交易全部交互经 C2S packet 与服务端状态机
 * 完成（{@code trade/network/*}）。菜单会话只承载「玩家正在打开交易界面」这一状态。
 * <p>
 * {@link #stillValid} 只检查玩家在线与菜单会话，不依赖方块距离；服务端关闭菜单
 * 不取消交易 —— 交易生命周期完全由 {@code TradeService} 状态机（超时/锁定/提交）管理，
 * 关闭界面只是不再接收实时快照。
 */
public class PlayerTradeMenu extends AbstractContainerMenu {

    public PlayerTradeMenu(int containerId, Inventory playerInventory) {
        super(ModMenuTypes.PLAYER_TRADE.get(), containerId);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return !player.isRemoved();
    }

    /** 服务端打开交易界面（入口：命令 /pokeemc trade open 或后续目录入口） */
    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, p) -> new PlayerTradeMenu(containerId, inventory),
                Component.translatable("container.pokeemc.player_trade")));
    }
}
