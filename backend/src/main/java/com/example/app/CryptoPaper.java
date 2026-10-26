package com.example.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.*;

@Entity
@Table(name = "crypto_account")
class CryptoAccount {
    @Id
    @Column(name = "user_id")
    Long userId;
    BigDecimal cash;
    @Column(name = "start_cash")
    BigDecimal startCash;
    @Column(name = "start_benchmark")
    BigDecimal startBenchmark;
    @Column(name = "last_decision_at")
    LocalDateTime lastDecisionAt;
    @Column(name = "last_attempt_at")
    LocalDateTime lastAttemptAt;
    @Column(name = "last_summary")
    String lastSummary;
    @Column(name = "auto_trade")
    boolean autoTrade;
    String strategy;
    @Column(name = "notify_email")
    boolean notifyEmail = true;
    @Column(name = "protect_on")
    boolean protectOn = true;
    @Column(name = "stop_loss_pct")
    BigDecimal stopLossPct = new BigDecimal("5");
    @Column(name = "take_profit_pct")
    BigDecimal takeProfitPct = new BigDecimal("10");
    @Column(name = "check_minutes")
    int checkMinutes = 60;
    @Column(name = "events_on")
    boolean eventsOn = true;
    @Column(name = "max_trades")
    int maxTrades = 4;
    @Column(name = "max_leverage")
    int maxLeverage = 50;
    @Column(name = "calls_day")
    LocalDate callsDay;
    @Column(name = "calls_count")
    int callsCount;
}

@Entity
@Table(name = "crypto_position")
class CryptoPosition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "user_id")
    Long userId;
    String symbol;
    BigDecimal qty;
    @Column(name = "avg_cost")
    BigDecimal avgCost;
    String side = "LONG";
    BigDecimal margin = BigDecimal.ZERO;

    int leverage = 1;

    boolean isShort() { return "SHORT".equals(side); }

    boolean margined() { return isShort() || leverage > 1; }
}

@Entity
@Table(name = "crypto_order")
class CryptoOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "user_id")
    Long userId;
    String symbol;
    String side;
    BigDecimal qty;
    @Column(name = "amount_inr")
    BigDecimal amountInr;
    String status;
    String reason;
    @Column(name = "decided_on")
    LocalDate decidedOn;
    @Column(name = "fill_price")
    BigDecimal fillPrice;
    BigDecimal fees;
    @Column(name = "trigger_price")
    BigDecimal triggerPrice;
    @Column(name = "trigger_dir")
    String triggerDir;
    String source = "AI";
    int leverage = 1;
}

@Entity
@Table(name = "crypto_snapshot")
class CryptoSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "user_id")
    Long userId;
    LocalDate day;
    BigDecimal equity;
    BigDecimal benchmark;
}

interface CryptoAccountRepository extends JpaRepository<CryptoAccount, Long> {}

interface CryptoPositionRepository extends JpaRepository<CryptoPosition, Long> {
    List<CryptoPosition> findByUserId(Long userId);
}

interface CryptoOrderRepository extends JpaRepository<CryptoOrder, Long> {
    List<CryptoOrder> findByUserIdAndStatus(Long userId, String status);

    List<CryptoOrder> findTop30ByUserIdOrderByIdDesc(Long userId);

    List<CryptoOrder> findByUserId(Long userId);

    List<CryptoOrder> findByStatus(String status);

    long countByUserIdAndStatus(Long userId, String status);
}

interface CryptoSnapshotRepository extends JpaRepository<CryptoSnapshot, Long> {
    List<CryptoSnapshot> findByUserIdOrderByDayAsc(Long userId);

    Optional<CryptoSnapshot> findByUserIdAndDay(Long userId, LocalDate day);

    List<CryptoSnapshot> findByUserId(Long userId);
}

@Service
class CryptoEngine {
    record Proposal(String side, String symbol, BigDecimal amount, String reason, int leverage) {}

    static final List<String> UNIVERSE = List.of("BTC", "ETH", "BNB", "XRP", "SOL", "DOGE", "ADA", "AVAX", "DOT", "LINK");
    static final String BENCHMARK = "BTC";
    static final int MAX_ORDERS = 4;
    static final BigDecimal MAX_SHARE = new BigDecimal("0.35");
    static final BigDecimal FEE_RATE = new BigDecimal("0.002");
    static final BigDecimal FUT_FEE_RATE = new BigDecimal("0.00059");
    static final BigDecimal LIQ_FEE_RATE = new BigDecimal("0.01");
    static final BigDecimal DEFAULT_SAFETY_PCT = new BigDecimal("1.5");
    static final BigDecimal SLIPPAGE = new BigDecimal("0.001");
    static final BigDecimal MIN_TRADE = new BigDecimal("200");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(CryptoEngine.class);

    private final CryptoAccountRepository accounts;
    private final CryptoPositionRepository positions;
    private final CryptoOrderRepository orders;
    private final CryptoSnapshotRepository snapshots;
    private final PriceService prices;
    private final LlmClient llm;
    private final Notifier notifier;
    private final Sentiment sentiment;
    private final MarketWatch marketWatch;

    CryptoEngine(CryptoAccountRepository accounts, CryptoPositionRepository positions, CryptoOrderRepository orders,
                 CryptoSnapshotRepository snapshots, PriceService prices, LlmClient llm, Notifier notifier, Sentiment sentiment, MarketWatch marketWatch) {
        this.sentiment = sentiment;
        this.marketWatch = marketWatch;
        this.accounts = accounts;
        this.positions = positions;
        this.orders = orders;
        this.snapshots = snapshots;
        this.prices = prices;
        this.llm = llm;
        this.notifier = notifier;
    }

    void notifyTrades(CryptoAccount a, List<CryptoOrder> list, String headline) {
        if (!a.notifyEmail || !notifier.configured() || list.isEmpty()) return;
        CryptoOrder first = list.get(0);
        String what = switch (first.side) { case "BUY" -> "Buy "; case "SELL" -> "Sell "; case "SHORT" -> "Short "; default -> "Cover "; };
        what += (first.qty == null ? "" : first.qty.stripTrailingZeros().toPlainString() + " ") + first.symbol;
        String title = list.size() == 1 ? what : list.size() + " trades";
        BigDecimal equity = equity(a, positions.findByUserId(a.userId));
        notifier.sendAsync(a.userId, "AI Crypto Lab: Trade Confirmation", title + "\n\n" + Notifier.tradesBody(list, equity, a.cash, notifier.appUrl(), headline));
    }

    static String cleanStrategy(String text) {
        if (text == null) return null;
        String t = text.strip();
        if (t.isEmpty()) return null;
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    BigDecimal price(String coin) {
        return prices.coindcx(coin).map(PriceService.Quote::price).orElse(null);
    }

    static BigDecimal pnl(CryptoPosition p, BigDecimal px) {
        return p.isShort() ? p.avgCost.subtract(px).multiply(p.qty) : px.multiply(p.qty).subtract(p.avgCost.multiply(p.qty));
    }

    static BigDecimal value(CryptoPosition p, BigDecimal px) {
        if (!p.margined()) return px.multiply(p.qty);
        return p.margin.add(pnl(p, px)).max(BigDecimal.ZERO);
    }

    BigDecimal equity(CryptoAccount a, List<CryptoPosition> pos) {
        BigDecimal e = a.cash;
        for (CryptoPosition p : pos) {
            BigDecimal px = price(p.symbol);
            e = e.add(value(p, px == null ? p.avgCost : px));
        }
        return e.setScale(2, RoundingMode.HALF_UP);
    }

    CryptoAccount start(Long userId, BigDecimal amount) {
        BigDecimal start = PaperLimits.checkAmount(amount);
        return accounts.findById(userId).orElseGet(() -> {
            CryptoAccount a = new CryptoAccount();
            a.userId = userId;
            a.cash = start;
            a.startCash = start;
            a.startBenchmark = price(BENCHMARK);
            return accounts.save(a);
        });
    }

    void deposit(CryptoAccount a, BigDecimal amount) {
        BigDecimal add = PaperLimits.checkAmount(amount);
        a.cash = a.cash.add(add);
        a.startCash = a.startCash.add(add);
        accounts.save(a);
    }

    void reset(Long userId) {
        watching.set(-1);
        orders.deleteAll(orders.findByUserId(userId));
        positions.deleteAll(positions.findByUserId(userId));
        snapshots.deleteAll(snapshots.findByUserId(userId));
        accounts.deleteById(userId);
    }

    static String pct(BigDecimal now, BigDecimal then) {
        if (then == null || then.signum() == 0) return "n/a";
        return now.subtract(then).multiply(BigDecimal.valueOf(100)).divide(then, 1, RoundingMode.HALF_UP) + "%";
    }

    static String coinLine(String coin, BigDecimal price, List<PriceService.Candle> hourly, List<PriceService.Candle> daily) {
        StringBuilder sb = new StringBuilder("- ").append(coin).append(": price ").append(price.setScale(price.compareTo(BigDecimal.valueOf(100)) > 0 ? 0 : 4, RoundingMode.HALF_UP));
        int h = hourly.size();
        if (h > 1) sb.append(", 1h ").append(pct(price, hourly.get(h - 2).close()));
        if (h > 24) sb.append(", 24h ").append(pct(price, hourly.get(h - 25).close()));
        int d = daily.size();
        if (d > 7) sb.append(", 7d ").append(pct(price, daily.get(d - 8).close()));
        if (h >= 12) {
            sb.append(", last12h [");
            for (int i = h - 12; i < h; i++) sb.append(hourly.get(i).close().setScale(price.compareTo(BigDecimal.valueOf(100)) > 0 ? 0 : 4, RoundingMode.HALF_UP)).append(i < h - 1 ? "," : "");
            sb.append("]");
        }
        return sb.append(Indicators.summary(hourly, daily)).append("\n").toString();
    }

    static String buildPrompt(CryptoAccount a, List<CryptoPosition> pos, Map<String, BigDecimal> lastPrice, List<String> coinLines, BigDecimal equity) {
        StringBuilder sb = new StringBuilder();
        sb.append("You manage a small paper-trading portfolio of cryptocurrencies priced in Indian rupees. This is a simulation. ")
          .append("Crypto is very volatile and trades 24 hours a day. Be conservative: holding cash is a valid choice, and fewer trades are better because every trade has costs. ")
          .append("You are asked every few hours.\n\n");
        sb.append("Rules:\n- Use BUY to open and SELL to close a long position. You may also open a SHORT (a bet that the price will fall, it earns when the price drops and loses when it rises) with side SHORT, and close it with side COVER. You may hold a long and a short in the same coin at the same time as a hedge, but a hedge cancels out most of the profit, so only do it for a clear reason.\n")
          .append("- Leverage: BUY and SHORT orders may include an integer \"leverage\" (default 1, meaning none). With leverage L, amount_inr is the cash you put in (the margin) and the position size is amount_inr x L, so gains and losses are multiplied by L. The highest leverage allowed per coin is listed below. A leveraged or short position is liquidated, losing nearly all of its margin, when the price moves against it by roughly (100 / L - 1.5) percent. Closing orders (SELL, COVER) use the position's own leverage, and their amount_inr is the position value to close. Spot positions (leverage 1 BUY) are not liquidated. A position keeps one leverage, so adding to it must use the same leverage.\n")
          .append(a.protectOn ? "- Every position you open automatically gets a stop-loss at " + a.stopLossPct.stripTrailingZeros().toPlainString() + "% and a take-profit at " + a.takeProfitPct.stripTrailingZeros().toPlainString() + "% price move (the stop is tightened so it triggers before liquidation).\n" : "")
          .append("- At most ").append(MAX_ORDERS).append(" orders.\n")
          .append("- No single coin may exceed ").append(MAX_SHARE.multiply(BigDecimal.valueOf(100)).intValue()).append("% of total equity, counted by the cash put in.\n")
          .append("- Each order is an amount in rupees (amount_inr) of at least ").append(MIN_TRADE).append(". Spot trades cost about ")
          .append(FEE_RATE.multiply(BigDecimal.valueOf(100))).append("% per side, and leveraged or short trades about ")
          .append(FUT_FEE_RATE.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()).append("% of the position size per side.\n")
          .append("- Only use symbols from the list below.\n");
        if (a.strategy != null && !a.strategy.isBlank()) {
            sb.append("\nThe user's own strategy preferences (follow them where sensible, but never break the rules above):\n")
              .append(a.strategy.strip()).append("\n");
        }
        sb.append("\nAccount: cash ").append(a.cash).append(" INR, total equity ").append(equity).append(" INR.\nHoldings:\n");
        if (pos.isEmpty()) sb.append("- none\n");
        for (CryptoPosition p : pos) {
            BigDecimal px = lastPrice.get(p.symbol);
            if (p.margined()) {
                sb.append("- ").append(p.symbol).append(": ").append(p.isShort() ? "SHORT " : "LONG ").append(p.qty.stripTrailingZeros().toPlainString()).append(" units at ").append(p.leverage)
                  .append("x, entry ").append(p.avgCost.setScale(2, RoundingMode.HALF_UP)).append(", margin ").append(p.margin.setScale(2, RoundingMode.HALF_UP))
                  .append(", liquidation price ").append(liqPrice(p, DEFAULT_SAFETY_PCT).setScale(2, RoundingMode.HALF_UP))
                  .append(px == null ? "" : ", profit now " + pnl(p, px).setScale(2, RoundingMode.HALF_UP)).append("\n");
                continue;
            }
            sb.append("- ").append(p.symbol).append(": ").append(p.qty.stripTrailingZeros().toPlainString()).append(" units, average cost ").append(p.avgCost.setScale(2, RoundingMode.HALF_UP))
              .append(px == null ? "" : ", worth " + p.qty.multiply(px).setScale(2, RoundingMode.HALF_UP)).append("\n");
        }
        sb.append("\nMarket data (INR). Suffix h means hourly and d means daily. RSI below 30 is oversold and above 70 overbought. SMA is the simple moving average, ATR the typical daily move, and 30d range position is 0% at the 30-day low and 100% at the high:\n");
        coinLines.forEach(sb::append);
        sb.append("\nReply with JSON only, in exactly this shape:\n")
          .append("{\"summary\":\"one or two sentences on your view\",\"orders\":[{\"side\":\"BUY\",\"symbol\":\"BTC\",\"amount_inr\":2000,\"leverage\":1,\"reason\":\"short reason\"}]}\n")
          .append("Use an empty orders array if you want to do nothing.");
        return sb.toString();
    }

    static List<Proposal> validate(JsonNode json, BigDecimal cash, BigDecimal equity, Map<String, BigDecimal> held, Map<String, BigDecimal> last) {
        return validate(json, cash, equity, held, last, Map.of());
    }

    static List<Proposal> validate(JsonNode json, BigDecimal cash, BigDecimal equity, Map<String, BigDecimal> held, Map<String, BigDecimal> last, Map<String, Integer> levCap) {
        List<Proposal> out = new ArrayList<>();
        Map<String, BigDecimal> qty = new LinkedHashMap<>(held);
        BigDecimal cashLeft = cash;
        for (JsonNode o : json.path("orders")) {
            if (out.size() >= MAX_ORDERS) break;
            String side = o.path("side").asText("").toUpperCase();
            String symbol = o.path("symbol").asText("").toUpperCase();
            BigDecimal px = last.get(symbol);
            BigDecimal amount = o.path("amount_inr").isNumber() ? o.path("amount_inr").decimalValue() : null;
            String reason = o.path("reason").asText("");
            if (!UNIVERSE.contains(symbol) || px == null || amount == null || amount.signum() <= 0
                || !(side.equals("BUY") || side.equals("SELL") || side.equals("SHORT") || side.equals("COVER"))) continue;
            if (reason.length() > 300) reason = reason.substring(0, 300);
            String key = side.equals("SHORT") || side.equals("COVER") ? symbol + ":SHORT" : symbol;
            BigDecimal heldQty = qty.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal heldValue = heldQty.multiply(px);
            int lev = 1;
            if (side.equals("SELL") || side.equals("COVER")) {
                if (heldQty.signum() <= 0) continue;
                if (amount.compareTo(heldValue.multiply(new BigDecimal("0.98"))) >= 0 || heldValue.subtract(amount).compareTo(MIN_TRADE) < 0) amount = heldValue;
                qty.put(key, heldQty.subtract(amount.divide(px, 10, RoundingMode.DOWN)).max(BigDecimal.ZERO));
            } else {
                lev = Math.max(1, Math.min(o.path("leverage").asInt(1), levCap.getOrDefault(symbol, 1)));
                BigDecimal rate = side.equals("SHORT") || lev > 1 ? FUT_FEE_RATE : FEE_RATE;
                BigDecimal costFactor = BigDecimal.ONE.add(rate.multiply(BigDecimal.valueOf(lev)));
                BigDecimal byShare = equity.multiply(MAX_SHARE).subtract(heldValue);
                BigDecimal byCash = cashLeft.divide(costFactor, 2, RoundingMode.DOWN);
                amount = amount.min(byShare).min(byCash);
                if (amount.compareTo(MIN_TRADE) < 0) continue;
                cashLeft = cashLeft.subtract(amount.multiply(costFactor));
                qty.put(key, heldQty.add(amount.multiply(BigDecimal.valueOf(lev)).divide(px, 10, RoundingMode.DOWN)));
            }
            out.add(new Proposal(side, symbol, amount.setScale(2, RoundingMode.DOWN), reason, lev));
        }
        return out;
    }

    private void reject(CryptoOrder o, String why) {
        o.status = "REJECTED";
        o.reason = (o.reason == null ? "" : o.reason + " ") + why;
        orders.save(o);
    }

    void fill(CryptoAccount a, CryptoOrder o, List<CryptoPosition> pos) {
        BigDecimal live = price(o.symbol);
        if (live == null) {
            reject(o, "[no price available]");
            return;
        }
        boolean opening = o.side.equals("BUY") || o.side.equals("SHORT");
        boolean shortSide = o.side.equals("SHORT") || o.side.equals("COVER");
        boolean paysMore = o.side.equals("BUY") || o.side.equals("COVER");
        BigDecimal price = live.multiply(paysMore ? BigDecimal.ONE.add(SLIPPAGE) : BigDecimal.ONE.subtract(SLIPPAGE)).setScale(6, RoundingMode.HALF_UP);
        CryptoPosition held = pos.stream().filter(p -> p.symbol.equals(o.symbol) && p.isShort() == shortSide).findFirst().orElse(null);
        if (!opening && (held == null || held.qty.signum() <= 0)) {
            reject(o, shortSide ? "[no short to cover]" : "[nothing to sell]");
            return;
        }
        int lev = opening ? Math.max(1, o.leverage) : held.leverage;
        boolean margined = shortSide || lev > 1;
        BigDecimal rate = margined ? FUT_FEE_RATE : FEE_RATE;
        BigDecimal feeRate = "SYSTEM".equals(o.source) ? rate.add(LIQ_FEE_RATE) : rate;
        BigDecimal levBd = BigDecimal.valueOf(lev);
        if (opening && held != null && held.leverage != lev) {
            reject(o, "[this position is open at " + held.leverage + "x]");
            return;
        }
        BigDecimal qty;
        if (opening) {
            BigDecimal margin = o.amountInr.min(a.cash.divide(BigDecimal.ONE.add(rate.multiply(levBd)), 2, RoundingMode.DOWN));
            if (margin.compareTo(MIN_TRADE) < 0) {
                reject(o, "[not enough cash]");
                return;
            }
            qty = margin.multiply(levBd).divide(price, 8, RoundingMode.DOWN);
        } else {
            qty = o.amountInr.divide(price, 8, RoundingMode.DOWN).min(held.qty);
            if (qty.compareTo(held.qty.multiply(new BigDecimal("0.98"))) >= 0 || held.qty.subtract(qty).multiply(price).compareTo(MIN_TRADE) < 0) qty = held.qty;
        }
        if (qty.signum() <= 0) {
            reject(o, "[amount too small]");
            return;
        }
        BigDecimal value = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = value.multiply(feeRate).setScale(2, RoundingMode.HALF_UP);
        if (opening) {
            BigDecimal marginPart = value.divide(levBd, 2, RoundingMode.HALF_UP);
            a.cash = a.cash.subtract((margined ? marginPart : value).add(fee));
            if (held == null) {
                held = new CryptoPosition();
                held.userId = a.userId;
                held.symbol = o.symbol;
                held.qty = BigDecimal.ZERO;
                held.avgCost = BigDecimal.ZERO;
                held.side = shortSide ? "SHORT" : "LONG";
                held.margin = BigDecimal.ZERO;
                held.leverage = lev;
                pos.add(held);
            }
            BigDecimal totalCost = held.avgCost.multiply(held.qty).add(value).add(margined ? BigDecimal.ZERO : fee);
            held.qty = held.qty.add(qty);
            held.avgCost = totalCost.divide(held.qty, 6, RoundingMode.HALF_UP);
            if (margined) held.margin = held.margin.add(marginPart);
            positions.save(held);
        } else {
            if (margined) {
                BigDecimal marginPart = held.margin.multiply(qty).divide(held.qty, 2, RoundingMode.HALF_UP);
                BigDecimal profit = shortSide ? held.avgCost.subtract(price).multiply(qty) : price.subtract(held.avgCost).multiply(qty);
                a.cash = a.cash.add(marginPart.add(profit).subtract(fee).max(BigDecimal.ZERO));
                held.margin = held.margin.subtract(marginPart);
            } else {
                a.cash = a.cash.add(value.subtract(fee));
            }
            held.qty = held.qty.subtract(qty);
            if (held.qty.compareTo(new BigDecimal("0.000000001")) <= 0) {
                positions.delete(held);
                pos.remove(held);
            } else {
                positions.save(held);
            }
        }
        o.qty = qty;
        o.fillPrice = price;
        o.fees = fee;
        o.leverage = lev;
        o.status = "FILLED";
        orders.save(o);
        accounts.save(a);
        if (opening && "AI".equals(o.source) && a.protectOn) attachProtection(a, held);
        else if (!opening && !pos.contains(held)) cancelProtection(a.userId, o.symbol, shortSide);
    }

    void cancelProtection(Long userId, String symbol, boolean shortSide) {
        String closeSide = shortSide ? "COVER" : "SELL";
        for (CryptoOrder w : orders.findByUserIdAndStatus(userId, "WATCHING")) {
            if (!"PROTECT".equals(w.source) || !w.symbol.equals(symbol) || !closeSide.equals(w.side)) continue;
            w.status = "CANCELLED";
            orders.save(w);
        }
    }

    private void protect(CryptoPosition p, BigDecimal pct, boolean stop, String dir) {
        BigDecimal factor = BigDecimal.ONE.add(pct.divide(BigDecimal.valueOf(100)).multiply(BigDecimal.valueOf("ABOVE".equals(dir) ? 1 : -1)));
        BigDecimal trigger = p.avgCost.multiply(factor).setScale(4, RoundingMode.HALF_UP);
        CryptoOrder w = new CryptoOrder();
        w.userId = p.userId;
        w.symbol = p.symbol;
        w.side = p.isShort() ? "COVER" : "SELL";
        w.qty = p.qty;
        w.amountInr = p.qty.multiply(trigger).multiply(BigDecimal.valueOf(2)).setScale(2, RoundingMode.UP);
        w.decidedOn = LocalDate.now(IST);
        w.source = "PROTECT";
        w.status = "WATCHING";
        w.triggerPrice = trigger;
        w.triggerDir = dir;
        w.reason = (stop ? "Automatic stop-loss" : "Automatic take-profit") + " at " + pct.stripTrailingZeros().toPlainString() + "%";
        orders.save(w);
    }

    void attachProtection(CryptoAccount a, CryptoPosition p) {
        boolean sh = p.isShort();
        cancelProtection(a.userId, p.symbol, sh);
        BigDecimal sl = a.stopLossPct;
        if (sl != null && sl.signum() > 0 && p.margined() && p.qty.signum() > 0 && p.avgCost.signum() > 0) {
            BigDecimal liqPct = liqLoss(p, safetyPct(p.symbol)).multiply(BigDecimal.valueOf(100)).divide(p.qty.multiply(p.avgCost), 4, RoundingMode.HALF_UP);
            sl = sl.min(liqPct.multiply(new BigDecimal("0.8"))).setScale(2, RoundingMode.DOWN);
        }
        if (sl != null && sl.signum() > 0) protect(p, sl, true, sh ? "ABOVE" : "BELOW");
        if (a.takeProfitPct != null && a.takeProfitPct.signum() > 0) protect(p, a.takeProfitPct, false, sh ? "BELOW" : "ABOVE");
        watching.set(-1);
    }

    static final int MAX_WATCHING = 10;
    private final java.util.concurrent.atomic.AtomicInteger watching = new java.util.concurrent.atomic.AtomicInteger(-1);

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static String triggerDir(BigDecimal target, BigDecimal current) {
        int c = target.compareTo(current);
        return c == 0 ? null : c > 0 ? "ABOVE" : "BELOW";
    }

    String place(CryptoAccount a, TradeIn in) {
        String side = in.side() == null ? "" : in.side().toUpperCase();
        String symbol = in.symbol() == null ? "" : in.symbol().toUpperCase();
        if (!UNIVERSE.contains(symbol) || !(side.equals("BUY") || side.equals("SELL") || side.equals("SHORT") || side.equals("COVER"))) throw bad("Pick a coin and choose Buy, Sell, Short or Cover");
        BigDecimal px = price(symbol);
        if (px == null) throw bad("No live price for " + symbol + " right now. Try again in a moment.");
        List<CryptoPosition> pos = new ArrayList<>(positions.findByUserId(a.userId));
        boolean trigger = in.price() != null;
        BigDecimal ref = trigger ? in.price() : px;
        if (ref.signum() <= 0) throw bad("Enter a price above zero");
        BigDecimal amount;
        BigDecimal qty;
        boolean opens = side.equals("BUY") || side.equals("SHORT");
        boolean shortSide = side.equals("SHORT") || side.equals("COVER");
        CryptoPosition held = pos.stream().filter(p -> p.symbol.equals(symbol) && p.isShort() == shortSide).findFirst().orElse(null);
        int lev = !opens || in.leverage() == null ? 1 : in.leverage();
        int maxLev = opens ? maxLeverage(symbol) : 1;
        if (opens) {
            amount = in.amount();
            if (amount == null || amount.compareTo(MIN_TRADE) < 0) throw bad("The minimum amount is " + MIN_TRADE.toPlainString() + " rupees");
            if (!trigger && amount.compareTo(a.cash) > 0) throw bad("You only have " + a.cash.toPlainString() + " rupees in cash");
            if (lev < 1 || lev > maxLev) throw bad("Leverage for " + symbol + " can be 1x to " + maxLev + "x on CoinDCX");
            if (held != null && held.leverage != lev) throw bad("Your " + symbol + " position is open at " + held.leverage + "x. Use the same leverage or close it first.");
            qty = amount.multiply(BigDecimal.valueOf(lev)).divide(ref, 8, RoundingMode.DOWN);
        } else {
            if (held == null || held.qty.signum() <= 0) throw bad(shortSide ? "You have no short on " + symbol : "You don't hold any " + symbol);
            BigDecimal pc = in.percent();
            if (pc == null || pc.signum() <= 0 || pc.compareTo(BigDecimal.valueOf(100)) > 0) throw bad("Choose between 1 and 100 percent");
            qty = held.qty.multiply(pc).divide(BigDecimal.valueOf(100), 8, RoundingMode.DOWN);
            amount = qty.multiply(ref);
            if (amount.compareTo(BigDecimal.ONE) < 0) throw bad("That amount is too small to sell");
        }
        CryptoOrder o = new CryptoOrder();
        o.userId = a.userId;
        o.symbol = symbol;
        o.side = side;
        o.qty = qty;
        o.amountInr = amount.setScale(2, RoundingMode.DOWN);
        o.decidedOn = LocalDate.now(IST);
        o.source = "USER";
        o.leverage = lev;
        if (trigger) {
            String dir = triggerDir(ref, px);
            if (dir == null) throw bad("Pick a price different from the current price");
            if (orders.findByUserIdAndStatus(a.userId, "WATCHING").stream().filter(w -> !"PROTECT".equals(w.source)).count() >= MAX_WATCHING) throw bad("You can watch up to " + MAX_WATCHING + " orders at once. Cancel one first.");
            o.status = "WATCHING";
            o.triggerPrice = ref.setScale(4, RoundingMode.HALF_UP);
            o.triggerDir = dir;
            o.reason = "Your price order";
            orders.save(o);
            watching.set(-1);
            return "Watching " + symbol + ": will " + side.toLowerCase() + " when the price " + (dir.equals("ABOVE") ? "rises to " : "falls to ") + ref.stripTrailingZeros().toPlainString();
        }
        o.status = "PENDING";
        o.reason = "Your order";
        o = orders.save(o);
        fill(a, o, pos);
        if ("FILLED".equals(o.status)) notifyTrades(a, List.of(o), "You placed this trade.");
        String past = switch (side) { case "BUY" -> "Bought "; case "SELL" -> "Sold "; case "SHORT" -> "Shorted "; default -> "Covered "; };
        return "FILLED".equals(o.status) ? past + o.qty.stripTrailingZeros().toPlainString() + " " + symbol + (o.leverage > 1 ? " at " + o.leverage + "x" : "") + " at the live price"
            : "Order not filled" + (o.reason == null ? "" : ": " + o.reason);
    }

    void cancel(CryptoAccount a, Long orderId) {
        CryptoOrder o = orders.findById(orderId).filter(x -> x.userId.equals(a.userId) && "WATCHING".equals(x.status))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        o.status = "CANCELLED";
        orders.save(o);
        watching.set(-1);
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 5000)
    void checkTriggers() {
        if (watching.get() == 0) return;
        try {
            List<CryptoOrder> open = orders.findByStatus("WATCHING");
            int left = open.size();
            for (CryptoOrder o : open) {
                BigDecimal px = price(o.symbol);
                if (px == null || o.triggerPrice == null) continue;
                boolean hit = "BELOW".equals(o.triggerDir) ? px.compareTo(o.triggerPrice) <= 0 : px.compareTo(o.triggerPrice) >= 0;
                if (!hit) continue;
                CryptoAccount a = accounts.findById(o.userId).orElse(null);
                if (a == null) continue;
                fill(a, o, new ArrayList<>(positions.findByUserId(a.userId)));
                notifyTrades(a, List.of(o), "PROTECT".equals(o.source) ? "Automatic protection on an AI trade triggered." : "Your price order was triggered.");
                left--;
            }
            watching.set(left > 0 ? left : orders.findByStatus("WATCHING").isEmpty() ? 0 : -1);
        } catch (Exception e) {
            log.warn("Price order check failed: {}", e.toString());
        }
    }

    void approve(CryptoAccount a, Long orderId) {
        CryptoOrder o = orders.findById(orderId).filter(x -> x.userId.equals(a.userId) && "SUGGESTED".equals(x.status))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        fill(a, o, new ArrayList<>(positions.findByUserId(a.userId)));
    }

    void decline(CryptoAccount a, Long orderId) {
        CryptoOrder o = orders.findById(orderId).filter(x -> x.userId.equals(a.userId) && "SUGGESTED".equals(x.status))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        reject(o, "[declined]");
    }

    int aiTradesToday(Long userId) {
        LocalDate today = LocalDate.now(IST);
        return (int) orders.findByUserId(userId).stream()
            .filter(o -> "FILLED".equals(o.status) && "AI".equals(o.source) && today.equals(o.decidedOn)).count();
    }

    static List<Proposal> limitOrders(List<Proposal> props, int max) {
        List<Proposal> sorted = new ArrayList<>(props);
        sorted.sort(Comparator.comparing((Proposal p) -> p.side().equals("SELL") || p.side().equals("COVER") ? 0 : 1));
        return sorted.size() <= max ? sorted : new ArrayList<>(sorted.subList(0, max));
    }

    static final int MAX_CALLS_PER_DAY = 24;
    static final Set<Integer> CHECK_CHOICES = Set.of(30, 60, 120, 240);
    final Set<Long> eventTargets = ConcurrentHashMap.newKeySet();

    String decide(CryptoAccount a, boolean manual) {
        return decide(a, manual, null);
    }

    synchronized String decide(CryptoAccount a, boolean manual, String trigger) {
        if (!llm.enabled()) return "The AI is not set up on the server yet.";
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (a.lastAttemptAt != null && a.lastAttemptAt.isAfter(now.minusMinutes(manual ? 2 : trigger != null ? 10 : 15))) return "Tried recently. Please wait a bit.";
        if (manual && a.lastDecisionAt != null && a.lastDecisionAt.isAfter(now.minusMinutes(15))) return "The AI decided less than 15 minutes ago.";
        int tradesLeft = a.maxTrades <= 0 ? MAX_ORDERS : Math.max(0, a.maxTrades - aiTradesToday(a.userId));
        if (tradesLeft == 0) return "The AI has reached your limit of " + a.maxTrades + " trades for today. It continues tomorrow (India time).";
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(a.callsDay)) {
            a.callsDay = today;
            a.callsCount = 0;
        }
        if (a.callsCount >= MAX_CALLS_PER_DAY) return "The AI has reached its limit of " + MAX_CALLS_PER_DAY + " checks for today. It resets at midnight UTC.";
        a.callsCount++;
        a.lastAttemptAt = now;
        accounts.save(a);

        Map<String, BigDecimal> last = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        for (String coin : UNIVERSE) {
            BigDecimal px = price(coin);
            if (px == null) continue;
            last.put(coin, px);
            lines.add(coinLine(coin, px, prices.candles(coin, "1h", 100), prices.candles(coin, "1d", 60)));
        }
        String sentimentText = sentiment.block();
        if (!sentimentText.isBlank()) lines.add("\n" + sentimentText);
        Map<String, Integer> levCap = new LinkedHashMap<>();
        for (String coin : last.keySet()) levCap.put(coin, Math.max(1, Math.min(maxLeverage(coin), a.maxLeverage)));
        lines.add("\nHighest leverage you may use per coin: " + levCap.entrySet().stream().map(e -> e.getKey() + " " + e.getValue() + "x").collect(java.util.stream.Collectors.joining(", ")) + ".\n");
        if (trigger != null) lines.add("\nMarket alert that triggered this check: " + trigger + ".\n");
        if (a.maxTrades > 0) lines.add("\nDaily trade limit: you may place at most " + tradesLeft + " more order(s) today, so save them for clear opportunities. Closing a position counts as an order.\n");
        if (last.size() < 5) return "Market data is unavailable right now.";

        List<CryptoPosition> pos = new ArrayList<>(positions.findByUserId(a.userId));
        BigDecimal equity = equity(a, pos);
        Optional<JsonNode> reply = llm.generateJson(buildPrompt(a, pos, last, lines, equity));
        if (reply.isEmpty()) return "The AI is busy or did not answer. Try again in a few minutes.";

        for (CryptoOrder stale : orders.findByUserIdAndStatus(a.userId, "SUGGESTED")) {
            stale.status = "CANCELLED";
            orders.save(stale);
        }
        Map<String, BigDecimal> held = new LinkedHashMap<>();
        for (CryptoPosition p : pos) held.put(p.isShort() ? p.symbol + ":SHORT" : p.symbol, p.qty);
        List<Proposal> props = limitOrders(validate(reply.get(), a.cash, equity, held, last, levCap), tradesLeft);
        List<CryptoOrder> created = new ArrayList<>();
        for (Proposal p : props) {
            CryptoOrder o = new CryptoOrder();
            o.userId = a.userId;
            o.symbol = p.symbol();
            o.side = p.side();
            o.amountInr = p.amount();
            o.leverage = p.leverage();
            o.qty = p.amount().multiply(BigDecimal.valueOf(p.leverage())).divide(last.get(p.symbol()), 8, RoundingMode.DOWN);
            o.status = "SUGGESTED";
            o.reason = p.reason();
            o.decidedOn = LocalDate.now(IST);
            created.add(orders.save(o));
        }
        String summary = reply.get().path("summary").asText("");
        a.lastSummary = (summary.length() > 500 ? summary.substring(0, 500) : summary) + (props.isEmpty() ? " (No trades.)" : "");
        a.lastDecisionAt = now;
        accounts.save(a);
        if (props.isEmpty()) return "The AI suggests holding for now.";
        if (!a.autoTrade) {
            notifyTrades(a, created, "The AI has " + props.size() + " suggestion(s) waiting for your approval.");
            return "The AI has " + props.size() + " suggestion(s) for you to approve or decline.";
        }
        created.sort(Comparator.comparing((CryptoOrder o) -> o.side.equals("SELL") || o.side.equals("COVER") ? 0 : 1).thenComparing(o -> o.id));
        for (CryptoOrder o : created) fill(a, o, pos);
        notifyTrades(a, created, "The AI traded for you automatically.");
        return "The AI traded automatically: " + props.size() + " order(s) placed.";
    }

    void snapshot(CryptoAccount a) {
        BigDecimal bench = price(BENCHMARK);
        if (bench == null) return;
        LocalDate day = LocalDate.now(IST);
        CryptoSnapshot s = snapshots.findByUserIdAndDay(a.userId, day).orElseGet(CryptoSnapshot::new);
        s.userId = a.userId;
        s.day = day;
        s.equity = equity(a, positions.findByUserId(a.userId));
        s.benchmark = bench;
        snapshots.save(s);
    }

    static BigDecimal liqLoss(CryptoPosition p, BigDecimal safetyPct) {
        BigDecimal notional = p.qty.multiply(p.avgCost);
        BigDecimal loss = p.margin.subtract(notional.multiply(safetyPct).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
        return loss.max(p.margin.multiply(new BigDecimal("0.05")));
    }

    static BigDecimal liqPrice(CryptoPosition p, BigDecimal safetyPct) {
        BigDecimal d = liqLoss(p, safetyPct).divide(p.qty, 6, RoundingMode.HALF_UP);
        return p.isShort() ? p.avgCost.add(d) : p.avgCost.subtract(d).max(BigDecimal.ZERO);
    }

    BigDecimal safetyPct(String coin) {
        return prices.futures(coin).safetyPct();
    }

    int maxLeverage(String coin) {
        return prices.futures(coin).maxLeverage();
    }

    boolean liquidateShorts(CryptoAccount a) {
        List<CryptoPosition> pos = new ArrayList<>(positions.findByUserId(a.userId));
        List<CryptoOrder> done = new ArrayList<>();
        for (CryptoPosition p : List.copyOf(pos)) {
            BigDecimal px = p.margined() ? price(p.symbol) : null;
            if (px == null || p.margin.signum() <= 0 || pnl(p, px).negate().compareTo(liqLoss(p, safetyPct(p.symbol))) < 0) continue;
            CryptoOrder o = new CryptoOrder();
            o.userId = a.userId;
            o.symbol = p.symbol;
            o.side = p.isShort() ? "COVER" : "SELL";
            o.qty = p.qty;
            o.amountInr = p.qty.multiply(px).setScale(2, RoundingMode.UP);
            o.decidedOn = LocalDate.now(IST);
            o.source = "SYSTEM";
            o.status = "PENDING";
            o.reason = "Liquidated: the loss reached the liquidation level";
            o = orders.save(o);
            fill(a, o, pos);
            if ("FILLED".equals(o.status)) done.add(o);
        }
        if (!done.isEmpty()) notifyTrades(a, done, "A leveraged or short position was liquidated to cap your loss.");
        return !done.isEmpty();
    }

    @Scheduled(initialDelay = 180000, fixedDelay = 900000)
    void tick() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (CryptoAccount a : accounts.findAll()) {
            try {
                liquidateShorts(a);
                snapshot(a);
                if (a.eventsOn) eventTargets.add(a.userId);
                else eventTargets.remove(a.userId);
                if (a.lastDecisionAt == null || a.lastDecisionAt.isBefore(now.minusMinutes(Math.max(a.checkMinutes - 5, 10)))) decide(a, false);
            } catch (Exception e) {
                log.warn("Crypto paper step failed for user {}: {}", a.userId, e.toString());
            }
        }
    }

    @Scheduled(initialDelay = 240000, fixedDelay = 300000)
    void watchMarket() {
        if (eventTargets.isEmpty()) return;
        try {
            List<MarketWatch.Signal> all = new ArrayList<>();
            for (String coin : UNIVERSE) {
                BigDecimal px = price(coin);
                if (px != null) all.addAll(MarketWatch.signals(coin, px, prices.candles(coin, "1h", 100), prices.candles(coin, "1d", 60)));
            }
            List<String> events = marketWatch.fresh(all);
            if (events.isEmpty()) return;
            String why = String.join("; ", events.subList(0, Math.min(events.size(), 4)));
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            for (Long uid : List.copyOf(eventTargets)) {
                CryptoAccount a = accounts.findById(uid).orElse(null);
                if (a == null || !a.eventsOn || (a.lastDecisionAt != null && a.lastDecisionAt.isAfter(now.minusMinutes(20)))) continue;
                log.info("Market alert for user {}: {}", uid, why);
                decide(a, false, why);
            }
        } catch (Exception e) {
            log.warn("Market watch failed: {}", e.toString());
        }
    }
}

record LivePosition(String symbol, BigDecimal price, BigDecimal value, BigDecimal pnl, String side) {}

record LiveView(BigDecimal equity, BigDecimal cash, BigDecimal returnPct, BigDecimal benchmarkPct, BigDecimal benchmarkPrice,
                List<LivePosition> positions, Map<String, BigDecimal> prices, String asOf) {}

record TradeIn(String side, String symbol, BigDecimal amount, BigDecimal percent, BigDecimal price, Integer leverage) {}

@RestController
@RequestMapping("/api/crypto-paper")
class CryptoPaperController {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CryptoEngine engine;
    private final LlmClient llm;
    private final CryptoAccountRepository accounts;
    private final CryptoPositionRepository positions;
    private final CryptoOrderRepository orders;
    private final CryptoSnapshotRepository snapshots;

    private final Notifier notifier;

    CryptoPaperController(CryptoEngine engine, LlmClient llm, CryptoAccountRepository accounts, CryptoPositionRepository positions,
                          CryptoOrderRepository orders, CryptoSnapshotRepository snapshots, Notifier notifier) {
        this.notifier = notifier;
        this.engine = engine;
        this.llm = llm;
        this.accounts = accounts;
        this.positions = positions;
        this.orders = orders;
        this.snapshots = snapshots;
    }

    private static Long uid(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }

    private CryptoAccount account(Jwt jwt) {
        return accounts.findById(uid(jwt)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/status")
    Map<String, Boolean> status(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("available", llm.enabled(), "started", accounts.existsById(uid(jwt)));
    }

    @PostMapping("/start") @ResponseStatus(HttpStatus.NO_CONTENT)
    void start(@AuthenticationPrincipal Jwt jwt, @RequestBody AmountIn in) {
        if (!llm.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The AI is not set up on the server yet");
        engine.start(uid(jwt), in.amount());
    }

    @PostMapping("/deposit") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deposit(@AuthenticationPrincipal Jwt jwt, @RequestBody AmountIn in) { engine.deposit(account(jwt), in.amount()); }

    @PostMapping("/auto") @ResponseStatus(HttpStatus.NO_CONTENT)
    void auto(@AuthenticationPrincipal Jwt jwt, @RequestBody AutoIn in) {
        CryptoAccount a = account(jwt);
        a.autoTrade = in.enabled();
        accounts.save(a);
    }

    @PostMapping("/protection") @ResponseStatus(HttpStatus.NO_CONTENT)
    void protection(@AuthenticationPrincipal Jwt jwt, @RequestBody ProtectIn in) {
        CryptoAccount a = account(jwt);
        BigDecimal sl = in.stopLossPct() == null ? BigDecimal.ZERO : in.stopLossPct();
        BigDecimal tp = in.takeProfitPct() == null ? BigDecimal.ZERO : in.takeProfitPct();
        if (sl.signum() < 0 || sl.compareTo(BigDecimal.valueOf(50)) > 0 || tp.signum() < 0 || tp.compareTo(BigDecimal.valueOf(200)) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stop-loss can be 0 to 50 percent and take-profit 0 to 200 percent");
        a.protectOn = in.enabled();
        a.stopLossPct = sl.setScale(2, RoundingMode.HALF_UP);
        a.takeProfitPct = tp.setScale(2, RoundingMode.HALF_UP);
        accounts.save(a);
    }

    @PostMapping("/schedule") @ResponseStatus(HttpStatus.NO_CONTENT)
    void schedule(@AuthenticationPrincipal Jwt jwt, @RequestBody ScheduleIn in) {
        CryptoAccount a = account(jwt);
        if (!CryptoEngine.CHECK_CHOICES.contains(in.minutes())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose 30, 60, 120 or 240 minutes");
        if (in.maxTrades() != null && (in.maxTrades() < 0 || in.maxTrades() > 50)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Daily trade limit must be 0 to 50 (0 means no limit)");
        a.checkMinutes = in.minutes();
        a.eventsOn = in.events();
        if (in.maxTrades() != null) a.maxTrades = in.maxTrades();
        accounts.save(a);
        if (a.eventsOn) engine.eventTargets.add(a.userId);
        else engine.eventTargets.remove(a.userId);
    }

    @PostMapping("/leverage") @ResponseStatus(HttpStatus.NO_CONTENT)
    void leverage(@AuthenticationPrincipal Jwt jwt, @RequestBody LeverageIn in) {
        CryptoAccount a = account(jwt);
        if (in.maxLeverage() < 1 || in.maxLeverage() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose 1x to 100x");
        a.maxLeverage = in.maxLeverage();
        accounts.save(a);
    }

    @PostMapping("/notify") @ResponseStatus(HttpStatus.NO_CONTENT)
    void notify(@AuthenticationPrincipal Jwt jwt, @RequestBody AutoIn in) {
        CryptoAccount a = account(jwt);
        a.notifyEmail = in.enabled();
        accounts.save(a);
    }

    @PostMapping("/notify/test")
    Map<String, String> notifyTest(@AuthenticationPrincipal Jwt jwt) {
        CryptoAccount a = account(jwt);
        String err = notifier.sendNow(a.userId, "AI Crypto Lab: test email",
            "This is a test email from AI Crypto Lab.\n\nIf you can read this, trade notifications will reach you here.\n\nOpen the app: " + notifier.appUrl() + "\n");
        return Map.of("message", err == null ? "Test email sent. Check your inbox and spam folder." : "Could not send: " + err);
    }

    @PostMapping("/strategy") @ResponseStatus(HttpStatus.NO_CONTENT)
    void strategy(@AuthenticationPrincipal Jwt jwt, @RequestBody StrategyIn in) {
        CryptoAccount a = account(jwt);
        a.strategy = CryptoEngine.cleanStrategy(in.text());
        accounts.save(a);
    }

    @PostMapping("/orders/{id}/approve") @ResponseStatus(HttpStatus.NO_CONTENT)
    void approve(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) { engine.approve(account(jwt), id); }

    @PostMapping("/orders/{id}/decline") @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) { engine.decline(account(jwt), id); }

    @PostMapping("/reset") @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@AuthenticationPrincipal Jwt jwt) { engine.reset(uid(jwt)); }

    @PostMapping("/run")
    Map<String, String> run(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("message", engine.decide(account(jwt), true));
    }

    @GetMapping("/live")
    LiveView live(@AuthenticationPrincipal Jwt jwt) {
        CryptoAccount a = account(jwt);
        List<CryptoPosition> pos = positions.findByUserId(a.userId);
        if (pos.stream().anyMatch(CryptoPosition::margined) && engine.liquidateShorts(a)) pos = positions.findByUserId(a.userId);
        List<LivePosition> lp = new ArrayList<>();
        for (CryptoPosition p : pos) {
            BigDecimal px = engine.price(p.symbol);
            BigDecimal value = px == null ? null : CryptoEngine.value(p, px).setScale(2, RoundingMode.HALF_UP);
            BigDecimal pnl = px == null ? null : CryptoEngine.pnl(p, px).setScale(2, RoundingMode.HALF_UP);
            lp.add(new LivePosition(p.symbol, px, value, pnl, p.side));
        }
        BigDecimal equity = engine.equity(a, pos);
        BigDecimal ret = equity.subtract(a.startCash).multiply(BigDecimal.valueOf(100)).divide(a.startCash, 2, RoundingMode.HALF_UP);
        BigDecimal bench = engine.price(CryptoEngine.BENCHMARK);
        BigDecimal benchPct = a.startBenchmark == null || bench == null || a.startBenchmark.signum() == 0 ? null
            : bench.subtract(a.startBenchmark).multiply(BigDecimal.valueOf(100)).divide(a.startBenchmark, 2, RoundingMode.HALF_UP);
        Map<String, BigDecimal> all = new java.util.LinkedHashMap<>();
        for (String coin : CryptoEngine.UNIVERSE) {
            BigDecimal px = engine.price(coin);
            if (px != null) all.put(coin, px);
        }
        return new LiveView(equity, a.cash, ret, benchPct, bench, lp, all, java.time.Instant.now().toString());
    }

    @PostMapping("/trade")
    Map<String, String> trade(@AuthenticationPrincipal Jwt jwt, @RequestBody TradeIn in) {
        return Map.of("message", engine.place(account(jwt), new TradeIn(in.side(), in.symbol(), in.amount(), in.percent(), null, in.leverage())));
    }

    @PostMapping("/trigger")
    Map<String, String> trigger(@AuthenticationPrincipal Jwt jwt, @RequestBody TradeIn in) {
        if (in.price() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the price to wait for");
        return Map.of("message", engine.place(account(jwt), in));
    }

    @PostMapping("/orders/{id}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancelOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) { engine.cancel(account(jwt), id); }

    @GetMapping("/analysis")
    Analysis analysis(@AuthenticationPrincipal Jwt jwt) {
        CryptoAccount a = account(jwt);
        List<CryptoPosition> pos = positions.findByUserId(a.userId);
        Map<String, BigDecimal> prices = new java.util.LinkedHashMap<>();
        for (String coin : CryptoEngine.UNIVERSE) {
            BigDecimal px = engine.price(coin);
            if (px != null) prices.put(coin, px);
        }
        return CryptoAnalysis.build(a.startCash, a.startBenchmark, a.cash, engine.equity(a, pos), engine.price(CryptoEngine.BENCHMARK),
            pos, prices, orders.findByUserId(a.userId), snapshots.findByUserIdOrderByDayAsc(a.userId));
    }

    @GetMapping
    PaperView view(@AuthenticationPrincipal Jwt jwt) {
        CryptoAccount a = account(jwt);
        List<CryptoPosition> pos = positions.findByUserId(a.userId);
        List<PositionView> pv = new ArrayList<>();
        for (CryptoPosition p : pos) {
            BigDecimal px = engine.price(p.symbol);
            BigDecimal value = px == null ? null : CryptoEngine.value(p, px).setScale(2, RoundingMode.HALF_UP);
            BigDecimal pnl = px == null ? null : CryptoEngine.pnl(p, px).setScale(2, RoundingMode.HALF_UP);
            pv.add(new PositionView(p.symbol, p.qty.stripTrailingZeros(), p.avgCost, px, value, pnl, p.side, p.leverage,
                p.margined() ? CryptoEngine.liqPrice(p, engine.safetyPct(p.symbol)).setScale(4, RoundingMode.HALF_UP) : null, p.margin));
        }
        BigDecimal equity = engine.equity(a, pos);
        BigDecimal ret = equity.subtract(a.startCash).multiply(BigDecimal.valueOf(100)).divide(a.startCash, 2, RoundingMode.HALF_UP);
        BigDecimal benchNow = engine.price(CryptoEngine.BENCHMARK);
        BigDecimal benchPct = a.startBenchmark == null || benchNow == null || a.startBenchmark.signum() == 0 ? null
            : benchNow.subtract(a.startBenchmark).multiply(BigDecimal.valueOf(100)).divide(a.startBenchmark, 2, RoundingMode.HALF_UP);
        List<OrderView> ov = orders.findTop30ByUserIdOrderByIdDesc(a.userId).stream()
            .map(o -> new OrderView(o.id, o.symbol, o.side, o.qty == null ? BigDecimal.ZERO : o.qty.stripTrailingZeros(), o.status, o.reason,
                o.decidedOn, o.fillPrice, o.fees, o.amountInr, o.triggerPrice, o.triggerDir, o.source, o.leverage)).toList();
        List<SnapView> hv = snapshots.findByUserIdOrderByDayAsc(a.userId).stream().map(s -> new SnapView(s.day, s.equity, s.benchmark)).toList();
        LocalDate decided = a.lastDecisionAt == null ? null : a.lastDecisionAt.atZone(ZoneOffset.UTC).withZoneSameInstant(IST).toLocalDate();
        return new PaperView(a.cash, a.startCash, a.startBenchmark, equity, ret, benchPct, pv, ov, hv, a.lastSummary, decided, a.autoTrade, a.strategy, a.notifyEmail, notifier.configured(), a.protectOn, a.stopLossPct, a.takeProfitPct, a.checkMinutes, a.eventsOn, a.maxTrades, engine.aiTradesToday(a.userId), a.maxLeverage,
            CryptoEngine.UNIVERSE.stream().collect(java.util.stream.Collectors.toMap(c -> c, engine::maxLeverage, (x, y) -> x, LinkedHashMap::new)));
    }
}
