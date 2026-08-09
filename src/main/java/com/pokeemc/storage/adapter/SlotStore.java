package com.pokeemc.storage.adapter;

/**
 * 槽位数据访问抽象，将 Minecraft {@code Container} 的读取/写入桥接为纯 Java 接口。
 *
 * <p>让 {@link StorageHandleImpl} 的 simulate/commit 逻辑与游戏类解耦，可在 JVM 单测中
 * 用假实现直接驱动，同时保持游戏侧 {@link MinecraftSlotStore} 只是薄适配。</p>
 */
public interface SlotStore {

    /** 槽位总数。 */
    int size();

    /** 槽位物品 ID（注册表命名空间:id）；空槽返回 {@code null}。 */
    String itemId(int slot);

    /** 槽位当前数量；空槽为 0。 */
    int count(int slot);

    /** 将 {@code itemId} 放入该槽的最大堆叠数（槽内有物品时以其上限为准）。 */
    int maxStack(int slot, String itemId);

    /** 槽位内容指纹，用于事务冲突校验；空槽返回 0。 */
    long fingerprint(int slot);

    /** 提交写入：设置槽位为 {@code itemId}×{@code count}（{@code count<=0} 或 itemId 为 null 时清空）。 */
    void set(int slot, String itemId, int count);

    /** 标记底层容器已变更，需持久化/广播。 */
    void setChanged();
}
