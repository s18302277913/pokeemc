package com.poketrade.api.price;

import com.poketrade.api.TradeItemId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PriceCatalogTest {

    private static final TradeItemId BALL = TradeItemId.parse("pixelmon:poke_ball");
    private static final TradeItemId POTION = TradeItemId.parse("pixelmon:potion");
    private static final TradeItemId GREATER = TradeItemId.parse("pixelmon:great_ball");

    private static PriceCatalogEntry entry(TradeItemId id, long buy, long sell, String cat, String rarity) {
        return new PriceCatalogEntry(
                PriceQuote.of(id, buy, sell, PriceSource.OFFICIAL), cat, "", rarity, id.namespace());
    }

    @Test
    void quoteRejectsZeroBoth() {
        assertThrows(IllegalArgumentException.class,
                () -> PriceQuote.of(BALL, 0, 0, PriceSource.OFFICIAL));
    }

    @Test
    void duplicateIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new PriceCatalog(List.of(
                        entry(BALL, 2000, 650, "Poké Balls", "common"),
                        entry(BALL, 3000, 1000, "Medicine", "common"))));
    }

    @Test
    void sortByName() {
        PriceCatalog c = new PriceCatalog(List.of(
                entry(BALL, 2000, 650, "Poké Balls", "common"),
                entry(POTION, 3000, 1000, "Medicine", "common"),
                entry(GREATER, 6000, 2000, "Poké Balls", "uncommon")));
        List<PriceCatalogEntry> byName = c.sorted(PriceSort.NAME);
        // TradeItemId.toString 字典序：great_ball < poke_ball < potion
        assertEquals(GREATER, byName.get(0).quote().itemId());
        assertEquals(BALL, byName.get(1).quote().itemId());
        assertEquals(POTION, byName.get(2).quote().itemId());
    }

    @Test
    void priceDescTiesByNameAscending() {
        // 买价相同（2000）时 PRICE_DESC 仅反转价格维度，名称字典序小者（great_ball）在前
        PriceCatalog c = new PriceCatalog(List.of(
                entry(BALL, 2000, 650, "Poké Balls", "common"),
                entry(GREATER, 2000, 800, "Poké Balls", "uncommon")));
        List<PriceCatalogEntry> byPriceDesc = c.sorted(PriceSort.PRICE_DESC);
        assertEquals(GREATER, byPriceDesc.get(0).quote().itemId());
        assertEquals(BALL, byPriceDesc.get(1).quote().itemId());
    }

    @Test
    void sortUsesSellWhenBuyUnavailable() {
        // buy=0 的条目按卖价参与价格排序：排序价 1000（卖价）应排在买价 500 的条目之后
        PriceCatalog c = new PriceCatalog(List.of(
                entry(BALL, 500, 100, "Poké Balls", "common"),
                entry(GREATER, 0, 1000, "Poké Balls", "uncommon")));
        List<PriceCatalogEntry> byPriceAsc = c.sorted(PriceSort.PRICE_ASC);
        assertEquals(BALL, byPriceAsc.get(0).quote().itemId());
        assertEquals(GREATER, byPriceAsc.get(1).quote().itemId());
    }

    @Test
    void quoteAllowsSellZero() {
        PriceQuote q = PriceQuote.of(BALL, 2000, 0, PriceSource.OFFICIAL);
        assertEquals(2000, q.buyPrice());
        assertEquals(0, q.sellPrice());
        assertFalse(q.sellAvailable());
        assertTrue(q.buyAvailable());
    }

    @Test
    void quoteOfHits() {
        PriceCatalog c = new PriceCatalog(List.of(
                entry(BALL, 2000, 650, "Poké Balls", "common")));
        Optional<PriceQuote> q = c.quoteOf(BALL);
        assertTrue(q.isPresent());
        assertEquals(2000, q.get().buyPrice());
    }

    @Test
    void quoteOfMissing() {
        PriceCatalog c = new PriceCatalog(List.of(entry(BALL, 2000, 650, "Poké Balls", "common")));
        assertTrue(c.quoteOf(POTION).isEmpty());
    }

    @Test
    void sortByNameAndCategory() {
        PriceCatalog c = new PriceCatalog(List.of(
                entry(GREATER, 6000, 2000, "Poké Balls", "uncommon"),
                entry(POTION, 3000, 1000, "Medicine", "common"),
                entry(BALL, 2000, 650, "Poké Balls", "common")));
        List<PriceCatalogEntry> byCat = c.sorted(PriceSort.CATEGORY);
        assertEquals("Medicine", byCat.get(0).category());
        assertEquals("Poké Balls", byCat.get(1).category());
        // 同类内按子类、稀有度、名称稳定排序：great_ball 在 poke_ball 前
        assertEquals(GREATER, byCat.get(1).quote().itemId());
        assertEquals(BALL, byCat.get(2).quote().itemId());

        List<PriceCatalogEntry> byPriceAsc = c.sorted(PriceSort.PRICE_ASC);
        assertEquals(BALL, byPriceAsc.get(0).quote().itemId());
        assertEquals(GREATER, byPriceAsc.get(2).quote().itemId());

        List<PriceCatalogEntry> byPriceDesc = c.sorted(PriceSort.PRICE_DESC);
        assertEquals(GREATER, byPriceDesc.get(0).quote().itemId());
        assertEquals(POTION, byPriceDesc.get(1).quote().itemId());
        assertEquals(BALL, byPriceDesc.get(2).quote().itemId());

        List<PriceCatalogEntry> byMod = c.sorted(PriceSort.MOD);
        assertTrue(byMod.stream().allMatch(e -> e.modId().equals("pixelmon")));
    }

    @Test
    void filterByCategoryAndEmpty() {
        PriceCatalog c = new PriceCatalog(List.of(
                entry(BALL, 2000, 650, "Poké Balls", "common"),
                entry(POTION, 3000, 1000, "Medicine", "common")));
        assertEquals(1, c.filterByCategory("Medicine").size());
        assertEquals(2, c.filterByCategory("").size());
        assertTrue(PriceCatalog.empty().entries().isEmpty());
    }
}
