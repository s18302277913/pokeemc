package com.pokeemc.trade.model;

/**
 * 稳定交易错误码。客户端按枚举本地化展示，服务端不得向客户端发送任意异常文本。
 * 每个错误码在 zh_cn/en_us 语言文件中有对应翻译键 {@code poketrade.trade.error.<name>}。
 */
public enum TradeError {

    /** 操作成功 */
    NONE,

    /** 目标玩家不存在或不在线 */
    TARGET_OFFLINE,

    /** 不能与自己交易 */
    SELF_TRADE,

    /** 玩家已有活动交易 */
    ALREADY_IN_TRADE,

    /** 邀请已存在（同一玩家已收到/已发出邀请） */
    INVITE_ALREADY_PENDING,

    /** 不是该交易的参与者 */
    NOT_PARTICIPANT,

    /** 交易不存在或已结束 */
    TRADE_NOT_FOUND,

    /** 状态不允许该操作 */
    INVALID_STATE,

    /** revision 过期，客户端需刷新 */
    STALE_REVISION,

    /** 物品槽位无效或为空 */
    INVALID_ITEM_SLOT,

    /** 槽位内容在校验后变化（prepare 与 remove 之间被修改） */
    ITEM_SLOT_CHANGED,

    /** 数量越界或非法 */
    INVALID_COUNT,

    /** 物品黑名单拒绝 */
    ITEM_BLACKLISTED,

    /** 物品不可序列化 */
    ITEM_UNSERIALIZABLE,

    /** 物品 NBT 超出上限 */
    ITEM_NBT_TOO_LARGE,

    /** PKM 余额不足 */
    PKM_INSUFFICIENT_BALANCE,

    /** PKM 金额越界（0/负数/超上限） */
    PKM_INVALID_AMOUNT,

    /** PKM 借记失败 */
    PKM_DEBIT_FAILED,

    /** 钱包后端不支持幂等事务，PKM 交易被禁用 */
    PKM_ESCROW_UNSUPPORTED,

    /** 宝可梦位置为空 */
    POKEMON_SLOT_EMPTY,

    /** 宝可梦不可交易 */
    POKEMON_UNTRADEABLE,

    /** 宝可梦位置已变化（UUID 不匹配） */
    POKEMON_MOVED,

    /** 宝可梦参战/放出/临时队伍中 */
    POKEMON_BUSY,

    /** 不能移出最后一只可用宝可梦 */
    POKEMON_LAST_PARTY,

    /** 宝可梦已存在其他交易托管中 */
    POKEMON_ALREADY_ESCROWED,

    /** 资产不在本人报价中 */
    ASSET_NOT_OWNED,

    /** 报价条目超限（27 物品栈 / 1 PKM / 6 宝可梦） */
    OFFER_LIMIT_REACHED,

    /** 交易已过期 */
    TRADE_EXPIRED,

    /** 目标位置/交付失败 */
    DELIVERY_FAILED,

    /** 手续费 quote 过期或与当前 revision 不符 */
    FEE_QUOTE_INVALID,

    /** 手续费预留失败 */
    FEE_RESERVE_FAILED,

    /** 能力不可用（忙碌/离线/禁用等） */
    CAPABILITY_UNAVAILABLE,

    /** 请求过于频繁 */
    RATE_LIMITED,

    /** 请求 id 重复 */
    DUPLICATE_REQUEST,

    /** 输入越界或格式非法 */
    INVALID_INPUT,

    /** 需要管理员介入 */
    REQUIRES_ADMIN;

    public String translationKey() {
        return "poketrade.trade.error." + name().toLowerCase();
    }
}
