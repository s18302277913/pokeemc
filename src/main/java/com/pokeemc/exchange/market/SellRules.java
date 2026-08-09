package com.pokeemc.exchange.market;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pokeemc.config.PokeTradeConfig;
import com.poketrade.api.TradeItemId;

import java.util.HashSet;
import java.util.Set;

/**
 * 交易所出售规则（{@code data/poketrade/exchange/sell_rules.json}）：
 * 黑名单、白名单（非空才启用）与「贵重物品二次确认」阈值。
 */
public final class SellRules {

    public static final SellRules DEFAULT = new SellRules(Set.of(), Set.of(), 0L);

    private static volatile SellRules current = DEFAULT;

    private final Set<TradeItemId> blacklist;
    private final Set<TradeItemId> whitelist; // 空 = 不启用白名单
    private final long requireConfirmValue;

    public SellRules(Set<TradeItemId> blacklist, Set<TradeItemId> whitelist, long requireConfirmValue) {
        this.blacklist = Set.copyOf(blacklist);
        this.whitelist = Set.copyOf(whitelist);
        this.requireConfirmValue = requireConfirmValue;
    }

    public static SellRules current() {
        return current;
    }

    public static void apply(SellRules rules) {
        current = rules == null ? DEFAULT : rules;
    }

    /** 黑名单（不可出售的 id 集合）。 */
    public Set<TradeItemId> blacklist() {
        return blacklist;
    }

    /** 白名单（非空才启用；为空表示不限制）。 */
    public Set<TradeItemId> whitelist() {
        return whitelist;
    }

    /** 白名单是否启用（非空即启用，与 {@link #canSell} 的语义一致）。 */
    public boolean allowlistEnabled() {
        return !whitelist.isEmpty();
    }

    public static SellRules parse(JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            return DEFAULT;
        }
        JsonObject o = root.getAsJsonObject();
        Set<TradeItemId> black = parseIds(o, "sellBlacklist");
        Set<TradeItemId> white = parseIds(o, "sellWhitelist");
        long confirm = o.has("requireConfirmValue") && o.get("requireConfirmValue").isJsonPrimitive()
                ? o.get("requireConfirmValue").getAsLong()
                : PokeTradeConfig.exchangeSellConfirmValue();
        return new SellRules(black, white, confirm);
    }

    private static Set<TradeItemId> parseIds(JsonObject o, String key) {
        Set<TradeItemId> out = new HashSet<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray(key)) {
                try {
                    out.add(TradeItemId.parse(el.getAsString()));
                } catch (RuntimeException ignored) {
                    // 非法 id 忽略
                }
            }
        }
        return out;
    }

    /**
     * 判定物品是否可出售：黑名单始终拦截；白名单仅在非空时收紧（空 = 不限制）。
     * 黑名单优先于白名单——即使物品在白名单内，也在黑名单时同样被拦截。
     */
    public boolean canSell(TradeItemId id) {
        if (blacklist.contains(id)) {
            return false;
        }
        return whitelist.isEmpty() || whitelist.contains(id);
    }

    public long requireConfirmValue() {
        return requireConfirmValue;
    }
}
