package com.pokeemc.storage;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageKeyTest {

    private static final String DIM = "minecraft:overworld";
    private static final String ADAPTER = "vanilla_chest";
    private static final String LOCATION = "12;64;-8";

    @Test
    void validKeyIsAcceptedAndRoundTrips() {
        StorageKey key = StorageKey.of(DIM, ADAPTER, LOCATION);
        assertEquals(DIM, key.dimension());
        assertEquals(ADAPTER, key.adapterType());
        assertEquals(LOCATION, key.location());
        assertEquals(Optional.of(key), StorageKey.parse(key.asString()));
        assertEquals(DIM + "|" + ADAPTER + "|" + LOCATION, key.asString());
    }

    @Test
    void ofNormalizesLocationWhitespace() {
        StorageKey key = StorageKey.of(DIM, ADAPTER, "  12;64;-8  ");
        assertEquals(LOCATION, key.location());
    }

    @Test
    void invalidDimensionRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey("Overworld", ADAPTER, LOCATION));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey("minecraft", ADAPTER, LOCATION));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey("minecraft:", ADAPTER, LOCATION));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey("minecraft:over world", ADAPTER, LOCATION));
    }

    @Test
    void invalidAdapterTypeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey(DIM, "Vanilla_Chest", LOCATION));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey(DIM, "vanilla/chest", LOCATION));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey(DIM, "", LOCATION));
    }

    @Test
    void invalidLocationRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey(DIM, ADAPTER, "0 64 0"));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey(DIM, ADAPTER, "0|64|0"));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey(DIM, ADAPTER, ""));
    }

    @Test
    void parseReturnsEmptyOnMalformedInput() {
        assertTrue(StorageKey.parse(null).isEmpty());
        assertTrue(StorageKey.parse("").isEmpty());
        assertTrue(StorageKey.parse("a").isEmpty());
        assertTrue(StorageKey.parse("a|b").isEmpty());
        assertTrue(StorageKey.parse("a|b|c|d").isEmpty());
        assertTrue(StorageKey.parse(DIM + "|" + ADAPTER + "|bad loc").isEmpty());
        assertTrue(StorageKey.parse("BadDim|" + ADAPTER + "|" + LOCATION).isEmpty());
    }

    @Test
    void nullComponentsRejected() {
        assertThrows(NullPointerException.class,
                () -> new StorageKey(null, ADAPTER, LOCATION));
        assertThrows(NullPointerException.class,
                () -> new StorageKey(DIM, null, LOCATION));
        assertThrows(NullPointerException.class,
                () -> new StorageKey(DIM, ADAPTER, null));
    }
}
