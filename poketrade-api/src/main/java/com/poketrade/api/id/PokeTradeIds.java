package com.poketrade.api.id;

import com.poketrade.api.TradeItemId;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PokeTradeIds {
    public static final String CURRENT_NAMESPACE = "poketrade";
    public static final String LEGACY_NAMESPACE = "pokeemc";
    public static final List<String> BLOCK_PATHS = List.of("transmutation_table", "condenser");
    public static final List<String> ITEM_PATHS = List.of("transmutation_table", "condenser", "alchemical_coal", "mobius_fuel", "aeternalis_fuel");
    public static final List<String> BLOCK_ENTITY_PATHS = BLOCK_PATHS;
    public static final List<String> MENU_PATHS = BLOCK_PATHS;
    public static final Set<String> MIGRATABLE_PATHS = Set.of("transmutation_table", "condenser", "alchemical_coal", "mobius_fuel", "aeternalis_fuel");

    public static Set<String> allApprovedPaths() {
        Set<String> paths = new HashSet<>();
        paths.addAll(BLOCK_PATHS);
        paths.addAll(ITEM_PATHS);
        paths.addAll(BLOCK_ENTITY_PATHS);
        paths.addAll(MENU_PATHS);
        return Set.copyOf(paths);
    }

    public static TradeItemId current(String path) {
        return new TradeItemId(CURRENT_NAMESPACE, path);
    }

    public static TradeItemId legacy(String path) {
        return new TradeItemId(LEGACY_NAMESPACE, path);
    }

    private PokeTradeIds() {
    }
}
