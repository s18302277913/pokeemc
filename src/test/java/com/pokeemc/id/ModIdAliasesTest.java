package com.pokeemc.id;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModIdAliasesTest {
    @Test
    void coversEveryApprovedPersistentEntry() {
        assertEquals(2, ModIdAliases.blockAliases().size());
        assertEquals(5, ModIdAliases.itemAliases().size());
        assertEquals(2, ModIdAliases.blockEntityAliases().size());
        assertEquals(2, ModIdAliases.menuAliases().size());
        assertEquals("poketrade:condenser", ModIdAliases.blockAliases().get("pokeemc:condenser"));
    }

    @Test
    void mapsOnlyLegacyIdsToCurrentIds() {
        ModIdAliases.allAliases().forEach((legacy, current) -> {
            assertTrue(legacy.startsWith("pokeemc:"));
            assertTrue(current.startsWith("poketrade:"));
        });
    }
}
