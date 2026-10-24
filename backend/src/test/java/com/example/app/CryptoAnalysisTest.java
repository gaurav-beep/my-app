package com.example.app;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CryptoAnalysisTest {
    private static CryptoOrder order(long id, String symbol, String side, String qty, String price, String fee, String source, String status) {
        CryptoOrder o = new CryptoOrder();
        o.id = id;
        o.symbol = symbol;
        o.side = side;
        o.qty = new BigDecimal(qty);
        o.fillPrice = new BigDecimal(price);
        o.fees = new BigDecimal(fee);
        o.source = source;
        o.status = status;
        return o;
    }

    private static CryptoSnapshot snap(String day, String equity) {
        CryptoSnapshot s = new CryptoSnapshot();
        s.day = LocalDate.parse(day);
        s.equity = new BigDecimal(equity);
        return s;
    }

    @Test
    void realizedProfitUsesAverageCostAndFees() {
        List<CryptoOrder> orders = List.of(
            order(1, "BTC", "BUY", "2", "100", "2", "AI", "FILLED"),
            order(2, "BTC", "SELL", "1", "150", "1.5", "USER", "FILLED"),
            order(3, "BTC", "SELL", "1", "90", "0.9", "AI", "FILLED"));
        Analysis a = CryptoAnalysis.build(new BigDecimal("1000"), null, new BigDecimal("1000"), new BigDecimal("1000"), null,
            List.of(), Map.of(), orders, List.of());
        // average cost is (200 + 2) / 2 = 101 per coin
        assertEquals(3, a.trades().trades());
        assertEquals(1, a.trades().buys());
        assertEquals(2, a.trades().sells());
        assertEquals(0, new BigDecimal("4.40").compareTo(a.trades().fees()));
        assertEquals(0, new BigDecimal("35.60").compareTo(a.trades().realized()), "(150-1.5-101) + (90-0.9-101)");
        assertEquals(0, new BigDecimal("50.00").compareTo(a.trades().winRatePct()));
        SourceStat user = a.ai().bySource().stream().filter(s -> s.source().equals("USER")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("47.50").compareTo(user.realized()));
    }

    @Test
    void drawdownAndBestWorstDays() {
        Analysis a = CryptoAnalysis.build(new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("1050"), new BigDecimal("102"),
            List.of(), Map.of(), List.of(), List.of(snap("2026-10-20", "1100"), snap("2026-10-21", "990")));
        Perf p = a.performance();
        assertEquals(0, new BigDecimal("5.00").compareTo(p.returnPct()));
        assertEquals(0, new BigDecimal("2.00").compareTo(p.benchmarkPct()));
        assertEquals(0, new BigDecimal("3.00").compareTo(p.alphaPts()));
        assertEquals("2026-10-20", p.bestDay());
        assertEquals("2026-10-21", p.worstDay());
        assertEquals(0, new BigDecimal("10.00").compareTo(p.maxDrawdownPct()));
    }

    @Test
    void aiAcceptanceCountsDeclinedAndCancelled() {
        CryptoOrder declined = order(2, "ETH", "BUY", "1", "1", "0", "AI", "REJECTED");
        declined.reason = "x [declined]";
        List<CryptoOrder> orders = List.of(order(1, "BTC", "BUY", "1", "1", "0", "AI", "FILLED"), declined,
            order(3, "BTC", "BUY", "1", "1", "0", "AI", "CANCELLED"));
        Analysis a = CryptoAnalysis.build(new BigDecimal("1000"), null, new BigDecimal("1000"), new BigDecimal("1000"), null,
            List.of(), Map.of(), orders, List.of());
        assertEquals(1, a.ai().executed());
        assertEquals(1, a.ai().declined());
        assertEquals(1, a.ai().cancelled());
        assertEquals(0, new BigDecimal("33.33").compareTo(a.ai().acceptancePct()));
    }

    @Test
    void triggerDirectionFollowsTargetVersusPrice() {
        assertEquals("ABOVE", CryptoEngine.triggerDir(new BigDecimal("110"), new BigDecimal("100")));
        assertEquals("BELOW", CryptoEngine.triggerDir(new BigDecimal("90"), new BigDecimal("100")));
        assertNull(CryptoEngine.triggerDir(new BigDecimal("100"), new BigDecimal("100")));
    }
}
