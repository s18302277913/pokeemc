package com.pokeemc.trade.network;

/**
 * 网络层硬上限常量（计划 5.1）：全部 C2S handler 在解码后先做边界检查，
 * 越界输入返回稳定错误码，不进入 TradeService。
 */
public final class TradePacketLimits {

    private TradePacketLimits() {
    }

    /** 目录搜索词最大长度 */
    public static final int MAX_SEARCH_LENGTH = 64;

    /** 目录页最大条目数 */
    public static final int MAX_DIRECTORY_PAGE_SIZE = 50;

    /** 资产页最大条目数 */
    public static final int MAX_ASSET_PAGE_SIZE = 54;

    /** 物品报价背包槽位上限（覆盖物品 0..35 + 盔甲 36..39 + 副手 40） */
    public static final int MAX_INVENTORY_SLOT = 40;

    /** 物品报价数量上限（防御性：不超过 2 组） */
    public static final int MAX_ITEM_COUNT = 127;

    /** PKM 报价金额上限（防御性，服务层有更精确校验） */
    public static final long MAX_PKM_AMOUNT = 1_000_000_000L;

    /** 队伍槽位上限（0..5） */
    public static final int MAX_PARTY_SLOT = 5;

    /** PC 箱号上限（防御性） */
    public static final int MAX_PC_BOX = 255;

    /** PC 箱内槽位上限（防御性） */
    public static final int MAX_PC_SLOT = 255;

    /** 页码上限（防御性，防整数膨胀） */
    public static final int MAX_PAGE_NUMBER = 10_000;

    /** 请求结果缓存容量（最近 N 个 requestId -> result） */
    public static final int REQUEST_CACHE_SIZE = 128;

    // ---- 每玩家每秒速率限制（计划 5.1） ----

    /** 创建/确认：2 次/秒 */
    public static final int RATE_CREATE_OR_CONFIRM_PER_SECOND = 2;

    /** 报价变更（托管/移除/偏好）：10 次/秒 */
    public static final int RATE_OFFER_CHANGE_PER_SECOND = 10;

    /** 目录/资产翻页：5 次/秒 */
    public static final int RATE_PAGE_PER_SECOND = 5;
}
