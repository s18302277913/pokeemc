package com.poketrade.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiModuleSmokeTest {
    @Test
    void exposesCurrentApiVersion() {
        assertEquals(1, PokeTradeApi.API_VERSION);
    }
}
