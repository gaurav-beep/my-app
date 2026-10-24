package com.example.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Indicators {
    private static double[] closes(List<PriceService.Candle> c) {
        return c.stream().mapToDouble(x -> x.close().doubleValue()).toArray();
    }

    static Double rsi(double[] c, int n) {
        if (c.length <= n) return null;
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= n; i++) {
            double d = c[i] - c[i - 1];
            if (d >= 0) gain += d;
            else loss -= d;
        }
        gain /= n;
        loss /= n;
        for (int i = n + 1; i < c.length; i++) {
            double d = c[i] - c[i - 1];
            gain = (gain * (n - 1) + Math.max(d, 0)) / n;
            loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
        }
        return loss == 0 ? 100.0 : 100 - 100 / (1 + gain / loss);
    }

    static double sma(double[] c, int n) {
        double s = 0;
        for (int i = c.length - n; i < c.length; i++) s += c[i];
        return s / n;
    }

    static double[] ema(double[] c, int n) {
        double[] out = new double[c.length];
        double k = 2.0 / (n + 1);
        out[0] = c[0];
        for (int i = 1; i < c.length; i++) out[i] = c[i] * k + out[i - 1] * (1 - k);
        return out;
    }

    static double[] macdHistogram(double[] c) {
        double[] fast = ema(c, 12);
        double[] slow = ema(c, 26);
        double[] macd = new double[c.length];
        for (int i = 0; i < c.length; i++) macd[i] = fast[i] - slow[i];
        double[] signal = ema(macd, 9);
        double[] hist = new double[c.length];
        for (int i = 0; i < c.length; i++) hist[i] = macd[i] - signal[i];
        return hist;
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%+.1f%%", v);
    }

    static String summary(List<PriceService.Candle> hourly, List<PriceService.Candle> daily) {
        List<String> parts = new ArrayList<>();
        int h = hourly.size();
        int d = daily.size();
        double[] hc = closes(hourly);
        double[] dc = closes(daily);
        Double rsiH = rsi(hc, 14);
        if (rsiH != null) parts.add(String.format(Locale.ROOT, "RSI14h %.0f", rsiH));
        Double rsiD = rsi(dc, 14);
        if (rsiD != null) parts.add(String.format(Locale.ROOT, "RSI14d %.0f", rsiD));
        if (d >= 20) parts.add("vs SMA20d " + pct((dc[d - 1] / sma(dc, 20) - 1) * 100));
        if (d >= 50) parts.add("vs SMA50d " + pct((dc[d - 1] / sma(dc, 50) - 1) * 100));
        if (h >= 40) {
            double[] hist = macdHistogram(hc);
            boolean up = hist[h - 1] > 0;
            boolean rising = hist[h - 1] > hist[h - 2];
            parts.add("MACDh " + (up ? "bullish" : "bearish") + (rising ? " rising" : " falling"));
        }
        if (d >= 15) {
            double tr = 0;
            for (int i = d - 14; i < d; i++) {
                PriceService.Candle x = daily.get(i);
                double prev = dc[i - 1];
                double hi = x.high() == null ? dc[i] : x.high().doubleValue();
                double lo = x.low() == null ? dc[i] : x.low().doubleValue();
                tr += Math.max(hi - lo, Math.max(Math.abs(hi - prev), Math.abs(lo - prev)));
            }
            parts.add(String.format(Locale.ROOT, "ATR14d %.1f%%", tr / 14 / dc[d - 1] * 100));
        }
        if (d >= 30) {
            double hi = Double.NEGATIVE_INFINITY;
            double lo = Double.POSITIVE_INFINITY;
            for (int i = d - 30; i < d; i++) {
                PriceService.Candle x = daily.get(i);
                hi = Math.max(hi, x.high() == null ? dc[i] : x.high().doubleValue());
                lo = Math.min(lo, x.low() == null ? dc[i] : x.low().doubleValue());
            }
            if (hi > lo) parts.add(String.format(Locale.ROOT, "30d range position %.0f%%", (dc[d - 1] - lo) / (hi - lo) * 100));
        }
        if (h >= 48) {
            double now = 0;
            double before = 0;
            for (int i = h - 24; i < h; i++) now += hourly.get(i).volume() == null ? 0 : hourly.get(i).volume().doubleValue();
            for (int i = h - 48; i < h - 24; i++) before += hourly.get(i).volume() == null ? 0 : hourly.get(i).volume().doubleValue();
            if (before > 0) parts.add("volume 24h " + pct((now / before - 1) * 100));
        }
        return parts.isEmpty() ? "" : ", " + String.join(", ", parts);
    }

    private Indicators() {}
}
