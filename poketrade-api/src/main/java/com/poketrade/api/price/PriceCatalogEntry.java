package com.poketrade.api.price;

import java.util.Objects;

/** 商品目录条目：报价 + 展示维度（分类/子类/稀有度/来源模组）。 */
public record PriceCatalogEntry(PriceQuote quote, String category, String subcategory, String rarity, String modId) {
    public PriceCatalogEntry {
        Objects.requireNonNull(quote, "quote");
        category = Objects.requireNonNullElse(category, "").trim();
        subcategory = Objects.requireNonNullElse(subcategory, "").trim();
        rarity = Objects.requireNonNullElse(rarity, "").trim();
        modId = (modId == null || modId.isBlank()) ? quote.itemId().namespace() : modId.trim();
    }
}
