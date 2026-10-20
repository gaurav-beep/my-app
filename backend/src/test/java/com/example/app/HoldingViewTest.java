package com.example.app;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class HoldingViewTest {
    private static Holding holding(String units, String invested) {
        Holding h = new Holding();
        h.id = 1L;
        h.type = "mf";
        h.symbol = "122639";
        h.name = "Test Fund";
        h.units = new BigDecimal(units);
        h.invested = invested == null ? null : new BigDecimal(invested);
        return h;
    }

    private static PriceService.Quote quote(String price) {
        return new PriceService.Quote(new BigDecimal(price), "x", Instant.now());
    }

    @Test
    void computesValueAndGain() {
        var v = HoldingView.of(holding("100", "8000"), quote("90.3546"));
        assertEquals(new BigDecimal("9035.46"), v.value());
        assertEquals(new BigDecimal("1035.46"), v.gain());
        assertEquals(new BigDecimal("12.94"), v.gainPct());
    }

    @Test
    void handlesMissingPriceAndZeroInvested() {
        var none = HoldingView.of(holding("5", "100"), null);
        assertNull(none.value());
        assertNull(none.gain());
        assertNull(HoldingView.of(holding("5", "0"), quote("10")).gainPct());
    }

    @Test
    void unknownCostHasValueButNoGain() {
        var v = HoldingView.of(holding("2", null), quote("50"));
        assertEquals(new BigDecimal("100.00"), v.value());
        assertNull(v.gain());
        assertNull(v.gainPct());
    }

    @Test
    void portfolioTotalsIgnoreUnknownCostInGain() {
        var known = HoldingView.of(holding("100", "8000"), quote("90"));
        var unknown = HoldingView.of(holding("2", null), quote("50"));
        var p = Portfolio.of(List.of(known, unknown));
        assertEquals(new BigDecimal("9100.00"), p.totalValue());
        assertEquals(new BigDecimal("8000"), p.totalInvested());
        assertEquals(new BigDecimal("1000.00"), p.totalGain());
        assertEquals(1, p.unknownCost());
        assertTrue(p.complete());
    }
}
