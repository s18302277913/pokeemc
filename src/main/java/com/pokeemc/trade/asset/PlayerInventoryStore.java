package com.pokeemc.trade.asset;

/**
 * 玩家背包槽位存储抽象（与 {@link com.pokeemc.storage.adapter.SlotStore} 同构）。
 * <p>
 * 将 Minecraft {@code Inventory/Container} 的读取/写入桥接为纯 Java 接口，
 * 使 ItemEscrowGateway 的 prepare/remove/deliver 逻辑可在 JVM 单测中驱动，
 * 游戏侧 {@link MinecraftPlayerInventoryStore} 只是薄适配。
 */
public interface PlayerInventoryStore {

    /** 槽位总数 */
    int size();

    /** 槽位快照（空槽返回 {@link ItemSnapshot#empty()}） */
    ItemSnapshot get(int slot);

    /** 覆盖槽位（空快照即清空）；越界抛 IllegalArgumentException */
    void set(int slot, ItemSnapshot snapshot);

    /** 槽位物品最大堆叠（槽内物品存在时以其上限为准） */
    int maxStack(int slot);

    /** 背包是否已满（无可容纳新物品的空槽/可合并槽） */
    boolean isFull();

    /** 标记底层容器已变更，需同步/持久化 */
    void setChanged();
}
