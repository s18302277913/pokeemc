package com.pokeemc.menu;

import com.pokeemc.blockentity.TransmutationTableBlockEntity;
import com.pokeemc.economy.PixelmonWallet;
import com.pokeemc.emc.PKMManager;
import com.pokeemc.exchange.ExchangeService;
import com.pokeemc.network.StorageSellPacket;
import com.pokeemc.registry.ModMenuTypes;
import com.poketrade.api.TradeResult;
import com.poketrade.api.storage.StorageTransactionResult;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * 转化桌菜单：
 * <ul>
 *   <li>Slot 0：存入槽（放入物品自动换算成 PKM 入玩家钱包）</li>
 *   <li>Slot 1：结果槽（兑换出来的物品）</li>
 *   <li>Slot 2-28：玩家背包 27 格</li>
 *   <li>Slot 29-37：快捷栏 9 格</li>
 * </ul>
 * 余额即玩家 Pixelmon 钱包（宝可元）。用两个 DataSlot（高 32 位 + 低 32 位）
 * 把服务端钱包余额同步到客户端显示。
 *
 * <p>Task 8：本菜单继承 {@link StorageBrowserMenu}，成为仓储浏览会话的宿主；
 * 新增两个 DataSlot（出售结果码 + 序号）把服务端出售执行结果推给客户端展示。
 * 出售区是<b>虚拟视图</b>：屏幕层维护待出售请求，真实物品始终留在仓储槽位，
 * 点击「结算出售」才把完整请求经 {@link StorageSellPacket} 发给服务端执行。</p>
 */
public class TransmutationTableMenu extends StorageBrowserMenu {

    private static final int INPUT_SLOT = 0;
    private static final int OUTPUT_SLOT = 1;
    private static final int PLAYER_INV_START = 2;
    private static final int PLAYER_INV_END = 29;
    private static final int HOTBAR_START = 29;
    private static final int HOTBAR_END = 38;

    private final TransmutationTableBlockEntity table;
    /** 服务端打开菜单的玩家（钱包操作目标）；客户端构造时为 null */
    private final Player ownerPlayer;
    private final Container inputContainer;
    private final Container outputContainer;
    private final DataSlot pkmHigh;
    private final DataSlot pkmLow;
    /** 出售结果码（0=无；见 {@link #codeToSellCode(String)}）+ 自增序号（区分连续同码结果） */
    private final DataSlot sellResultCode;
    private final DataSlot sellResultNonce;
    private boolean processingInput = false;

    // 客户端构造（由 MenuType 调用）
    public TransmutationTableMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null);
    }

    // 服务端构造
    public TransmutationTableMenu(int containerId, Inventory playerInventory, TransmutationTableBlockEntity table) {
        super(ModMenuTypes.TRANSMUTATION_TABLE.get(), containerId, playerInventory,
                table == null ? null
                        : (Player p) -> table.getBlockPos().closerToCenterThan(p.position(), 8.0));
        this.table = table;
        this.ownerPlayer = table != null ? playerInventory.player : null;
        this.inputContainer = new SimpleContainer(1);
        this.outputContainer = new SimpleContainer(1);

        this.addSlot(new Slot(inputContainer, 0, 44, 37) {
            @Override
            public void setChanged() {
                super.setChanged();
                TransmutationTableMenu.this.onInputChanged();
            }
        });
        this.addSlot(new Slot(outputContainer, 0, 114, 37) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false; // 结果槽不可手动放入
            }
        });

        // 玩家背包（放在物品列表下方，避免与列表面板重叠）
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 164 + row * 18));
            }
        }
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 226));
        }

        // PKM 余额同步（钱包余额 long 拆高低位）
        this.pkmHigh = DataSlot.standalone();
        this.pkmLow = DataSlot.standalone();
        this.addDataSlot(pkmHigh);
        this.addDataSlot(pkmLow);
        // 出售结果同步（结果码 + 序号；客户端与服务端必须按相同顺序添加）
        this.sellResultCode = DataSlot.standalone();
        this.sellResultNonce = DataSlot.standalone();
        this.addDataSlot(sellResultCode);
        this.addDataSlot(sellResultNonce);
        if (table != null) {
            syncPkmFromWallet();
        }
    }

    /** 服务端：从玩家钱包读取最新余额写入 DataSlot（客户端据此显示） */
    private void syncPkmFromWallet() {
        long balance = PixelmonWallet.getBalance(ownerPlayer);
        pkmHigh.set((int) (balance >>> 32));
        pkmLow.set((int) (balance & 0xFFFFFFFFL));
    }

    /** 客户端/服务端读取当前 PKM 余额（即玩家钱包余额） */
    public long getPkm() {
        return (((long) pkmHigh.get()) << 32) | (pkmLow.get() & 0xFFFFFFFFL);
    }

    /** 输入槽内容变化（服务端）：物品折算成 PKM 直接入玩家钱包 */
    private void onInputChanged() {
        if (table == null || processingInput) {
            return; // 客户端无需处理 / 防止重入
        }
        processingInput = true;
        try {
            ItemStack stack = inputContainer.getItem(0);
            if (stack.isEmpty()) {
                return;
            }
            long value = PKMManager.getPkm(stack);
            if (value <= 0) {
                // [CHANGED] Bug E：无 PKM 价值的物品此前留在输入槽不清空，导致输入槽被永久占用，
                // 之后任何 Shift/拖拽放入（quickMoveStack 的 moveItemStackTo 目标槽非空）都会失败，
                // 表现为「按 Shift 不能直接把道具放入转化桌」。退还到背包并清空，保持输入槽随时可用。
                if (ownerPlayer instanceof ServerPlayer serverPlayer) {
                    serverPlayer.sendSystemMessage(Component.literal(
                            "PokeEMC: " + stack.getHoverName().getString()
                                    + " 无 PKM 价值，无法换算存入（已退还背包）"));
                }
                refundToPlayer(stack);
                return;
            }
            long total;
            try {
                total = Math.multiplyExact(value, stack.getCount());
            } catch (ArithmeticException ignored) {
                refundToPlayer(stack);
                return;
            }
            if (!PixelmonWallet.add(ownerPlayer, total)) {
                // [CHANGED] Bug E：入账失败（Pixelmon 钱包账户未就绪/异常）时同样退还，
                // 避免无反应卡槽；物品不出入账，语义为「放入即换算」失败即回退。
                if (ownerPlayer instanceof ServerPlayer serverPlayer) {
                    serverPlayer.sendSystemMessage(Component.literal(
                            "PokeEMC: 钱包入账失败，" + stack.getHoverName().getString()
                                    + " ×" + stack.getCount() + " 已退还背包"));
                }
                refundToPlayer(stack);
                return;
            }
            inputContainer.setItem(0, ItemStack.EMPTY);
            syncPkmFromWallet();
            broadcastChanges();
        } finally {
            processingInput = false;
        }
    }

    /**
     * [CHANGED] Bug E：把输入槽物品退还到玩家背包并清空输入槽。
     * 用 {@link #moveItemStackTo} 的 split 直接扣减输入槽引用，成功后槽位自动变空；
     * 背包满时留在输入槽并提示，防止物品凭空丢失。
     */
    private void refundToPlayer(ItemStack stack) {
        if (ownerPlayer instanceof ServerPlayer serverPlayer
                && !moveItemStackTo(stack, PLAYER_INV_START, HOTBAR_END, false)) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "PokeEMC: 背包已满，" + stack.getHoverName().getString()
                            + " ×" + stack.getCount() + " 无法退还"));
            return;
        }
        inputContainer.setItem(0, ItemStack.EMPTY);
        broadcastChanges();
    }

    /** 服务端：尝试兑换物品（从玩家钱包扣 PKM），返回稳定结果码 */
    public TradeResult purchase(ItemStack requested, int count) {
        if (table == null) {
            return TradeResult.INTERNAL_ERROR;
        }
        if (requested.isEmpty() || count <= 0) {
            return TradeResult.INVALID_QUANTITY;
        }
        ItemStack target = PKMManager.snapshotStacks().stream()
                .map(PKMManager.PricedStack::stack)
                .filter(stack -> ItemStack.isSameItemSameComponents(stack, requested))
                .findFirst()
                .map(stack -> stack.copyWithCount(count))
                .orElse(ItemStack.EMPTY);
        if (target.isEmpty()) {
            return TradeResult.UNKNOWN_ITEM;
        }
        if (count > target.getMaxStackSize()) {
            return TradeResult.INVALID_QUANTITY;
        }
        long value = PKMManager.getPkm(target);
        if (value <= 0) {
            return TradeResult.UNKNOWN_ITEM;
        }
        long cost;
        try {
            cost = Math.multiplyExact(value, count);
        } catch (ArithmeticException ignored) {
            return TradeResult.INVALID_QUANTITY;
        }
        if (!PixelmonWallet.take(ownerPlayer, cost)) {
            return TradeResult.INSUFFICIENT_FUNDS; // 钱包余额不足
        }
        ItemStack out = outputContainer.getItem(0);
        if (!out.isEmpty()) {
            // 结果槽已有物品：尝试堆叠
            if (!ItemStack.isSameItemSameComponents(out, target)
                    || out.getCount() + count > out.getMaxStackSize()) {
                // 无法放入，退回钱包
                PixelmonWallet.add(ownerPlayer, cost);
                syncPkmFromWallet();
                return TradeResult.OUTPUT_BLOCKED;
            }
            out.grow(count);
        } else {
            outputContainer.setItem(0, target);
        }
        syncPkmFromWallet();
        broadcastChanges();
        return TradeResult.SUCCESS;
    }

    @Override
    public void broadcastChanges() {
        // 钱包余额可能被其他途径（命令/商店）改动，广播前重新同步一次
        if (table != null) {
            syncPkmFromWallet();
        }
        super.broadcastChanges();
    }

    /**
     * 服务端：执行出售请求（由 {@link StorageSellPacket#executeSell} 调用）。
     * 结果写入 DataSlot（码 + 序号）供客户端轮询展示；出售区本身是虚拟视图，
     * 结算成功后由客户端清空出售区并重新拉取快照。
     */
    public StorageTransactionResult runSell(ServerPlayer player, StorageSellPacket packet) {
        if (table == null) {
            return StorageTransactionResult.failure("invalid_menu", "sell requires server menu");
        }
        StorageTransactionResult result = ExchangeService.forServer().sell(
                player.getUUID(), packet.sessionId(), packet.operationId(),
                packet.entries(), packet.expectedRevisions());
        sellResultNonce.set(sellResultNonce.get() + 1);
        sellResultCode.set(codeToSellCode(result.code()));
        broadcastChanges();
        return result;
    }

    /** 客户端：读取最后一次出售结果码（0=尚无结果）。 */
    public int getSellResultCode() {
        return sellResultCode.get();
    }

    /** 客户端：读取出售结果序号（连续出售且结果码相同也能区分）。 */
    public int getSellResultNonce() {
        return sellResultNonce.get();
    }

    /** 客户端：清空出售结果展示（下次序号变化时才会再次提示）。 */
    public void clearSellResult() {
        sellResultCode.set(0);
    }

    /** 服务端结果码 -> DataSlot 整数码（客户端据此显示中文文案）。 */
    public static int codeToSellCode(String code) {
        if (code == null) {
            return 0;
        }
        return switch (code) {
            case StorageTransactionResult.SUCCESS -> 1;
            case StorageTransactionResult.PERMISSION_DENIED -> 2;
            case StorageTransactionResult.REVISION_CONFLICT -> 3;
            case ExchangeService.VALUE_OVERFLOW -> 4;
            case ExchangeService.FREE_ITEM -> 5;
            case ExchangeService.WALLET_REJECTED -> 6;
            case ExchangeService.SOURCE_EMPTY -> 7;
            case ExchangeService.CONTENT_CHANGED -> 8;
            case StorageTransactionResult.ADAPTER_UNAVAILABLE,
                    StorageTransactionResult.NOT_FOUND,
                    StorageTransactionResult.CHUNK_UNLOADED,
                    StorageTransactionResult.NOT_CLAIMED -> 9;
            default -> 10; // invalid_* / distance / menu
        };
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            itemstack = stack.copy();
            if (index == OUTPUT_SLOT) {
                if (!this.moveItemStackTo(stack, PLAYER_INV_START, HOTBAR_END, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (index == INPUT_SLOT) {
                // 输入槽的物品移动到背包
                if (!this.moveItemStackTo(stack, PLAYER_INV_START, HOTBAR_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (this.moveItemStackTo(stack, INPUT_SLOT, INPUT_SLOT + 1, false)) {
                // 背包物品放入输入槽
            } else if (index >= PLAYER_INV_START && index < PLAYER_INV_END) {
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
