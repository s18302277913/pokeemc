package com.pokeemc.menu;

import com.poketrade.api.storage.StorageDescriptor;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageSnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.jetbrains.annotations.Nullable;

/**
 * 仓储浏览菜单基类（Task 9/10 公共状态）。
 *
 * <p>承载「当前浏览的仓储」选择与「出售请求待结算」状态：</p>
 * <ul>
 *   <li>浏览状态（选中的仓储 id、描述、快照、修订）与出售待结算标记是<b>客户端侧</b>状态，
 *       服务端不依赖它们 —— 所有实际操作（移动/出售/管理）都通过带完整上下文的网络包执行，
 *       服务端自行解析与校验；</li>
 *   <li>菜单打开会话的有效性委托给 {@link ServerContext}（服务端由转化桌位置判定，
 *       客户端恒为 true），与旧版转化桌语义完全一致。</li>
 * </ul>
 *
 * <p>转化桌菜单 {@link TransmutationTableMenu} 继承本类，从而「交易所复用同一仓储列表与
 * 槽位组件」：范围/搜索/排序/过滤等<b>第二套权限或范围状态</b>一律不存在于菜单，
 * 只存在共享的 {@code StorageViewModel} 中。</p>
 */
public abstract class StorageBrowserMenu extends AbstractContainerMenu {

    /** 服务端菜单会话的有效性判定；客户端恒为 null（视为有效）。 */
    @FunctionalInterface
    public interface ServerContext {
        boolean stillValid(Player player);
    }

    private final ServerContext serverContext;

    // —— 客户端侧浏览状态（服务端不读这些字段） ——
    private StorageId selectedStorageId;
    private StorageDescriptor selectedDescriptor;
    private StorageSnapshot selectedSnapshot;
    private long selectedSnapshotRevision = -1L;
    private boolean snapshotStale = true;
    private boolean sellPending;

    protected StorageBrowserMenu(
            MenuType<?> type, int containerId, Inventory playerInventory,
            @Nullable ServerContext serverContext) {
        super(type, containerId);
        this.serverContext = serverContext;
    }

    @Override
    public boolean stillValid(Player player) {
        return serverContext == null || serverContext.stillValid(player);
    }

    /** 记录当前浏览的仓储（客户端侧；由屏幕在选中变化时写入）。 */
    public void setBrowsedStorage(
            StorageId storageId,
            @Nullable StorageDescriptor descriptor,
            @Nullable StorageSnapshot snapshot,
            long snapshotRevision) {
        this.selectedStorageId = storageId;
        this.selectedDescriptor = descriptor;
        this.selectedSnapshot = snapshot;
        this.selectedSnapshotRevision = snapshotRevision;
    }

    /** 当前浏览的仓储 id；未选择时返回 null。 */
    @Nullable
    public StorageId getBrowsedStorage() {
        return selectedStorageId;
    }

    /** 当前浏览仓储的描述信息（客户端侧缓存）。 */
    @Nullable
    public StorageDescriptor getBrowsedDescriptor() {
        return selectedDescriptor;
    }

    /** 当前浏览仓储的槽位快照（客户端侧缓存）。 */
    @Nullable
    public StorageSnapshot getBrowsedSnapshot() {
        return selectedSnapshot;
    }

    /** 当前浏览仓储快照对应的 revision；-1 表示未知。 */
    public long getBrowsedSnapshotRevision() {
        return selectedSnapshotRevision;
    }

    /** 快照是否已过期（客户端侧：操作后标记为需要重新拉取）。 */
    public boolean isSnapshotStale() {
        return snapshotStale;
    }

    /** 快照过期标记（客户端侧）。 */
    public void markSnapshotStale(boolean stale) {
        this.snapshotStale = stale;
    }

    /** 是否存在未结算的出售请求（客户端侧标记；服务端不依赖）。 */
    public boolean isSellPending() {
        return sellPending;
    }

    /** 出售请求待结算标记（客户端侧）。 */
    public void setSellPending(boolean sellPending) {
        this.sellPending = sellPending;
    }

    /** 服务端辅助：给指定玩家发送提示消息（客户端菜单侧无用）。 */
    protected static void sendSystemMessage(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    /**
     * 独立仓储浏览器菜单（Task 9）：无绑定方块，{@code ServerContext} 恒有效。
     * 只含玩家背包/快捷栏槽位；仓储槽位是虚拟视图（客户端按快照渲染、
     * 交互经 {@code StorageMovePacket} 发给服务端），不占用容器槽位。
     */
    public static class Standalone extends StorageBrowserMenu {

        private static final int PLAYER_INV_START = 0;
        private static final int PLAYER_INV_END = 27;
        private static final int HOTBAR_START = 27;
        private static final int HOTBAR_END = 36;

        // 客户端构造（由 MenuType 调用）
        public Standalone(int containerId, Inventory playerInventory) {
            this(containerId, playerInventory, null);
        }

        // 服务端构造
        public Standalone(int containerId, Inventory playerInventory, ServerContext serverContext) {
            super(com.pokeemc.registry.ModMenuTypes.STORAGE_BROWSER.get(),
                    containerId, playerInventory, serverContext);
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    this.addSlot(new net.minecraft.world.inventory.Slot(
                            playerInventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
                }
            }
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new net.minecraft.world.inventory.Slot(
                        playerInventory, col, 8 + col * 18, 198));
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            ItemStack itemstack = ItemStack.EMPTY;
            net.minecraft.world.inventory.Slot slot = this.slots.get(index);
            if (slot != null && slot.hasItem()) {
                ItemStack stack = slot.getItem();
                itemstack = stack.copy();
                if (index >= PLAYER_INV_START && index < PLAYER_INV_END) {
                    if (!this.moveItemStackTo(stack, HOTBAR_START, HOTBAR_END, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (index >= HOTBAR_START && index < HOTBAR_END) {
                    if (!this.moveItemStackTo(stack, PLAYER_INV_START, PLAYER_INV_END, false)) {
                        return ItemStack.EMPTY;
                    }
                }
                if (stack.isEmpty()) {
                    slot.set(ItemStack.EMPTY);
                } else {
                    slot.setChanged();
                }
                if (stack.getCount() == itemstack.getCount()) {
                    return ItemStack.EMPTY;
                }
                slot.onTake(player, stack);
            }
            return itemstack;
        }
    }
}
