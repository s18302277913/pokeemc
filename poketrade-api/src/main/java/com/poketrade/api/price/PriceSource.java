package com.poketrade.api.price;

/** 价格来源：官方商店同步 / 数据包显式覆盖 / PKM 价值兜底。 */
public enum PriceSource {
    /** 来自 Pixelmon shopkeeper 预设（经倍率换算后）。 */
    OFFICIAL,
    /** 来自 poketrade 覆盖数据包或内置规则（如大师球）。 */
    OVERRIDE,
    /** 来自 PKM（EMC 价值体系）兜底：无官方/覆盖价的可回收物品，仅提供回收价。 */
    PKM
}
