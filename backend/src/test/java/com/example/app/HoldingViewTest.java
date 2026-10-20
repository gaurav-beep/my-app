package com.example.app;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class HoldingViewTest {
    private static Holding holding(String units, String invested) {
        Holding h = new Holding();
        h.id = 1L;
        h.type = "mf";
        h.symbol = "122639";
        h.name = "Test Fund";
        h.units = new BigDecimal(units);
        h.invested = new BigDecimal(invested);
        return h;
    }

    @Test
    void computesValueAndGain() {
        var q = new PriceService.Quote(new BigDecimal("90.3546"), "19-10-2026", Instant.now());
        var v = HoldingView.of(holding("100", "8000"), q);
        assertEquals(new BigDecimal("9035.46"), v.value());
        assertEquals(new BigDecimal("1035.46"), v.gain());
        assertEquals(new BigDecimal("12.94"), v.gainPct());
    }

    @Test
    void handlesMissingPriceAndZeroInvested() {
        var none = HoldingView.of(holding("5", "100"), null);
        assertNull(none.value());
        assertNull(none.gain());
        var q = new PriceService.Quote(new BigDecimal("10"), "x", Instant.now());
        assertNull(HoldingView.of(holding("5", "0"), q).gainPct());
    }
}
