package com.example.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record Perf(BigDecimal pnl, BigDecimal returnPct, BigDecimal benchmarkPct, BigDecimal alphaPts, String bestDay, BigDecimal bestDayPct,
            String worstDay, BigDecimal worstDayPct, BigDecimal maxDrawdownPct, int daysTracked) {}

record CoinStat(String symbol, BigDecimal qty, BigDecimal price, BigDecimal value, BigDecimal sharePct, BigDecimal avgCost,
                BigDecimal unrealized, BigDecimal realized, BigDecimal fees, int trades) {}

record TradeStats(int trades, int buys, int sells, BigDecimal fees, BigDecimal realized, BigDecimal winRatePct, BigDecimal avgWin,
                  BigDecimal avgLoss, BigDecimal bestTrade, BigDecimal worstTrade) {}

record SourceStat(String source, int filled, int sells, BigDecimal realized) {}

record AiStat(int executed, int declined, int cancelled, BigDecimal acceptancePct, List<SourceStat> bySource) {}

record Analysis(Perf performance, BigDecimal cashPct, BigDecimal coinsPct, List<CoinStat> coins, TradeStats trades, AiStat ai) {}

final class CryptoAnalysis {
    private static BigDecimal r(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal r(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static final class Lot {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal avg = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        int trades;
    }

    private static final class Src {
        int filled;
        int sells;
        BigDecimal realized = BigDecimal.ZERO;
    }

    static Analysis build(BigDecimal startCash, BigDecimal startBenchmark, BigDecimal cash, BigDecimal equity, BigDecimal benchmarkNow,
                          List<CryptoPosition> positions, Map<String, BigDecimal> prices, List<CryptoOrder> allOrders, List<CryptoSnapshot> snaps) {
        // performance
        List<String> labels = new ArrayList<>();
        List<Double> series = new ArrayList<>();
        labels.add("start");
        series.add(startCash.doubleValue());
        for (CryptoSnapshot s : snaps) {
            labels.add(String.valueOf(s.day));
            series.add(s.equity.doubleValue());
        }
        labels.add("now");
        series.add(equity.doubleValue());
        String bestDay = null;
        String worstDay = null;
        double best = Double.NEGATIVE_INFINITY;
        double worst = Double.POSITIVE_INFINITY;
        double peak = series.get(0);
        double maxDd = 0;
        for (int i = 1; i < series.size(); i++) {
            double prev = series.get(i - 1);
            double cur = series.get(i);
            if (prev > 0) {
                double ch = (cur - prev) / prev * 100;
                if (ch > best) {
                    best = ch;
                    bestDay = labels.get(i);
                }
                if (ch < worst) {
                    worst = ch;
                    worstDay = labels.get(i);
                }
            }
            peak = Math.max(peak, cur);
            if (peak > 0) maxDd = Math.max(maxDd, (peak - cur) / peak * 100);
        }
        boolean hasChange = bestDay != null;
        BigDecimal ret = startCash.signum() == 0 ? BigDecimal.ZERO : equity.subtract(startCash).multiply(BigDecimal.valueOf(100)).divide(startCash, 2, RoundingMode.HALF_UP);
        BigDecimal benchPct = startBenchmark == null || benchmarkNow == null || startBenchmark.signum() == 0 ? null
            : benchmarkNow.subtract(startBenchmark).multiply(BigDecimal.valueOf(100)).divide(startBenchmark, 2, RoundingMode.HALF_UP);
        Perf perf = new Perf(r(equity.subtract(startCash)), ret, benchPct, benchPct == null ? null : ret.subtract(benchPct),
            bestDay, hasChange ? r(best) : null, worstDay, hasChange ? r(worst) : null, r(maxDd), snaps.size());

        // realized profit by replaying filled orders in order
        Map<String, Lot> lots = new LinkedHashMap<>();
        Map<String, Src> sources = new LinkedHashMap<>();
        List<Double> wins = new ArrayList<>();
        List<Double> losses = new ArrayList<>();
        int buys = 0;
        int sells = 0;
        BigDecimal feesTotal = BigDecimal.ZERO;
        BigDecimal realizedTotal = BigDecimal.ZERO;
        double bestTrade = 0;
        double worstTrade = 0;
        List<CryptoOrder> orders = new ArrayList<>(allOrders);
        orders.sort(Comparator.comparing((CryptoOrder o) -> o.id));
        for (CryptoOrder o : orders) {
            if (!"FILLED".equals(o.status) || o.qty == null || o.fillPrice == null) continue;
            Lot lot = lots.computeIfAbsent(o.symbol, k -> new Lot());
            Src src = sources.computeIfAbsent(o.source == null || "PROTECT".equals(o.source) ? "AI" : o.source, k -> new Src());
            BigDecimal fee = o.fees == null ? BigDecimal.ZERO : o.fees;
            BigDecimal value = o.qty.multiply(o.fillPrice);
            lot.trades++;
            lot.fees = lot.fees.add(fee);
            feesTotal = feesTotal.add(fee);
            src.filled++;
            if ("BUY".equals(o.side) || "SHORT".equals(o.side)) {
                buys++;
                BigDecimal totalCost = lot.avg.multiply(lot.qty).add(value).add("BUY".equals(o.side) ? fee : fee.negate());
                lot.qty = lot.qty.add(o.qty);
                lot.avg = lot.qty.signum() == 0 ? BigDecimal.ZERO : totalCost.divide(lot.qty, 8, RoundingMode.HALF_UP);
            } else {
                sells++;
                src.sells++;
                BigDecimal realized = "COVER".equals(o.side) ? lot.avg.multiply(o.qty).subtract(value).subtract(fee)
                    : value.subtract(fee).subtract(lot.avg.multiply(o.qty));
                lot.realized = lot.realized.add(realized);
                realizedTotal = realizedTotal.add(realized);
                src.realized = src.realized.add(realized);
                lot.qty = lot.qty.subtract(o.qty).max(BigDecimal.ZERO);
                double rv = realized.doubleValue();
                if (rv > 0) wins.add(rv);
                else if (rv < 0) losses.add(rv);
                bestTrade = Math.max(bestTrade, rv);
                worstTrade = Math.min(worstTrade, rv);
            }
        }
        double winRate = sells == 0 ? 0 : wins.size() * 100.0 / sells;
        TradeStats trades = new TradeStats(buys + sells, buys, sells, r(feesTotal), r(realizedTotal), sells == 0 ? null : r(winRate),
            wins.isEmpty() ? null : r(wins.stream().mapToDouble(Double::doubleValue).average().orElse(0)),
            losses.isEmpty() ? null : r(losses.stream().mapToDouble(Double::doubleValue).average().orElse(0)),
            sells == 0 ? null : r(bestTrade), sells == 0 ? null : r(worstTrade));

        // coins
        Set<String> symbols = new LinkedHashSet<>();
        for (CryptoPosition p : positions) symbols.add(p.symbol);
        symbols.addAll(lots.keySet());
        List<CoinStat> coins = new ArrayList<>();
        BigDecimal coinsValue = BigDecimal.ZERO;
        for (String sym : symbols) {
            CryptoPosition pos = positions.stream().filter(p -> p.symbol.equals(sym)).findFirst().orElse(null);
            Lot lot = lots.getOrDefault(sym, new Lot());
            BigDecimal px = prices.get(sym);
            BigDecimal qty = pos == null ? BigDecimal.ZERO : pos.qty;
            BigDecimal value = px == null || pos == null ? BigDecimal.ZERO : CryptoEngine.value(pos, px);
            coinsValue = coinsValue.add(value);
            BigDecimal unrealized = pos == null || px == null ? BigDecimal.ZERO : CryptoEngine.pnl(pos, px);
            double share = equity.signum() == 0 ? 0 : value.doubleValue() / equity.doubleValue() * 100;
            coins.add(new CoinStat(sym, qty.stripTrailingZeros(), px, r(value), r(share), pos == null ? null : pos.avgCost.setScale(2, RoundingMode.HALF_UP),
                r(unrealized), r(lot.realized), r(lot.fees), lot.trades));
        }
        coins.sort(Comparator.comparing((CoinStat c) -> c.value()).reversed());
        double coinsPct = equity.signum() == 0 ? 0 : coinsValue.doubleValue() / equity.doubleValue() * 100;
        double cashPct = equity.signum() == 0 ? 100 : cash.doubleValue() / equity.doubleValue() * 100;

        // AI versus you
        int executed = 0;
        int declined = 0;
        int cancelled = 0;
        for (CryptoOrder o : orders) {
            if (!"AI".equals(o.source)) continue;
            if ("FILLED".equals(o.status)) executed++;
            else if (o.reason != null && o.reason.contains("[declined]")) declined++;
            else if ("CANCELLED".equals(o.status)) cancelled++;
        }
        int decided = executed + declined + cancelled;
        List<SourceStat> bySource = new ArrayList<>();
        for (Map.Entry<String, Src> e : sources.entrySet()) {
            bySource.add(new SourceStat(e.getKey(), e.getValue().filled, e.getValue().sells, r(e.getValue().realized)));
        }
        AiStat ai = new AiStat(executed, declined, cancelled, decided == 0 ? null : r(executed * 100.0 / decided), bySource);

        return new Analysis(perf, r(cashPct), r(coinsPct), coins, trades, ai);
    }

    private CryptoAnalysis() {}
}
