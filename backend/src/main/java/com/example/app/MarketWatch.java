package com.example.app;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
class MarketWatch {
    record Signal(String key, String text) {}

    static final double MOVE_1H_PCT = 3.0;
    static final double MOVE_24H_PCT = 7.0;
    static final Duration REPEAT_AFTER = Duration.ofHours(4);

    private final Map<String, Instant> fired = new ConcurrentHashMap<>();

    private static String signed(double v) {
        return String.format(Locale.ROOT, "%+.1f%%", v);
    }

    static List<Signal> signals(String coin, BigDecimal price, List<PriceService.Candle> hourly, List<PriceService.Candle> daily) {
        List<Signal> out = new ArrayList<>();
        int h = hourly.size();
        int d = daily.size();
        double p = price.doubleValue();
        if (h >= 2) {
            double ch = (p / hourly.get(h - 2).close().doubleValue() - 1) * 100;
            if (Math.abs(ch) >= MOVE_1H_PCT) out.add(new Signal(coin + ":MOVE1H", coin + " moved " + signed(ch) + " in about an hour"));
        }
        if (h >= 25) {
            double ch = (p / hourly.get(h - 25).close().doubleValue() - 1) * 100;
            if (Math.abs(ch) >= MOVE_24H_PCT) out.add(new Signal(coin + ":MOVE24H", coin + " moved " + signed(ch) + " in 24 hours"));
        }
        if (h > 15) {
            double[] c = hourly.stream().mapToDouble(x -> x.close().doubleValue()).toArray();
            c[c.length - 1] = p;
            Double rsi = Indicators.rsi(c, 14);
            if (rsi != null && rsi <= 25) out.add(new Signal(coin + ":RSI_LOW", String.format(Locale.ROOT, "%s hourly RSI is very low (%.0f)", coin, rsi)));
            if (rsi != null && rsi >= 75) out.add(new Signal(coin + ":RSI_HIGH", String.format(Locale.ROOT, "%s hourly RSI is very high (%.0f)", coin, rsi)));
        }
        if (d >= 31) {
            double hi = Double.NEGATIVE_INFINITY;
            double lo = Double.POSITIVE_INFINITY;
            for (int i = d - 31; i < d - 1; i++) {
                PriceService.Candle x = daily.get(i);
                hi = Math.max(hi, x.high() == null ? x.close().doubleValue() : x.high().doubleValue());
                lo = Math.min(lo, x.low() == null ? x.close().doubleValue() : x.low().doubleValue());
            }
            if (p > hi) out.add(new Signal(coin + ":BREAKOUT", coin + " broke above its 30-day high"));
            if (p < lo) out.add(new Signal(coin + ":BREAKDOWN", coin + " broke below its 30-day low"));
        }
        return out;
    }

    List<String> fresh(List<Signal> all) {
        Instant now = Instant.now();
        List<String> out = new ArrayList<>();
        for (Signal s : all) {
            Instant last = fired.get(s.key());
            if (last != null && last.plus(REPEAT_AFTER).isAfter(now)) continue;
            fired.put(s.key(), now);
            out.add(s.text());
        }
        return out;
    }
}
