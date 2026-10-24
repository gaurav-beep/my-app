package com.example.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MarketWatchTest {
    private static List<PriceService.Candle> flat(int n, double price) {
        List<PriceService.Candle> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal c = BigDecimal.valueOf(price);
            out.add(new PriceService.Candle(i, c, c, c, c, BigDecimal.ONE));
        }
        return out;
    }

    @Test
    void quietMarketGivesNoSignals() {
        assertTrue(MarketWatch.signals("BTC", new BigDecimal("100"), flat(100, 100), flat(60, 100)).isEmpty());
    }

    @Test
    void sharpMoveAndBreakoutAreDetectedOnceThenSuppressed() {
        var all = MarketWatch.signals("BTC", new BigDecimal("108"), flat(100, 100), flat(60, 100));
        var keys = all.stream().map(MarketWatch.Signal::key).toList();
        assertTrue(keys.contains("BTC:MOVE1H") && keys.contains("BTC:MOVE24H") && keys.contains("BTC:BREAKOUT"), keys.toString());
        var watch = new MarketWatch();
        assertEquals(all.size(), watch.fresh(all).size());
        assertEquals(0, watch.fresh(all).size());
    }
}
