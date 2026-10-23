package com.example.app;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);
    private static final Duration MF_TTL = Duration.ofHours(3);
    private static final Duration CRYPTO_TTL = Duration.ofMinutes(5);

    private final RestClient mf = client("https://api.mfapi.in");
    private final RestClient gecko = client("https://api.coingecko.com/api/v3");
    private final RestClient yahoo = client("https://query1.finance.yahoo.com");
    private final RestClient dcx = client("https://api.coindcx.com");
    private final Map<String, Quote> cache = new ConcurrentHashMap<>();
    private volatile Map<String, BigDecimal> dcxTickers = Map.of();
    private volatile Instant dcxFetchedAt = Instant.EPOCH;

    static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(8000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(f)
            .defaultHeader("User-Agent", "Mozilla/5.0").build();
    }

    Optional<Quote> stock(String symbol) {
        String key = "stock:" + symbol;
        Quote cached = cache.get(key);
        if (fresh(cached, CRYPTO_TTL)) return Optional.of(cached);
        try {
            JsonNode meta = yahoo.get().uri(u -> u.path("/v8/finance/chart/{s}").queryParam("range", "1d").queryParam("interval", "1d").build(symbol))
                .retrieve().body(JsonNode.class).path("chart").path("result").path(0).path("meta");
            if (meta.path("regularMarketPrice").isNumber() && "INR".equals(meta.path("currency").asText())) {
                String asOf = meta.path("regularMarketTime").isNumber() ? Instant.ofEpochSecond(meta.path("regularMarketTime").asLong()).toString() : Instant.now().toString();
                Quote q = new Quote(meta.path("regularMarketPrice").decimalValue(), asOf, Instant.now());
                cache.put(key, q);
                return Optional.of(q);
            }
        } catch (Exception e) {
            log.warn("Stock price lookup failed for {}: {}", symbol, e.toString());
        }
        return Optional.ofNullable(cached);
    }

    record Candle(long time, BigDecimal open, BigDecimal close) {}

    private final RestClient dcxPublic = client("https://public.coindcx.com");
    private final Map<String, List<Candle>> candleCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> candleFetchedAt = new ConcurrentHashMap<>();

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
                        out.add(new Candle(x.path("time").asLong(), x.path("open").decimalValue(), x.path("close").decimalValue()));
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

    record Bar(java.time.LocalDate date, BigDecimal open, BigDecimal close) {}

    private final Map<String, List<Bar>> barCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> barFetchedAt = new ConcurrentHashMap<>();

    List<Bar> bars(String symbol) {
        Instant at = barFetchedAt.get(symbol);
        List<Bar> cached = barCache.get(symbol);
        if (cached != null && at != null && at.plus(Duration.ofMinutes(10)).isAfter(Instant.now())) return cached;
        try {
            JsonNode r = yahoo.get().uri(u -> u.path("/v8/finance/chart/{s}").queryParam("range", "6mo").queryParam("interval", "1d").build(symbol))
                .retrieve().body(JsonNode.class).path("chart").path("result").path(0);
            JsonNode ts = r.path("timestamp");
            JsonNode q = r.path("indicators").path("quote").path(0);
            List<Bar> out = new ArrayList<>();
            for (int i = 0; i < ts.size(); i++) {
                JsonNode o = q.path("open").path(i);
                JsonNode c = q.path("close").path(i);
                if (o.isNumber() && c.isNumber()) {
                    out.add(new Bar(java.time.LocalDate.ofInstant(Instant.ofEpochSecond(ts.get(i).asLong()), java.time.ZoneId.of("Asia/Kolkata")),
                        o.decimalValue(), c.decimalValue()));
                }
            }
            if (!out.isEmpty()) {
                barCache.put(symbol, out);
                barFetchedAt.put(symbol, Instant.now());
                return out;
            }
        } catch (Exception e) {
            log.warn("Daily bars lookup failed for {}: {}", symbol, e.toString());
        }
        return cached == null ? List.of() : cached;
    }

    List<Map<String, String>> searchStocks(String q) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            JsonNode n = yahoo.get().uri(u -> u.path("/v1/finance/search").queryParam("q", q).queryParam("quotesCount", 15).queryParam("newsCount", 0).build())
                .retrieve().body(JsonNode.class).path("quotes");
            for (JsonNode x : n) {
                String sym = x.path("symbol").asText();
                String type = x.path("quoteType").asText();
                if (out.size() >= 8) break;
                if (!(sym.endsWith(".NS") || sym.endsWith(".BO")) || !(type.equals("EQUITY") || type.equals("ETF"))) continue;
                String name = x.path("longname").asText(x.path("shortname").asText(sym));
                out.add(Map.of("symbol", sym, "name", name + " (" + sym + ")"));
            }
        } catch (Exception e) {
            log.warn("Stock search failed: {}", e.toString());
        }
        return out;
    }

    private synchronized Map<String, BigDecimal> dcxTicker() {
        if (dcxFetchedAt.plusSeconds(60).isAfter(Instant.now())) return dcxTickers;
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

    private boolean fresh(Quote q, Duration ttl) {
        return q != null && q.fetchedAt().plus(ttl).isAfter(Instant.now());
    }

    Optional<Quote> mfNav(String code) {
        String key = "mf:" + code;
        Quote cached = cache.get(key);
        if (fresh(cached, MF_TTL)) return Optional.of(cached);
        try {
            JsonNode data = mf.get().uri("/mf/{c}/latest", code).retrieve().body(JsonNode.class).path("data");
            if (data.isArray() && !data.isEmpty()) {
                Quote q = new Quote(new BigDecimal(data.get(0).path("nav").asText()), data.get(0).path("date").asText(), Instant.now());
                cache.put(key, q);
                return Optional.of(q);
            }
        } catch (Exception e) {
            log.warn("MF NAV lookup failed for {}: {}", code, e.toString());
        }
        return Optional.ofNullable(cached);
    }

    Map<String, Quote> crypto(Collection<String> ids) {
        List<String> stale = new ArrayList<>();
        for (String id : ids) {
            if (!fresh(cache.get("crypto:" + id), CRYPTO_TTL)) stale.add(id);
        }
        if (!stale.isEmpty()) {
            try {
                JsonNode n = gecko.get().uri(u -> u.path("/simple/price")
                    .queryParam("ids", String.join(",", stale)).queryParam("vs_currencies", "inr").build())
                    .retrieve().body(JsonNode.class);
                Instant now = Instant.now();
                for (String id : stale) {
                    JsonNode p = n.path(id).path("inr");
                    if (p.isNumber()) cache.put("crypto:" + id, new Quote(p.decimalValue(), now.toString(), now));
                }
            } catch (Exception e) {
                log.warn("Crypto price lookup failed: {}", e.toString());
            }
        }
        Map<String, Quote> out = new HashMap<>();
        for (String id : ids) {
            Quote q = cache.get("crypto:" + id);
            if (q != null) out.put(id, q);
        }
        return out;
    }

    List<Map<String, String>> searchMf(String q) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            JsonNode n = mf.get().uri(u -> u.path("/mf/search").queryParam("q", q).build()).retrieve().body(JsonNode.class);
            for (JsonNode x : n) {
                if (out.size() >= 10) break;
                out.add(Map.of("symbol", x.path("schemeCode").asText(), "name", x.path("schemeName").asText()));
            }
        } catch (Exception e) {
            log.warn("MF search failed: {}", e.toString());
        }
        return out;
    }

    List<Map<String, String>> searchCrypto(String q) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            JsonNode n = gecko.get().uri(u -> u.path("/search").queryParam("query", q).build()).retrieve().body(JsonNode.class).path("coins");
            for (JsonNode x : n) {
                if (out.size() >= 8) break;
                out.add(Map.of("symbol", x.path("id").asText(), "name", x.path("name").asText() + " (" + x.path("symbol").asText() + ")"));
            }
        } catch (Exception e) {
            log.warn("Crypto search failed: {}", e.toString());
        }
        return out;
    }
}
