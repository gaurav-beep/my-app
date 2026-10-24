package com.example.app;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class NotifierTest {
    @Test
    void emailBodyListsTradesReasonsAndPortfolio() {
        CryptoOrder o = new CryptoOrder();
        o.symbol = "BTC";
        o.side = "BUY";
        o.qty = new BigDecimal("0.00030000");
        o.fillPrice = new BigDecimal("8161226.5735");
        o.fees = new BigDecimal("5.00");
        o.status = "FILLED";
        o.reason = "Establishing core\nposition";
        CryptoOrder s = new CryptoOrder();
        s.symbol = "ETH";
        s.side = "SELL";
        s.qty = new BigDecimal("0.01");
        s.amountInr = new BigDecimal("2400");
        s.status = "SUGGESTED";

        String body = Notifier.tradesBody(List.of(o, s), new BigDecimal("10234.5"), new BigDecimal("4000"), "https://example.test", "The AI traded for you automatically.");

        assertTrue(body.startsWith("The AI traded for you automatically."));
        assertTrue(body.contains("Bought 0.0003 BTC at Rs 8161226.57 (fee Rs 5.00)"));
        assertTrue(body.contains("Reason: Establishing core position"));
        assertTrue(body.contains("Suggested sell of 0.01 ETH, about Rs 2400.00"));
        assertTrue(body.contains("Portfolio value: Rs 10234.50"));
        assertTrue(body.contains("https://example.test"));
    }
}
