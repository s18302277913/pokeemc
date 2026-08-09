package com.poketrade.api.id;

import com.poketrade.api.TradeItemId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryIdMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "transmutation_table",
            "condenser",
            "alchemical_coal",
            "mobius_fuel",
            "aeternalis_fuel"
    })
    void migratesEveryApprovedLegacyId(String path) {
        assertEquals(PokeTradeIds.current(path), RegistryIdMigration.toCurrent(PokeTradeIds.legacy(path)), path);
    }

    @Test
    void migratablePathsEqualTheUnionOfAllPublishedPathCollections() {
        Set<String> union = new HashSet<>();
        union.addAll(PokeTradeIds.BLOCK_PATHS);
        union.addAll(PokeTradeIds.ITEM_PATHS);
        union.addAll(PokeTradeIds.BLOCK_ENTITY_PATHS);
        union.addAll(PokeTradeIds.MENU_PATHS);
        assertEquals(PokeTradeIds.MIGRATABLE_PATHS, union);
        assertEquals(PokeTradeIds.MIGRATABLE_PATHS, PokeTradeIds.allApprovedPaths());
    }

    @Test
    void keepsPublishedRegistryPathCollectionsConsistentWithTheMigrationWhitelist() {
        assertEquals(PokeTradeIds.allApprovedPaths(), PokeTradeIds.MIGRATABLE_PATHS);
        assertEquals(PokeTradeIds.MIGRATABLE_PATHS, Set.copyOf(PokeTradeIds.ITEM_PATHS));
        assertEquals(PokeTradeIds.ITEM_PATHS.size(), Set.copyOf(PokeTradeIds.ITEM_PATHS).size());
        assertTrue(PokeTradeIds.ITEM_PATHS.containsAll(PokeTradeIds.BLOCK_PATHS));
        assertEquals(PokeTradeIds.BLOCK_PATHS, PokeTradeIds.BLOCK_ENTITY_PATHS);
        assertEquals(PokeTradeIds.BLOCK_PATHS, PokeTradeIds.MENU_PATHS);
    }

    @Test
    void leavesCurrentForeignAndUnknownLegacyIdsUnchanged() {
        TradeItemId current = TradeItemId.parse("poketrade:condenser");
        TradeItemId foreign = TradeItemId.parse("minecraft:diamond");
        TradeItemId unknownLegacy = TradeItemId.parse("pokeemc:not_approved");

        assertSame(current, RegistryIdMigration.toCurrent(current));
        assertSame(foreign, RegistryIdMigration.toCurrent(foreign));
        assertSame(unknownLegacy, RegistryIdMigration.toCurrent(unknownLegacy));
    }

    @Test
    void parsesIdsAtTheAcceptedCharacterBoundaries() {
        assertEquals(new TradeItemId("a", "b"), TradeItemId.parse("a:b"));
        assertEquals(
                new TradeItemId("a", "b.c-d_e/f"),
                TradeItemId.parse("a:b.c-d_e/f")
        );
        assertEquals(
                new TradeItemId("namespace.with-dash_1", "path/with.dot-dash_1"),
                TradeItemId.parse("namespace.with-dash_1:path/with.dot-dash_1")
        );
    }

    @Test
    void rejectsNullAndMalformedIdsAtParsingBoundaries() {
        List<String> malformed = List.of(
                "",
                ":",
                ":path",
                "namespace:",
                "a:b:c",
                "namespace:path:extra",
                "missing_separator",
                "NameSpace:path",
                "namespace:Path",
                "namespace:bad path",
                "namespace:bad\\path"
        );

        assertThrows(NullPointerException.class, () -> TradeItemId.parse(null));
        assertThrows(NullPointerException.class, () -> new TradeItemId(null, "path"));
        assertThrows(NullPointerException.class, () -> new TradeItemId("namespace", null));
        for (String value : malformed) {
            assertThrows(IllegalArgumentException.class, () -> TradeItemId.parse(value), value);
        }
    }

    @Test
    void roundTripsEveryPublishedAndBoundaryId() {
        for (String path : PokeTradeIds.allApprovedPaths()) {
            assertRoundTrip(PokeTradeIds.current(path));
            assertRoundTrip(PokeTradeIds.legacy(path));
        }
        assertRoundTrip(new TradeItemId("a", "b"));
        assertRoundTrip(new TradeItemId("a", "b.c-d_e/f"));
        assertRoundTrip(new TradeItemId("namespace.with-dash_1", "path/with.dot-dash_1"));
    }

    private static void assertRoundTrip(TradeItemId id) {
        assertEquals(id, TradeItemId.parse(id.toString()), id.toString());
    }
}
