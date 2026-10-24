package com.example.app;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class CryptoEngineTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final Map<String, BigDecimal> LAST = Map.of("BTC", new BigDecimal("8000000"), "DOGE", new BigDecimal("8"));

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void capsBuysByCashAndByCoinShare() throws Exception {
        var out = CryptoEngine.validate(json("""
            {"orders":[{"side":"BUY","symbol":"BTC","amount_inr":999999,"reason":"all in"}]}"""),
            new BigDecimal("10000"), new BigDecimal("10000"), Map.of(), LAST);
        assertEquals(1, out.size());
        assertEquals(0, new BigDecimal("3500.00").compareTo(out.get(0).amount()), "35% of equity");
    }

    @Test
    void dropsBadOrders() throws Exception {
        var out = CryptoEngine.validate(json("""
            {"orders":[
              {"side":"BUY","symbol":"SCAM","amount_inr":1000,"reason":"x"},
              {"side":"BUY","symbol":"DOGE","amount_inr":50,"reason":"too small"},
              {"side":"SELL","symbol":"DOGE","amount_inr":1000,"reason":"nothing held"},
              {"side":"HODL","symbol":"DOGE","amount_inr":1000,"reason":"bad side"},
              {"side":"BUY","symbol":"DOGE","amount_inr":"lots","reason":"bad amount"}
            ]}"""), new BigDecimal("10000"), new BigDecimal("10000"), Map.of(), LAST);
        assertTrue(out.isEmpty());
    }

    @Test
    void shortsAreCappedAndConflictsDropped() throws Exception {
        var out = CryptoEngine.validate(json("""
            {"orders":[
              {"side":"SHORT","symbol":"BTC","amount_inr":999999,"reason":"fall"},
              {"side":"SHORT","symbol":"DOGE","amount_inr":500,"reason":"holds it"},
              {"side":"COVER","symbol":"ETH","amount_inr":500,"reason":"no short"}]}"""),
            new BigDecimal("10000"), new BigDecimal("10000"), Map.of("DOGE", new BigDecimal("125")), LAST);
        assertEquals(1, out.size());
        assertEquals("SHORT", out.get(0).side());
        assertTrue(out.get(0).amount().compareTo(new BigDecimal("3500")) <= 0);
    }

    @Test
    void coveringAllMostOfAShortCoversItAll() throws Exception {
        var out = CryptoEngine.validate(json("""
            {"orders":[{"side":"COVER","symbol":"DOGE","amount_inr":990,"reason":"exit"}]}"""),
            new BigDecimal("1000"), new BigDecimal("2000"), Map.of("DOGE", new BigDecimal("-125")), LAST);
        assertEquals(1, out.size());
        assertEquals("COVER", out.get(0).side());
    }

    @Test
    void shortValueRisesWhenPriceFalls() {
        var p = new CryptoPosition();
        p.side = "SHORT";
        p.qty = new BigDecimal("2");
        p.avgCost = new BigDecimal("100");
        p.margin = new BigDecimal("200");
        assertEquals(0, new BigDecimal("40").compareTo(CryptoEngine.pnl(p, new BigDecimal("80"))));
        assertEquals(0, new BigDecimal("240").compareTo(CryptoEngine.value(p, new BigDecimal("80"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(CryptoEngine.value(p, new BigDecimal("500"))));
    }

    @Test
    void sellingMostOfAHoldingSellsItAll() throws Exception {
        var out = CryptoEngine.validate(json("""
            {"orders":[{"side":"SELL","symbol":"DOGE","amount_inr":990,"reason":"exit"}]}"""),
            new BigDecimal("1000"), new BigDecimal("2000"), Map.of("DOGE", new BigDecimal("125")), LAST);
        assertEquals(1, out.size());
        assertEquals(0, new BigDecimal("1000.00").compareTo(out.get(0).amount()));
    }

    @Test
    void limitsOrderCount() throws Exception {
        StringBuilder sb = new StringBuilder("{\"orders\":[");
        for (int i = 0; i < 8; i++) sb.append(i > 0 ? "," : "").append("{\"side\":\"BUY\",\"symbol\":\"DOGE\",\"amount_inr\":300,\"reason\":\"r\"}");
        sb.append("]}");
        var out = CryptoEngine.validate(json(sb.toString()), new BigDecimal("100000"), new BigDecimal("100000"), Map.of(), LAST);
        assertEquals(CryptoEngine.MAX_ORDERS, out.size());
    }

    @Test
    void cleansStrategyText() {
        assertNull(CryptoEngine.cleanStrategy("   "));
        assertNull(CryptoEngine.cleanStrategy(null));
        assertEquals("buy dips", CryptoEngine.cleanStrategy("  buy dips "));
        assertEquals(500, CryptoEngine.cleanStrategy("x".repeat(900)).length());
    }

    @Test
    void promptIncludesStrategyAndHoldings() {
        CryptoAccount a = new CryptoAccount();
        a.cash = new BigDecimal("5000");
        a.strategy = "Only trade BTC and ETH";
        CryptoPosition p = new CryptoPosition();
        p.symbol = "BTC";
        p.qty = new BigDecimal("0.001");
        p.avgCost = new BigDecimal("8000000");
        String prompt = CryptoEngine.buildPrompt(a, List.of(p), Map.of("BTC", new BigDecimal("8100000")), List.of("- BTC: price 8100000\n"), new BigDecimal("13100"));
        assertTrue(prompt.contains("Only trade BTC and ETH"));
        assertTrue(prompt.contains("BTC: 0.001 units"));
        assertTrue(prompt.contains("JSON only"));
    }
}
