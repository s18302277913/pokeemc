package com.pokeemc.storage;

/**
 * 仓储的六项独立权限。
 *
 * <p>权限必须逐项判定，禁止使用 ordinal、整数等级或“高权限自动包含低权限”
 * 的比较。任一权限都不隐含其他权限。</p>
 *
 * <ul>
 *   <li>{@link #VIEW}：查看仓储元数据与槽位内容，并打开只读视图。</li>
 *   <li>{@link #DEPOSIT}：向仓储写入物品；不隐含 {@code VIEW} 或 {@code WITHDRAW}。</li>
 *   <li>{@link #WITHDRAW}：从仓储取出物品；不隐含 {@code VIEW}、{@code SELL} 或 {@code BREAK}。</li>
 *   <li>{@link #SELL}：以仓储中的物品为出售来源；不隐含 {@code WITHDRAW}。</li>
 *   <li>{@link #BREAK}：破坏或移动仓储方块；不隐含 {@code MANAGE}。</li>
 *   <li>{@link #MANAGE}：修改名称、ACL、模板、自动化设置和范围可见性；不隐含其他权限。</li>
 * </ul>
 */
public enum StoragePermission {
    VIEW,
    DEPOSIT,
    WITHDRAW,
    SELL,
    BREAK,
    MANAGE
}
