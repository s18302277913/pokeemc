package com.pokeemc.trade.asset;

import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.TradeError;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * 物品托管 gateway（Task 3）：三阶段接口 prepare -> remove -> deliver。
 * 核心逻辑只依赖 {@link PlayerInventoryStore} 抽象，可在 JVM 单测驱动。
 *
 * <ul>
 *   <li>{@link #prepare}：校验槽位/数量/NBT 上限，快照原栈（含组件）；</li>
 *   <li>{@link #remove}：重新比较槽位内容与快照，原子扣减/清空，产出 {@link ItemAsset}；</li>
 *   <li>{@link #deliver}：按收货偏好放入背包，放不下的部分返回剩余（由调用方转收件箱）；</li>
 *   <li>{@link #cancel}：把快照放回原槽（槽位已被占则失败，进入收件箱）。</li>
 * </ul>
 *
 * <p>NBT 上限：单个物品 64 KiB（计划 5.1），超限拒绝托管。
 * 物品身份用完整 ItemStack NBT（组件级），不按 registry id 判断。</p>
 */
public final class ItemEscrowGateway {

    /** 单个物品 NBT 上限（字节）：64 KiB（计划 5.1） */
    public static final int MAX_ITEM_NBT_BYTES = 64 * 1024;

    private ItemEscrowGateway() {
    }

    /** 托管前的槽位快照 */
    public record PreparedItem(int slot, int count, ItemSnapshot snapshot) {

        public PreparedItem {
            if (slot < 0) {
                throw new IllegalArgumentException("slot cannot be negative");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive");
            }
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** remove 产物：已托管资产 + 移除的快照 */
    public record EscrowedItem(ItemAsset asset, ItemSnapshot removed) {

        public EscrowedItem {
            Objects.requireNonNull(asset, "asset");
            Objects.requireNonNull(removed, "removed");
        }
    }

    /**
     * 阶段 1：校验并快照槽位。
     *
     * @param store  背包存储
     * @param slot   槽位（含背包主导行偏移，由调用方计算）
     * @param count  待托管数量（必须 &gt; 0 且 &lt;= 槽内数量）
     * @param owner  资产原所有者（用于 ItemAsset）
     * @return 成功返回 PreparedItem；失败返回稳定 TradeError
     */
    public static Outcome<PreparedItem> prepare(PlayerInventoryStore store, int slot, int count, UUID owner) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(owner, "owner");
        if (slot < 0 || slot >= store.size()) {
            return Outcome.fail(TradeError.INVALID_ITEM_SLOT);
        }
        ItemSnapshot snapshot = store.get(slot);
        if (snapshot.isEmpty()) {
            return Outcome.fail(TradeError.INVALID_ITEM_SLOT);
        }
        if (count <= 0) {
            return Outcome.fail(TradeError.INVALID_COUNT);
        }
        if (count > snapshot.count()) {
            return Outcome.fail(TradeError.INVALID_COUNT);
        }
        if (nbtSizeBytes(snapshot.nbt()) > MAX_ITEM_NBT_BYTES) {
            return Outcome.fail(TradeError.ITEM_NBT_TOO_LARGE);
        }
        return Outcome.ok(new PreparedItem(slot, count, snapshot));
    }

    /**
     * 阶段 2：移除。remove 前重新比较槽位内容与快照（组件级 + 数量）。
     * 槽位被修改（包括物品被换走/数量不足）一律拒绝，保证不误扣。
     */
    public static Outcome<EscrowedItem> remove(PlayerInventoryStore store, PreparedItem prepared, UUID owner) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(owner, "owner");
        ItemSnapshot current = store.get(prepared.slot());
        if (current.isEmpty()) {
            return Outcome.fail(TradeError.ITEM_SLOT_CHANGED);
        }
        if (!current.itemId().equals(prepared.snapshot().itemId())) {
            return Outcome.fail(TradeError.ITEM_SLOT_CHANGED);
        }
        if (current.count() < prepared.count()) {
            return Outcome.fail(TradeError.ITEM_SLOT_CHANGED);
        }
        if (!nbtMatchesWithoutCount(current.nbt(), prepared.snapshot().nbt())) {
            return Outcome.fail(TradeError.ITEM_SLOT_CHANGED);
        }

        int remaining = current.count() - prepared.count();
        store.set(prepared.slot(), remaining > 0
                ? snapshotWithCount(current, remaining)
                : ItemSnapshot.empty());
        store.setChanged();

        // 托管快照：仅包含被托管数量对应的栈
        CompoundTag escrowedNbt = prepared.snapshot().nbt().copy();
        escrowedNbt.putByte("Count", (byte) prepared.count());
        ItemAsset asset = ItemAsset.create(owner, escrowedNbt);
        return Outcome.ok(new EscrowedItem(asset, prepared.snapshot()));
    }

    /**
     * 取消归还：把快照放回原槽。槽位已非空（被占用）则失败，由调用方转入收件箱。
     */
    public static Outcome<Void> cancel(PlayerInventoryStore store, PreparedItem prepared) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.slot() < 0 || prepared.slot() >= store.size()) {
            return Outcome.fail(TradeError.INVALID_ITEM_SLOT);
        }
        if (!store.get(prepared.slot()).isEmpty()) {
            return Outcome.fail(TradeError.ITEM_SLOT_CHANGED);
        }
        store.set(prepared.slot(), snapshotWithCount(prepared.snapshot(), prepared.count()));
        store.setChanged();
        return Outcome.ok(null);
    }

    /**
     * 交付：按偏好把资产放入背包，返回放入数量与剩余（剩余需转入收件箱）。
     * 重复交付幂等由调用方（收件箱状态机）保证；本方法只做放入。
     *
     * @param asset  资产（其 NBT 中的 Count 为托管数量）
     */
    public static DeliveryResult deliver(PlayerInventoryStore store, ItemAsset asset,
                                         DeliveryPreference.ItemDestination destination) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(destination, "destination");
        if (destination == DeliveryPreference.ItemDestination.INBOX
                || destination == DeliveryPreference.ItemDestination.ENDER_CHEST) {
            // 末影箱由调用方解析为对应容器后再交付；直接传入时按“不放入”处理（转入收件箱）
            return new DeliveryResult(0, assetCount(asset));
        }
        int total = assetCount(asset);
        if (total <= 0) {
            return new DeliveryResult(0, 0);
        }

        int remaining = total;
        String assetItemId = itemIdOf(asset);
        // 1) 合并到相同组件栈
        for (int slot = 0; slot < store.size() && remaining > 0; slot++) {
            ItemSnapshot cur = store.get(slot);
            if (cur.isEmpty()) {
                continue;
            }
            if (!cur.itemId().equals(assetItemId)) {
                continue;
            }
            if (!nbtMatchesWithoutCount(cur.nbt(), asset.stackNbt())) {
                continue;
            }
            int space = store.maxStack(slot) - cur.count();
            if (space <= 0) {
                continue;
            }
            int take = Math.min(space, remaining);
            store.set(slot, snapshotWithCount(cur, cur.count() + take));
            remaining -= take;
        }
        // 2) 放入空槽
        for (int slot = 0; slot < store.size() && remaining > 0; slot++) {
            ItemSnapshot cur = store.get(slot);
            if (!cur.isEmpty()) {
                continue;
            }
            int take = Math.min(store.maxStack(slot), remaining);
            store.set(slot, snapshotWithCount(asset.stackNbt(), take));
            remaining -= take;
        }
        if (remaining < total) {
            store.setChanged();
        }
        return new DeliveryResult(total - remaining, remaining);
    }

    // ------------------------------------------------------------------ 工具

    /** NBT 序列化大小（近似字节数），失败返回 0 */
    static int nbtSizeBytes(CompoundTag tag) {
        if (tag == null) {
            return 0;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            NbtIo.write(tag, new DataOutputStream(bytes));
        } catch (IOException e) {
            return 0;
        }
        return bytes.size();
    }

    /** 忽略 Count 后的 NBT 比较（组件级一致性） */
    static boolean nbtMatchesWithoutCount(CompoundTag a, CompoundTag b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        CompoundTag x = a.copy();
        x.remove("Count");
        CompoundTag y = b.copy();
        y.remove("Count");
        return x.equals(y);
    }

    /** 用新数量生成快照（保留原 NBT 组件） */
    static ItemSnapshot snapshotWithCount(ItemSnapshot snapshot, int count) {
        CompoundTag nbt = snapshot.nbt().copy();
        nbt.putByte("Count", (byte) count);
        return new ItemSnapshot(snapshot.itemId(), count, nbt);
    }

    /** 从资产 NBT + 新数量生成快照（放入空槽时用） */
    static ItemSnapshot snapshotWithCount(CompoundTag nbt, int count) {
        Objects.requireNonNull(nbt, "nbt");
        CompoundTag copy = nbt.copy();
        copy.putByte("Count", (byte) count);
        return new ItemSnapshot(copy.getString("id"), count, copy);
    }

    /** 从 ItemAsset 解码数量（NBT 缺失 Count 视为 1） */
    public static int assetCount(ItemAsset asset) {
        CompoundTag nbt = asset.stackNbt();
        if (nbt == null) {
            return 1;
        }
        return nbt.getByte("Count") & 0xFF;
    }

    /** 便捷：从 ItemAsset 解码物品 id（仅日志用） */
    static String itemIdOf(ItemAsset asset) {
        CompoundTag nbt = asset.stackNbt();
        return nbt == null ? "" : nbt.getString("id");
    }
}
