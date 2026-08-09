package com.pokeemc.menu;

import com.pokeemc.blockentity.TransmutationTableBlockEntity;
import com.pokeemc.economy.PixelmonWallet;
import com.pokeemc.exchange.ExchangeService;
import com.pokeemc.network.ExchangeBuyPacket;
import com.pokeemc.network.ExchangeSellPacket;
import com.pokeemc.network.StorageSellPacket;
import com.pokeemc.registry.ModMenuTypes;
import com.poketrade.api.TradeResult;
import com.poketrade.api.storage.StorageTransactionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 宝可梦交易所菜单（三栏 UI 的服务端容器）。
 *
 * <p>复用 {@link StorageBrowserMenu} 的仓储浏览状态与出售通道；新增：钱包余额 DataSlot、
 * 交易结果码 DataSlot（客户端轮询展示）。买入/出售指令经 {@link ExchangeBuyPacket}/
 * {@link ExchangeSellPacket} 送达，由 {@link com.pokeemc.exchange.market.TradeMarketService}
 * 执行后回写结果码。</p>
 *
 * <p>可达性校验由父类 {@link StorageBrowserMenu} 的 {@code ServerContext} 管理：
 * 服务端构造绑定转化桌方块（8 格内可达），客户端构造传 null（恒有效），
 * 本类<b>不覆写</b> {@link #stillValid}，避免绕过服务端方块距离校验。</p>
 */
public class ExchangeMenu extends StorageBrowserMenu {

    public enum Operation { NONE, BUY, INVENTORY_SELL, STORAGE_SELL }

    public enum FailureReason {
        NONE, PERMISSION_DENIED, REVISION_CONFLICT, CONTENT_CHANGED, UNAVAILABLE,
        WALLET_REJECTED, INVALID_REQUEST, INTERNAL_ERROR
    }

    private static final int PLAYER_INV_START = 0;
    private static final int PLAYER_INV_END = 27;
    private static final int HOTBAR_START = 27;
    private static final int HOTBAR_END = 36;
    private static final int INV_X = 154;
    private static final int INV_Y = 168;
    private static final int HOTBAR_Y = 222;

    private final DataSlot balanceHi;
    private final DataSlot balanceLo;
    private final DataSlot resultCode;
    private final DataSlot resultNonce;
    private final DataSlot resultOperation;
    private final DataSlot resultReason;
    /** 服务端打开菜单的玩家（钱包读写目标）；客户端构造时为 null */
    private final Player ownerPlayer;
    /** 客户端构造时保留的背包引用（收起/展开时重排玩家槽位坐标用） */
    private final Inventory playerInventoryRef;

    /** 客户端构造（容器数据槽由服务端初始化）。 */
    public ExchangeMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null);
    }

    /** 服务端构造：绑定转化桌方块（8 格内可达）。 */
    public ExchangeMenu(int containerId, Inventory playerInventory, TransmutationTableBlockEntity table) {
        super(ModMenuTypes.EXCHANGE.get(), containerId, playerInventory,
                table == null ? null : p ->
                        table.getBlockPos().closerToCenterThan(p.position(), 8.0));
        this.ownerPlayer = table != null ? playerInventory.player : null;
        this.playerInventoryRef = playerInventory;
        // 玩家背包槽位（保证背包物品可渲染/可交互、Shift 快速移动可用）
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, INV_X + col * 18, HOTBAR_Y));
        }
        // 钱包余额（long 拆高低位）
        this.balanceHi = addDataSlot(DataSlot.standalone());
        this.balanceLo = addDataSlot(DataSlot.standalone());
        // 交易结果（码 + nonce 用于轮询感知变化）；初始 -1 表示尚无结果，
        // 避免客户端 lastNonce(-1) 首次触发时把 0 误判为 SUCCESS 展示。
        this.resultCode = addDataSlot(DataSlot.standalone());
        this.resultNonce = addDataSlot(DataSlot.standalone());
        this.resultOperation = addDataSlot(DataSlot.standalone());
        this.resultReason = addDataSlot(DataSlot.standalone());
        if (table != null) {
            this.resultCode.set(-1);
            this.resultOperation.set(Operation.NONE.ordinal());
        }
        if (table != null && playerInventory.player instanceof ServerPlayer sp) {
            long balance = PixelmonWallet.getBalance(sp);
            balanceHi.set((int) (balance >> 32));
            balanceLo.set((int) (balance & 0xFFFFFFFFL));
        }
    }

    public long getBalance() {
        return (((long) balanceHi.get()) << 32) | (balanceLo.get() & 0xFFFFFFFFL);
    }

    public int getResultCode() {
        return resultCode.get();
    }

    public int getResultNonce() {
        return resultNonce.get();
    }

    public Operation getResultOperation() {
        int value = resultOperation.get();
        return value >= 0 && value < Operation.values().length ? Operation.values()[value] : Operation.NONE;
    }

    public FailureReason getResultReason() {
        int value = resultReason.get();
        return value >= 0 && value < FailureReason.values().length
                ? FailureReason.values()[value] : FailureReason.INTERNAL_ERROR;
    }

    public void reportTradeResult(Operation operation, TradeResult result) {
        reportTradeResult(operation, result, FailureReason.NONE);
    }

    public void reportTradeResult(Operation operation, TradeResult result, FailureReason reason) {
        resultCode.set(result.ordinal());
        resultOperation.set(operation.ordinal());
        resultReason.set(reason.ordinal());
        resultNonce.set(resultNonce.get() + 1);
    }

    /**
     * 清除交易结果展示。不递增 {@code resultNonce}：nonce 只由
     * {@link #reportTradeResult} 递增，配合客户端严格 nonce+1 匹配，
     * 中途清理不得打乱该契约（旧的实现递增 nonce，会令进行中的请求
     * 结果在客户端被判为过期而丢弃）。
     */
    public void clearTradeResult() {
        resultCode.set(-1);
        resultOperation.set(Operation.NONE.ordinal());
        resultReason.set(FailureReason.NONE.ordinal());
    }

    /**
     * 客户端专用：收起/展开时按新背包位置重建玩家槽位。
     *
     * <p>{@link Slot#x}/{@link Slot#y} 为 final 无法原地改坐标；原地 set 重建 36 个
     * 槽位对象（列表长度不变，父类 {@code lastSlots/remoteSlots} 对齐不受影响），
     * 渲染与点击都会跟随 {@code leftPos + slot.x}。服务端菜单无需调用。</p>
     */
    public void relayoutPlayerSlots(int invX, int invY, int hotbarY) {
        for (int i = 0; i < 36; i++) {
            int row = i / 9;
            int col = i % 9;
            Slot slot = new Slot(playerInventoryRef,
                    row == 3 ? col : col + row * 9 + 9,
                    invX + col * 18, row == 3 ? hotbarY : invY + row * 18);
            slot.index = i;
            this.slots.set(i, slot);
        }
    }

    /**
     * 服务端：执行仓储出售（由 {@link StorageSellPacket#executeSell} 调用）。
     *
     * <p>复用 {@link ExchangeService} 的两阶段出售语义；结果映射为 {@link TradeResult}
     * 写入 resultCode/resultNonce，与买入/背包出售共用同一条客户端展示通道。
     * 返回原始仓储事务结果供日志与调用方使用。</p>
     */
    public StorageTransactionResult runSell(ServerPlayer player, StorageSellPacket packet) {
        if (ownerPlayer == null) {
            return StorageTransactionResult.failure("invalid_menu", "sell requires server menu");
        }
        StorageTransactionResult result = ExchangeService.forServer().sell(
                player.getUUID(), packet.sessionId(), packet.operationId(),
                packet.entries(), packet.expectedRevisions());
        reportStorageResult(result);
        broadcastChanges();
        return result;
    }

    public void reportStorageResult(StorageTransactionResult result) {
        reportTradeResult(Operation.STORAGE_SELL, toTradeResult(result), toFailureReason(result));
    }

    /** 仓储事务结果 -> 交易所通用展示结果（客户端文案有限，失败统一归类）。 */
    private static TradeResult toTradeResult(StorageTransactionResult result) {
        if (result.success()) {
            return TradeResult.SUCCESS;
        }
        return switch (result.code()) {
            case StorageTransactionResult.PERMISSION_DENIED,
                    StorageTransactionResult.REVISION_CONFLICT,
                    StorageTransactionResult.ADAPTER_UNAVAILABLE,
                    StorageTransactionResult.NOT_FOUND,
                    StorageTransactionResult.CHUNK_UNLOADED,
                    StorageTransactionResult.NOT_CLAIMED,
                    ExchangeService.VALUE_OVERFLOW,
                    ExchangeService.FREE_ITEM,
                    "sell_disabled",
                    ExchangeService.SOURCE_EMPTY,
                    ExchangeService.CONTENT_CHANGED -> TradeResult.OUTPUT_BLOCKED;
            case ExchangeService.WALLET_REJECTED -> TradeResult.INSUFFICIENT_FUNDS;
            default -> TradeResult.INTERNAL_ERROR;
        };
    }

    private static FailureReason toFailureReason(StorageTransactionResult result) {
        if (result.success()) {
            return FailureReason.NONE;
        }
        return switch (result.code()) {
            case StorageTransactionResult.PERMISSION_DENIED -> FailureReason.PERMISSION_DENIED;
            case StorageTransactionResult.REVISION_CONFLICT -> FailureReason.REVISION_CONFLICT;
            case ExchangeService.CONTENT_CHANGED -> FailureReason.CONTENT_CHANGED;
            case StorageTransactionResult.ADAPTER_UNAVAILABLE,
                    StorageTransactionResult.NOT_FOUND,
                    StorageTransactionResult.CHUNK_UNLOADED,
                    StorageTransactionResult.NOT_CLAIMED -> FailureReason.UNAVAILABLE;
            case ExchangeService.WALLET_REJECTED -> FailureReason.WALLET_REJECTED;
            case ExchangeService.VALUE_OVERFLOW, ExchangeService.FREE_ITEM,
                    ExchangeService.SOURCE_EMPTY, "sell_disabled" -> FailureReason.INVALID_REQUEST;
            default -> FailureReason.INTERNAL_ERROR;
        };
    }

    @Override
    public void broadcastChanges() {
        // 买入/出售后钱包余额变化，广播前重新同步一次（与 TransmutationTableMenu 模式一致）
        if (ownerPlayer instanceof ServerPlayer sp) {
            long balance = PixelmonWallet.getBalance(sp);
            balanceHi.set((int) (balance >> 32));
            balanceLo.set((int) (balance & 0xFFFFFFFFL));
        }
        super.broadcastChanges();
    }

    @Override
    public MenuType<?> getType() {
        return ModMenuTypes.EXCHANGE.get();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 交易所无业务槽位，仅做背包内部移动（仿 StorageBrowserMenu.Standalone）
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
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
