package com.pokeemc.trade.model;

/**
 * 本人资产概览页类型（计划 5.1/5.2）。
 * 客户端只能请求自己的库存/钱包/队伍/PC，不存在"查看对手资产页"的协议分支。
 */
public enum AssetPageKind {
    /** 可交易物品（背包槽位，分页） */
    ITEMS,
    /** 钱包 PKM 总余额（单条目） */
    PKM,
    /** 队伍宝可梦（固定 6 格） */
    PARTY,
    /** PC 宝可梦（单箱一页） */
    PC
}
