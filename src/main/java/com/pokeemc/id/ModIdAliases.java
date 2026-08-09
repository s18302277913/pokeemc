package com.pokeemc.id;

import com.poketrade.api.id.PokeTradeIds;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModIdAliases {
    public static Map<String, String> blockAliases() {
        return aliases(PokeTradeIds.BLOCK_PATHS);
    }

    public static Map<String, String> itemAliases() {
        return aliases(PokeTradeIds.ITEM_PATHS);
    }

    public static Map<String, String> blockEntityAliases() {
        return aliases(PokeTradeIds.BLOCK_ENTITY_PATHS);
    }

    public static Map<String, String> menuAliases() {
        return aliases(PokeTradeIds.MENU_PATHS);
    }

    public static Map<String, String> allAliases() {
        Map<String, String> result = new LinkedHashMap<>();
        result.putAll(blockAliases());
        result.putAll(itemAliases());
        result.putAll(blockEntityAliases());
        result.putAll(menuAliases());
        return Map.copyOf(result);
    }

    private static Map<String, String> aliases(List<String> paths) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String path : paths) {
            result.put(PokeTradeIds.legacy(path).toString(), PokeTradeIds.current(path).toString());
        }
        return Map.copyOf(result);
    }

    private ModIdAliases() {
    }
}
