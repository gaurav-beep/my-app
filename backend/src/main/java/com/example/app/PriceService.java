package com.example.app;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

@Service
class PriceService {
    record Quote(BigDecimal price, String asOf, Instant fetchedAt) {}

    record Candle(long time, BigDecimal open, BigDecimal close, BigDecimal high, BigDecimal low, BigDecimal volume) {}

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);

    private final RestClient dcx = client("https://api.coindcx.com");
    private final RestClient dcxPublic = client("https://public.coindcx.com");
    private volatile Map<String, BigDecimal> dcxTickers = Map.of();
    private volatile Instant dcxFetchedAt = Instant.EPOCH;
    private final Map<String, List<Candle>> candleCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> candleFetchedAt = new ConcurrentHashMap<>();

    static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(8000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(f)
            .defaultHeader("User-Agent", "Mozilla/5.0").build();
    }

    record Fut(int maxLeverage, BigDecimal safetyPct) {}

    private final Map<String, Fut> futCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> futFetchedAt = new ConcurrentHashMap<>();

    Fut futures(String coin) {
        Instant at = futFetchedAt.get(coin);
        Fut cached = futCache.get(coin);
        if (cached != null && at != null && at.plus(Duration.ofHours(6)).isAfter(Instant.now())) return cached;
        try {
            JsonNode n = dcx.get().uri(u -> u.path("/exchange/v1/derivatives/futures/data/instrument").queryParam("pair", "B-" + coin + "_USDT")
                .queryParam("margin_currency_short_name", "INR").build()).retrieve().body(JsonNode.class);
            JsonNode i = n == null ? null : n.path("instrument");
            if (i != null && i.path("max_leverage_long").isNumber()) {
                int max = (int) Math.max(1, Math.min(i.path("max_leverage_long").asDouble(), i.path("max_leverage_short").asDouble(i.path("max_leverage_long").asDouble())));
                BigDecimal safety = i.path("safety_percentage").isNumber() ? i.path("safety_percentage").decimalValue() : new BigDecimal("1.5");
                Fut f = new Fut(max, safety);
                futCache.put(coin, f);
                futFetchedAt.put(coin, Instant.now());
                return f;
            }
        } catch (Exception e) {
            log.warn("Futures info lookup failed for {}: {}", coin, e.toString());
        }
        return cached == null ? new Fut(5, new BigDecimal("2")) : cached;
    }

    List<Candle> candles(String coin, String interval, int limit) {
        String key = coin + ":" + interval + ":" + limit;
        Instant at = candleFetchedAt.get(key);
        List<Candle> cached = candleCache.get(key);
        if (cached != null && at != null && at.plus(Duration.ofMinutes(5)).isAfter(Instant.now())) return cached;
        try {
            JsonNode n = dcxPublic.get().uri(u -> u.path("/market_data/candles").queryParam("pair", "I-" + coin + "_INR")
                .queryParam("interval", interval).queryParam("limit", limit).build()).retrieve().body(JsonNode.class);
            List<Candle> out = new ArrayList<>();
            if (n != null && n.isArray()) {
                for (JsonNode x : n) {
                    if (x.path("close").isNumber() && x.path("open").isNumber()) {
                        out.add(new Candle(x.path("time").asLong(), x.path("open").decimalValue(), x.path("close").decimalValue(),
                            x.path("high").isNumber() ? x.path("high").decimalValue() : null, x.path("low").isNumber() ? x.path("low").decimalValue() : null,
                            x.path("volume").isNumber() ? x.path("volume").decimalValue() : null));
                    }
                }
            }
            out.sort(java.util.Comparator.comparingLong(Candle::time));
            if (!out.isEmpty()) {
                candleCache.put(key, out);
                candleFetchedAt.put(key, Instant.now());
                return out;
            }
        } catch (Exception e) {
            log.warn("Candles lookup failed for {}: {}", key, e.toString());
        }
        return cached == null ? List.of() : cached;
    }

    private synchronized Map<String, BigDecimal> dcxTicker() {
        if (dcxFetchedAt.plusMillis(2500).isAfter(Instant.now())) return dcxTickers;
        try {
            JsonNode n = dcx.get().uri("/exchange/ticker").retrieve().body(JsonNode.class);
            Map<String, BigDecimal> m = new HashMap<>();
            for (JsonNode x : n) {
                try {
                    m.put(x.path("market").asText(), new BigDecimal(x.path("last_price").asText()));
                } catch (NumberFormatException ignored) {
                    // skip markets without a valid price
                }
            }
            if (!m.isEmpty()) {
                dcxTickers = m;
                dcxFetchedAt = Instant.now();
            }
        } catch (Exception e) {
            log.warn("CoinDCX ticker failed: {}", e.toString());
        }
        return dcxTickers;
    }

    Optional<Quote> coindcx(String currency) {
        Map<String, BigDecimal> t = dcxTicker();
        String c = currency.toUpperCase();
        BigDecimal p = c.equals("USDT") ? t.get("USDTINR") : t.get(c + "INR");
        if (p == null && t.get(c + "USDT") != null && t.get("USDTINR") != null) p = t.get(c + "USDT").multiply(t.get("USDTINR"));
        return p == null ? Optional.empty() : Optional.of(new Quote(p, dcxFetchedAt.toString(), dcxFetchedAt));
    }
}
