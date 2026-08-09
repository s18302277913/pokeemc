package com.poketrade.api.storage;

/**
 * 适配器能力声明。适配器只能声明实际可保证的能力。
 *
 * <ul>
 *   <li>{@link #SNAPSHOT}：可读取槽位内容快照。</li>
 *   <li>{@link #INSERT}：可向仓储写入物品。</li>
 *   <li>{@link #EXTRACT}：可从仓储取出物品。</li>
 *   <li>{@link #SELL_SOURCE}：可作为出售来源；无此能力时即使 ACL 有 SELL 也不得出售。</li>
 *   <li>{@link #AUTOMATION}：允许自动化插入/抽取。</li>
 *   <li>{@link #MULTI_BLOCK}：多方块容器，提供稳定规范键与所有物理部件。</li>
 * </ul>
 */
public enum StorageCapability {
    SNAPSHOT,
    INSERT,
    EXTRACT,
    SELL_SOURCE,
    AUTOMATION,
    MULTI_BLOCK
}
