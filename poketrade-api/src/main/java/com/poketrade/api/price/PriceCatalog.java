package com.poketrade.api.price;

import com.poketrade.api.TradeItemId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** 不可变商品价格目录。构造时按规范固定默认顺序；排序经 {@link #sorted} 返回新列表。 */
public final class PriceCatalog {

    private final List<PriceCatalogEntry> entries;
    private final TreeMap<TradeItemId, PriceCatalogEntry> byId;
    private final List<String> categories;

    public PriceCatalog(List<PriceCatalogEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        List<PriceCatalogEntry> copy = new ArrayList<>(entries);
        // 检测重复 itemId：目录 id 必须唯一，否则 byId 索引会静默覆盖，导致 entries 与 byId 不一致。
        Set<TradeItemId> seen = new HashSet<>();
        for (PriceCatalogEntry e : copy) {
            TradeItemId id = e.quote().itemId();
            if (!seen.add(id)) {
                throw new IllegalArgumentException("duplicate item id: " + id);
            }
        }
        // 默认顺序：类别 -> 子类 -> 稀有度（降序，稀有优先）-> 名称（TradeItemId.toString 字典序）。
        // 稀有度取降序：同类内高稀有度（如 uncommon）排在低稀有度（如 common）之前，符合交易所展示直觉。
        copy.sort(categoryOrder());
        this.entries = List.copyOf(copy);
        TreeMap<TradeItemId, PriceCatalogEntry> map = new TreeMap<>(Comparator.comparing(TradeItemId::toString));
        for (PriceCatalogEntry e : copy) {
            map.put(e.quote().itemId(), e);
        }
        this.byId = map;
        LinkedHashSet<String> cats = new LinkedHashSet<>();
        for (PriceCatalogEntry e : copy) {
            cats.add(e.category().isEmpty() ? "unknown" : e.category());
        }
        this.categories = List.copyOf(cats);
    }

    public static PriceCatalog empty() {
        return new PriceCatalog(List.of());
    }

    public List<PriceCatalogEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public Optional<PriceQuote> quoteOf(TradeItemId itemId) {
        PriceCatalogEntry e = byId.get(itemId);
        return Optional.ofNullable(e).map(PriceCatalogEntry::quote);
    }

    /** 全部分类（按默认顺序去重，未分类归入 "unknown"）。 */
    public List<String> categories() {
        return categories;
    }

    public List<PriceCatalogEntry> filterByCategory(String category) {
        if (category == null || category.isBlank()) {
            return entries;
        }
        String cat = category.trim();
        return entries.stream()
                .filter(e -> (e.category().isEmpty() ? "unknown" : e.category()).equals(cat))
                .toList();
    }

    /** 按指定排序返回新列表（不修改内部顺序）。 */
    public List<PriceCatalogEntry> sorted(PriceSort sort) {
        List<PriceCatalogEntry> out = new ArrayList<>(entries);
        switch (sort) {
            case PRICE_ASC -> out.sort(Comparator
                    .comparingLong((PriceCatalogEntry e) -> buyOrSell(e))
                    .thenComparing(e -> e.quote().itemId().toString()));
            case PRICE_DESC -> out.sort(Comparator
                    .comparingLong((PriceCatalogEntry e) -> buyOrSell(e))
                    .reversed()
                    // 仅价格维度反转，价格相同条目仍按名称升序（thenComparing 不受 reversed 影响）
                    .thenComparing(e -> e.quote().itemId().toString()));
            case NAME -> out.sort(Comparator.comparing(e -> e.quote().itemId().toString()));
            case MOD -> out.sort(Comparator
                    .comparing(PriceCatalogEntry::modId)
                    .thenComparing(e -> e.quote().itemId().toString()));
            case CATEGORY -> out.sort(categoryOrder());
        }
        return out;
    }

    /** 类别规范顺序：类别 -> 子类 -> 稀有度（降序，稀有优先）-> 名称。 */
    private static Comparator<PriceCatalogEntry> categoryOrder() {
        return Comparator
                .comparing(PriceCatalogEntry::category)
                .thenComparing(PriceCatalogEntry::subcategory)
                .thenComparing(Comparator.comparing(PriceCatalogEntry::rarity).reversed())
                .thenComparing(e -> e.quote().itemId().toString());
    }

    // 排序价 = 买价；不可购买（买价 0）时取卖价
    private static long buyOrSell(PriceCatalogEntry e) {
        return e.quote().buyAvailable() ? e.quote().buyPrice() : e.quote().sellPrice();
    }
}
