package com.example.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class IndicatorsTest {
    private static List<PriceService.Candle> series(int n, double start, double step) {
        List<PriceService.Candle> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal c = BigDecimal.valueOf(start + step * i);
            out.add(new PriceService.Candle(i, c, c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), BigDecimal.TEN));
        }
        return out;
    }

    @Test
    void rsiIsHighInAnUptrendAndLowInADowntrend() {
        double[] up = series(40, 100, 1).stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        double[] down = series(40, 200, -1).stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        assertEquals(100.0, Indicators.rsi(up, 14), 0.001);
        assertEquals(0.0, Indicators.rsi(down, 14), 0.001);
        assertNull(Indicators.rsi(new double[] {1, 2, 3}, 14));
    }

    @Test
    void summaryNeedsEnoughHistoryAndNamesTheIndicators() {
        assertEquals("", Indicators.summary(List.of(), List.of()));
        String s = Indicators.summary(series(100, 100, 1), series(60, 100, 1));
        assertTrue(s.contains("RSI14h") && s.contains("SMA50d") && s.contains("MACDh bullish") && s.contains("30d range position 9"), s);
    }
}
