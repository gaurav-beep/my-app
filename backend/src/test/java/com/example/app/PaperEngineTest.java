package com.example.app;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class PaperEngineTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    private static PaperPosition pos(String symbol, long qty) {
        PaperPosition p = new PaperPosition();
        p.symbol = symbol;
        p.qty = qty;
        p.avgCost = new BigDecimal("100");
        return p;
    }

    private static final Map<String, BigDecimal> LAST = Map.of("TCS.NS", new BigDecimal("100"), "ITC.NS", new BigDecimal("50"));

    @Test
    void dropsUnknownSymbolsBadSidesAndZeroQty() throws Exception {
        var out = PaperEngine.validate(json("""
            {"orders":[
              {"side":"BUY","symbol":"EVIL.NS","qty":1,"reason":"x"},
              {"side":"HOLD","symbol":"TCS.NS","qty":1,"reason":"x"},
              {"side":"BUY","symbol":"TCS.NS","qty":0,"reason":"x"},
              {"side":"BUY","symbol":"TCS.NS","qty":1,"reason":"ok"}
            ]}"""), new BigDecimal("10000"), new BigDecimal("10000"), List.of(), LAST);
        assertEquals(1, out.size());
        assertEquals("TCS.NS", out.get(0).symbol());
    }

    @Test
    void capsBuyByCashAndByPositionShare() throws Exception {
        var out = PaperEngine.validate(json("""
            {"orders":[{"side":"BUY","symbol":"TCS.NS","qty":1000,"reason":"all in"}]}"""),
            new BigDecimal("10000"), new BigDecimal("10000"), List.of(), LAST);
        assertEquals(1, out.size());
        assertTrue(out.get(0).qty() <= 39, "40% of equity at ~100 each");
        assertTrue(out.get(0).qty() > 0);
    }

    @Test
    void cannotSellMoreThanHeldOrSellWhatIsNotHeld() throws Exception {
        var out = PaperEngine.validate(json("""
            {"orders":[
              {"side":"SELL","symbol":"TCS.NS","qty":50,"reason":"x"},
              {"side":"SELL","symbol":"ITC.NS","qty":5,"reason":"x"}
            ]}"""), new BigDecimal("1000"), new BigDecimal("1000"), List.of(pos("TCS.NS", 3)), LAST);
        assertEquals(1, out.size());
        assertEquals(3, out.get(0).qty());
    }

    @Test
    void limitsNumberOfOrders() throws Exception {
        var out = PaperEngine.validate(json("""
            {"orders":[
              {"side":"BUY","symbol":"TCS.NS","qty":1,"reason":"a"},
              {"side":"BUY","symbol":"TCS.NS","qty":1,"reason":"b"},
              {"side":"BUY","symbol":"ITC.NS","qty":1,"reason":"c"},
              {"side":"BUY","symbol":"ITC.NS","qty":1,"reason":"d"}
            ]}"""), new BigDecimal("10000"), new BigDecimal("10000"), List.of(), LAST);
        assertEquals(PaperEngine.MAX_ORDERS, out.size());
    }

    @Test
    void feesAreAppliedPerSide() {
        assertEquals(new BigDecimal("12.00"), PaperEngine.fees("BUY", new BigDecimal("10000")));
        assertEquals(new BigDecimal("10.50"), PaperEngine.fees("SELL", new BigDecimal("10000")));
    }

    @Test
    void stripsMarkdownFencesFromModelOutput() {
        assertEquals("{\"a\":1}", LlmClient.stripFences("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", LlmClient.stripFences("{\"a\":1}"));
    }

    @Test
    void promptContainsAccountStateAndRules() {
        PaperAccount a = new PaperAccount();
        a.cash = new BigDecimal("9000");
        List<PriceService.Bar> bars = new java.util.ArrayList<>();
        for (int i = 0; i < 70; i++) bars.add(new PriceService.Bar(LocalDate.of(2026, 1, 1).plusDays(i), BigDecimal.TEN, BigDecimal.valueOf(100 + i)));
        String p = PaperEngine.buildPrompt(a, List.of(pos("TCS.NS", 2)), Map.of("TCS.NS", bars), new BigDecimal("9200"));
        assertTrue(p.contains("cash 9000"));
        assertTrue(p.contains("TCS.NS: 2 shares"));
        assertTrue(p.contains("long-only") || p.contains("Long only"));
        assertTrue(p.contains("JSON only"));
    }
}
