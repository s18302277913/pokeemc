package com.poketrade.api.price;

/** 商品目录排序方式（交易所排序按钮循环切换）。 */
public enum PriceSort {
    /** 默认：类别、子类、稀有度（稀有优先）、名称。 */
    CATEGORY,
    PRICE_ASC,
    PRICE_DESC,
    NAME,
    MOD
}
