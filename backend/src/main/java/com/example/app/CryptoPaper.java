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

    boolean isShort() { return "SHORT".equals(side); }
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
    record Proposal(String side, String symbol, BigDecimal amount, String reason) {}

    static final List<String> UNIVERSE = List.of("BTC", "ETH", "BNB", "XRP", "SOL", "DOGE", "ADA", "AVAX", "DOT", "LINK");
    static final String BENCHMARK = "BTC";
    static final int MAX_ORDERS = 4;
    static final BigDecimal MAX_SHARE = new BigDecimal("0.35");
    static final BigDecimal FEE_RATE = new BigDecimal("0.002");
    static final BigDecimal SLIPPAGE = new BigDecimal("0.001");
    static final BigDecimal MIN_TRADE = new BigDecimal("200");
    static final Duration DECISION_EVERY = Duration.ofHours(4);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(CryptoEngine.class);

    private final CryptoAccountRepository accounts;
    private final CryptoPositionRepository positions;
    private final CryptoOrderRepository orders;
    private final CryptoSnapshotRepository snapshots;
    private final PriceService prices;
    private final LlmClient llm;
    private final Notifier notifier;

    CryptoEngine(CryptoAccountRepository accounts, CryptoPositionRepository positions, CryptoOrderRepository orders,
                 CryptoSnapshotRepository snapshots, PriceService prices, LlmClient llm, Notifier notifier) {
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
        String subject = list.size() == 1 ? "AI Crypto Lab: " + what : "AI Crypto Lab: " + list.size() + " trades";
        BigDecimal equity = equity(a, positions.findByUserId(a.userId));
        notifier.sendAsync(a.userId, subject, Notifier.tradesBody(list, equity, a.cash, notifier.appUrl(), headline));
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
        if (!p.isShort()) return px.multiply(p.qty);
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
        return sb.append("\n").toString();
    }

    static String buildPrompt(CryptoAccount a, List<CryptoPosition> pos, Map<String, BigDecimal> lastPrice, List<String> coinLines, BigDecimal equity) {
        StringBuilder sb = new StringBuilder();
        sb.append("You manage a small long-only paper-trading portfolio of cryptocurrencies priced in Indian rupees. This is a simulation. ")
          .append("Crypto is very volatile and trades 24 hours a day. Be conservative: holding cash is a valid choice, and fewer trades are better because every trade has costs. ")
          .append("You are asked every few hours.\n\n");
        sb.append("Rules:\n- No leverage. Use BUY to open and SELL to close a normal holding. You may also open a SHORT (a bet that the price will fall, it earns when the price drops and loses when it rises) with side SHORT, and close it with side COVER. A short is fully backed by cash and is closed automatically if it loses 90% of that cash. Never SHORT a coin you hold, and never BUY a coin you are short.\n")
          .append("- At most ").append(MAX_ORDERS).append(" orders.\n")
          .append("- No single coin may exceed ").append(MAX_SHARE.multiply(BigDecimal.valueOf(100)).intValue()).append("% of total equity.\n")
          .append("- Each order is an amount in rupees (amount_inr) of at least ").append(MIN_TRADE).append(". Trades cost about ")
          .append(FEE_RATE.multiply(BigDecimal.valueOf(100))).append("% per side.\n")
          .append("- Only use symbols from the list below.\n");
        if (a.strategy != null && !a.strategy.isBlank()) {
            sb.append("\nThe user's own strategy preferences (follow them where sensible, but never break the rules above):\n")
              .append(a.strategy.strip()).append("\n");
        }
        sb.append("\nAccount: cash ").append(a.cash).append(" INR, total equity ").append(equity).append(" INR.\nHoldings:\n");
        if (pos.isEmpty()) sb.append("- none\n");
        for (CryptoPosition p : pos) {
            BigDecimal px = lastPrice.get(p.symbol);
            if (p.isShort()) {
                sb.append("- ").append(p.symbol).append(": SHORT ").append(p.qty.stripTrailingZeros().toPlainString()).append(" units, entry ").append(p.avgCost.setScale(2, RoundingMode.HALF_UP))
                  .append(px == null ? "" : ", profit now " + pnl(p, px).setScale(2, RoundingMode.HALF_UP)).append("\n");
                continue;
            }
            sb.append("- ").append(p.symbol).append(": ").append(p.qty.stripTrailingZeros().toPlainString()).append(" units, average cost ").append(p.avgCost.setScale(2, RoundingMode.HALF_UP))
              .append(px == null ? "" : ", worth " + p.qty.multiply(px).setScale(2, RoundingMode.HALF_UP)).append("\n");
        }
        sb.append("\nMarket data (INR):\n");
        coinLines.forEach(sb::append);
        sb.append("\nReply with JSON only, in exactly this shape:\n")
          .append("{\"summary\":\"one or two sentences on your view\",\"orders\":[{\"side\":\"BUY\",\"symbol\":\"BTC\",\"amount_inr\":2000,\"reason\":\"short reason\"}]}\n")
          .append("Use an empty orders array if you want to do nothing.");
        return sb.toString();
    }

    static List<Proposal> validate(JsonNode json, BigDecimal cash, BigDecimal equity, Map<String, BigDecimal> held, Map<String, BigDecimal> last) {
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
            BigDecimal heldQty = qty.getOrDefault(symbol, BigDecimal.ZERO);
            BigDecimal heldValue = heldQty.abs().multiply(px);
            if (side.equals("SELL") || side.equals("COVER")) {
                boolean covering = side.equals("COVER");
                if (covering ? heldQty.signum() >= 0 : heldQty.signum() <= 0) continue;
                if (amount.compareTo(heldValue.multiply(new BigDecimal("0.98"))) >= 0 || heldValue.subtract(amount).compareTo(MIN_TRADE) < 0) amount = heldValue;
                BigDecimal closed = amount.divide(px, 10, RoundingMode.DOWN);
                qty.put(symbol, covering ? heldQty.add(closed).min(BigDecimal.ZERO) : heldQty.subtract(closed).max(BigDecimal.ZERO));
            } else {
                boolean shorting = side.equals("SHORT");
                if (shorting ? heldQty.signum() > 0 : heldQty.signum() < 0) continue;
                BigDecimal byShare = equity.multiply(MAX_SHARE).subtract(heldValue);
                BigDecimal byCash = cashLeft.divide(BigDecimal.ONE.add(FEE_RATE), 2, RoundingMode.DOWN);
                amount = amount.min(byShare).min(byCash);
                if (amount.compareTo(MIN_TRADE) < 0) continue;
                cashLeft = cashLeft.subtract(amount.multiply(BigDecimal.ONE.add(FEE_RATE)));
                BigDecimal opened = amount.divide(px, 10, RoundingMode.DOWN);
                qty.put(symbol, shorting ? heldQty.subtract(opened) : heldQty.add(opened));
            }
            out.add(new Proposal(side, symbol, amount.setScale(2, RoundingMode.DOWN), reason));
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
        CryptoPosition held = pos.stream().filter(p -> p.symbol.equals(o.symbol)).findFirst().orElse(null);
        if (opening && held != null && held.isShort() != shortSide) {
            reject(o, shortSide ? "[sell your coins before shorting]" : "[cover your short before buying]");
            return;
        }
        if (!opening && (held == null || held.qty.signum() <= 0 || held.isShort() != shortSide)) {
            reject(o, shortSide ? "[no short to cover]" : "[nothing to sell]");
            return;
        }
        BigDecimal qty;
        if (opening) {
            BigDecimal spend = o.amountInr.min(a.cash.divide(BigDecimal.ONE.add(FEE_RATE), 2, RoundingMode.DOWN));
            if (spend.compareTo(MIN_TRADE) < 0) {
                reject(o, "[not enough cash]");
                return;
            }
            qty = spend.divide(price, 8, RoundingMode.DOWN);
        } else {
            qty = o.amountInr.divide(price, 8, RoundingMode.DOWN).min(held.qty);
            if (qty.compareTo(held.qty.multiply(new BigDecimal("0.98"))) >= 0 || held.qty.subtract(qty).multiply(price).compareTo(MIN_TRADE) < 0) qty = held.qty;
        }
        if (qty.signum() <= 0) {
            reject(o, "[amount too small]");
            return;
        }
        BigDecimal value = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = value.multiply(FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        if (opening) {
            a.cash = a.cash.subtract(value.add(fee));
            if (held == null) {
                held = new CryptoPosition();
                held.userId = a.userId;
                held.symbol = o.symbol;
                held.qty = BigDecimal.ZERO;
                held.avgCost = BigDecimal.ZERO;
                held.side = shortSide ? "SHORT" : "LONG";
                held.margin = BigDecimal.ZERO;
                pos.add(held);
            }
            BigDecimal totalCost = held.avgCost.multiply(held.qty).add(value).add(shortSide ? BigDecimal.ZERO : fee);
            held.qty = held.qty.add(qty);
            held.avgCost = totalCost.divide(held.qty, 6, RoundingMode.HALF_UP);
            if (shortSide) held.margin = held.margin.add(value);
            positions.save(held);
        } else {
            if (shortSide) {
                BigDecimal marginPart = held.margin.multiply(qty).divide(held.qty, 2, RoundingMode.HALF_UP);
                BigDecimal profit = held.avgCost.subtract(price).multiply(qty);
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
        o.status = "FILLED";
        orders.save(o);
        accounts.save(a);
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
        CryptoPosition held = pos.stream().filter(p -> p.symbol.equals(symbol)).findFirst().orElse(null);
        boolean trigger = in.price() != null;
        BigDecimal ref = trigger ? in.price() : px;
        if (ref.signum() <= 0) throw bad("Enter a price above zero");
        BigDecimal amount;
        BigDecimal qty;
        boolean opens = side.equals("BUY") || side.equals("SHORT");
        boolean shortSide = side.equals("SHORT") || side.equals("COVER");
        if (opens && held != null && held.isShort() != shortSide) {
            throw bad(shortSide ? "You hold " + symbol + ". Sell it before shorting." : "You are short " + symbol + ". Cover the short before buying.");
        }
        if (opens) {
            amount = in.amount();
            if (amount == null || amount.compareTo(MIN_TRADE) < 0) throw bad("The minimum amount is " + MIN_TRADE.toPlainString() + " rupees");
            if (!trigger && amount.compareTo(a.cash) > 0) throw bad("You only have " + a.cash.toPlainString() + " rupees in cash");
            qty = amount.divide(ref, 8, RoundingMode.DOWN);
        } else {
            if (held == null || held.qty.signum() <= 0 || held.isShort() != shortSide) throw bad(shortSide ? "You have no short on " + symbol : "You don't hold any " + symbol);
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
        if (trigger) {
            String dir = triggerDir(ref, px);
            if (dir == null) throw bad("Pick a price different from the current price");
            if (orders.countByUserIdAndStatus(a.userId, "WATCHING") >= MAX_WATCHING) throw bad("You can watch up to " + MAX_WATCHING + " orders at once. Cancel one first.");
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
        return "FILLED".equals(o.status) ? past + o.qty.stripTrailingZeros().toPlainString() + " " + symbol + " at the live price"
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
                notifyTrades(a, List.of(o), "Your price order was triggered.");
                left--;
            }
            watching.set(left);
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

    String decide(CryptoAccount a, boolean manual) {
        if (!llm.enabled()) return "The AI is not set up on the server yet.";
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (a.lastAttemptAt != null && a.lastAttemptAt.isAfter(now.minusMinutes(manual ? 2 : 60))) return "Tried recently. Please wait a bit.";
        if (manual && a.lastDecisionAt != null && a.lastDecisionAt.isAfter(now.minusMinutes(15))) return "The AI decided less than 15 minutes ago.";
        a.lastAttemptAt = now;
        accounts.save(a);

        Map<String, BigDecimal> last = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        for (String coin : UNIVERSE) {
            BigDecimal px = price(coin);
            if (px == null) continue;
            last.put(coin, px);
            lines.add(coinLine(coin, px, prices.candles(coin, "1h", 30), prices.candles(coin, "1d", 10)));
        }
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
        for (CryptoPosition p : pos) held.put(p.symbol, p.isShort() ? p.qty.negate() : p.qty);
        List<Proposal> props = validate(reply.get(), a.cash, equity, held, last);
        List<CryptoOrder> created = new ArrayList<>();
        for (Proposal p : props) {
            CryptoOrder o = new CryptoOrder();
            o.userId = a.userId;
            o.symbol = p.symbol();
            o.side = p.side();
            o.amountInr = p.amount();
            o.qty = p.amount().divide(last.get(p.symbol()), 8, RoundingMode.DOWN);
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

    boolean liquidateShorts(CryptoAccount a) {
        List<CryptoPosition> pos = new ArrayList<>(positions.findByUserId(a.userId));
        List<CryptoOrder> done = new ArrayList<>();
        for (CryptoPosition p : List.copyOf(pos)) {
            BigDecimal px = p.isShort() ? price(p.symbol) : null;
            if (px == null || p.margin.signum() <= 0 || pnl(p, px).negate().compareTo(p.margin.multiply(new BigDecimal("0.9"))) < 0) continue;
            CryptoOrder o = new CryptoOrder();
            o.userId = a.userId;
            o.symbol = p.symbol;
            o.side = "COVER";
            o.qty = p.qty;
            o.amountInr = p.qty.multiply(px).setScale(2, RoundingMode.UP);
            o.decidedOn = LocalDate.now(IST);
            o.source = "SYSTEM";
            o.status = "PENDING";
            o.reason = "Closed automatically: this short lost 90% of its cash";
            o = orders.save(o);
            fill(a, o, pos);
            if ("FILLED".equals(o.status)) done.add(o);
        }
        if (!done.isEmpty()) notifyTrades(a, done, "A short was closed automatically to limit your loss.");
        return !done.isEmpty();
    }

    @Scheduled(initialDelay = 180000, fixedDelay = 900000)
    void tick() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (CryptoAccount a : accounts.findAll()) {
            try {
                liquidateShorts(a);
                snapshot(a);
                if (a.lastDecisionAt == null || a.lastDecisionAt.isBefore(now.minus(DECISION_EVERY))) decide(a, false);
            } catch (Exception e) {
                log.warn("Crypto paper step failed for user {}: {}", a.userId, e.toString());
            }
        }
    }
}

record LivePosition(String symbol, BigDecimal price, BigDecimal value, BigDecimal pnl) {}

record LiveView(BigDecimal equity, BigDecimal cash, BigDecimal returnPct, BigDecimal benchmarkPct, BigDecimal benchmarkPrice,
                List<LivePosition> positions, Map<String, BigDecimal> prices, String asOf) {}

record TradeIn(String side, String symbol, BigDecimal amount, BigDecimal percent, BigDecimal price) {}

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
        if (pos.stream().anyMatch(CryptoPosition::isShort) && engine.liquidateShorts(a)) pos = positions.findByUserId(a.userId);
        List<LivePosition> lp = new ArrayList<>();
        for (CryptoPosition p : pos) {
            BigDecimal px = engine.price(p.symbol);
            BigDecimal value = px == null ? null : CryptoEngine.value(p, px).setScale(2, RoundingMode.HALF_UP);
            BigDecimal pnl = px == null ? null : CryptoEngine.pnl(p, px).setScale(2, RoundingMode.HALF_UP);
            lp.add(new LivePosition(p.symbol, px, value, pnl));
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
        return Map.of("message", engine.place(account(jwt), new TradeIn(in.side(), in.symbol(), in.amount(), in.percent(), null)));
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
            pv.add(new PositionView(p.symbol, p.qty.stripTrailingZeros(), p.avgCost, px, value, pnl, p.side));
        }
        BigDecimal equity = engine.equity(a, pos);
        BigDecimal ret = equity.subtract(a.startCash).multiply(BigDecimal.valueOf(100)).divide(a.startCash, 2, RoundingMode.HALF_UP);
        BigDecimal benchNow = engine.price(CryptoEngine.BENCHMARK);
        BigDecimal benchPct = a.startBenchmark == null || benchNow == null || a.startBenchmark.signum() == 0 ? null
            : benchNow.subtract(a.startBenchmark).multiply(BigDecimal.valueOf(100)).divide(a.startBenchmark, 2, RoundingMode.HALF_UP);
        List<OrderView> ov = orders.findTop30ByUserIdOrderByIdDesc(a.userId).stream()
            .map(o -> new OrderView(o.id, o.symbol, o.side, o.qty == null ? BigDecimal.ZERO : o.qty.stripTrailingZeros(), o.status, o.reason,
                o.decidedOn, o.fillPrice, o.fees, o.amountInr, o.triggerPrice, o.triggerDir, o.source)).toList();
        List<SnapView> hv = snapshots.findByUserIdOrderByDayAsc(a.userId).stream().map(s -> new SnapView(s.day, s.equity, s.benchmark)).toList();
        LocalDate decided = a.lastDecisionAt == null ? null : a.lastDecisionAt.atZone(ZoneOffset.UTC).withZoneSameInstant(IST).toLocalDate();
        return new PaperView(a.cash, a.startCash, a.startBenchmark, equity, ret, benchPct, pv, ov, hv, a.lastSummary, decided, a.autoTrade, a.strategy, a.notifyEmail, notifier.configured());
    }
}
