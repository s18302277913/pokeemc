package com.pokeemc.exchange.price;

import com.google.gson.JsonParser;
import com.poketrade.api.TradeItemId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OfficialPriceParserTest {

    private static final String WRAP = """
            {"interactions":{"values":[{"interactions":[{"results":{"value":[{"items":%s}]}}]}]}}
            """;

    private static String items(String inner) {
        return WRAP.formatted(inner);
    }

    @Test
    void parsesItems() {
        String json = items("""
                [
                  {"item": {"id": "pixelmon:poke_ball", "count": 1}, "buyPrice": 200.0, "sellPrice": 65.0},
                  {"item": {"id": "pixelmon:potion", "count": 1}, "buyPrice": 300.0, "sellPrice": 100.0}
                ]
                """);
        Map<TradeItemId, OfficialPriceParser.DoublePrice> out =
                OfficialPriceParser.parse(JsonParser.parseString(json));
        assertEquals(2, out.size());
        OfficialPriceParser.DoublePrice ball = out.get(TradeItemId.parse("pixelmon:poke_ball"));
        assertNotNull(ball);
        assertEquals(200.0, ball.buy(), 1e-9);
        assertEquals(65.0, ball.sell(), 1e-9);
    }

    @Test
    void ignoresMissingSellPrice() {
        String json = items("""
                [ {"item": {"id": "pixelmon:poke_ball", "count": 1}, "buyPrice": 200.0} ]
                """);
        Map<TradeItemId, OfficialPriceParser.DoublePrice> out =
                OfficialPriceParser.parse(JsonParser.parseString(json));
        assertNotNull(out.get(TradeItemId.parse("pixelmon:poke_ball")));
        assertEquals(0.0, out.get(TradeItemId.parse("pixelmon:poke_ball")).sell(), 1e-9);
    }

    @Test
    void ignoresNonPositivePrices() {
        String json = items("""
                [ {"item": {"id": "pixelmon:free_item", "count": 1}, "buyPrice": 0, "sellPrice": 0} ]
                """);
        assertTrue(OfficialPriceParser.parse(JsonParser.parseString(json)).isEmpty());
    }

    @Test
    void sellOnlyPriceCollected() {
        String json = items("""
                [ {"item": {"id": "pixelmon:free_item", "count": 1}, "buyPrice": 0, "sellPrice": 65.0} ]
                """);
        Map<TradeItemId, OfficialPriceParser.DoublePrice> out =
                OfficialPriceParser.parse(JsonParser.parseString(json));
        assertEquals(1, out.size());
        assertEquals(0.0, out.get(TradeItemId.parse("pixelmon:free_item")).buy(), 1e-9);
        assertEquals(65.0, out.get(TradeItemId.parse("pixelmon:free_item")).sell(), 1e-9);
    }

    @Test
    void negativePricesClampedToZero() {
        String json = items("""
                [ {"item": {"id": "pixelmon:weird", "count": 1}, "buyPrice": -5, "sellPrice": 10} ]
                """);
        Map<TradeItemId, OfficialPriceParser.DoublePrice> out =
                OfficialPriceParser.parse(JsonParser.parseString(json));
        assertEquals(1, out.size());
        assertEquals(0.0, out.get(TradeItemId.parse("pixelmon:weird")).buy(), 1e-9);
        assertEquals(10.0, out.get(TradeItemId.parse("pixelmon:weird")).sell(), 1e-9);
    }

    @Test
    void invalidItemIdSkipped() {
        String json = items("""
                [
                  {"item": {"id": "not-a-valid-id", "count": 1}, "buyPrice": 100, "sellPrice": 10},
                  {"item": {"id": "pixelmon:poke_ball", "count": 1}, "buyPrice": 200, "sellPrice": 65}
                ]
                """);
        Map<TradeItemId, OfficialPriceParser.DoublePrice> out =
                OfficialPriceParser.parse(JsonParser.parseString(json));
        assertEquals(1, out.size());
        assertTrue(out.containsKey(TradeItemId.parse("pixelmon:poke_ball")));
    }

    @Test
    void duplicateItemIdKeepsFirstPrice() {
        String json = items("""
                [
                  {"item": {"id": "pixelmon:poke_ball", "count": 1}, "buyPrice": 200.0, "sellPrice": 65.0},
                  {"item": {"id": "pixelmon:poke_ball", "count": 1}, "buyPrice": 999.0, "sellPrice": 888.0}
                ]
                """);
        Map<TradeItemId, OfficialPriceParser.DoublePrice> out =
                OfficialPriceParser.parse(JsonParser.parseString(json));
        assertEquals(1, out.size());
        assertEquals(200.0, out.get(TradeItemId.parse("pixelmon:poke_ball")).buy(), 1e-9);
    }

    @Test
    void malformedRootsYieldEmpty() {
        assertTrue(OfficialPriceParser.parse(null).isEmpty());
        assertTrue(OfficialPriceParser.parse(JsonParser.parseString("[]")).isEmpty());
        assertTrue(OfficialPriceParser.parse(JsonParser.parseString("{}")).isEmpty());
        assertTrue(OfficialPriceParser.parse(JsonParser.parseString("{\"interactions\":\"nope\"}")).isEmpty());
        assertTrue(OfficialPriceParser.parse(JsonParser.parseString("{\"interactions\":{}}")).isEmpty());
        assertTrue(OfficialPriceParser.parse(JsonParser.parseString(items("[42]"))).isEmpty());
    }

    @Test
    void emptyItemsYieldsEmptyMap() {
        assertTrue(OfficialPriceParser.parse(JsonParser.parseString(items("[]"))).isEmpty());
    }
}
