package com.example.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.*;

@Entity
@Table(name = "paper_account")
class PaperAccount {
    @Id
    @Column(name = "user_id")
    Long userId;
    BigDecimal cash;
    @Column(name = "start_cash")
    BigDecimal startCash;
    @Column(name = "start_benchmark")
    BigDecimal startBenchmark;
    @Column(name = "last_decision_on")
    LocalDate lastDecisionOn;
    @Column(name = "last_attempt_at")
    LocalDateTime lastAttemptAt;
    @Column(name = "last_summary")
    String lastSummary;
    @Column(name = "auto_trade")
    boolean autoTrade;
    String strategy;
}

@Entity
@Table(name = "paper_position")
class PaperPosition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "user_id")
    Long userId;
    String symbol;
    Long qty;
    @Column(name = "avg_cost")
    BigDecimal avgCost;
}

@Entity
@Table(name = "paper_order")
class PaperOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "user_id")
    Long userId;
    String symbol;
    String side;
    Long qty;
    String status;
    String reason;
    @Column(name = "decided_on")
    LocalDate decidedOn;
    @Column(name = "fill_price")
    BigDecimal fillPrice;
    BigDecimal fees;
    @Column(name = "filled_on")
    LocalDate filledOn;
}

@Entity
@Table(name = "paper_snapshot")
class PaperSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "user_id")
    Long userId;
    LocalDate day;
    BigDecimal equity;
    BigDecimal benchmark;
}

interface PaperAccountRepository extends JpaRepository<PaperAccount, Long> {}

interface PaperPositionRepository extends JpaRepository<PaperPosition, Long> {
    List<PaperPosition> findByUserId(Long userId);
}

interface PaperOrderRepository extends JpaRepository<PaperOrder, Long> {
    List<PaperOrder> findByUserIdAndStatus(Long userId, String status);

    List<PaperOrder> findTop30ByUserIdOrderByIdDesc(Long userId);

    List<PaperOrder> findByUserId(Long userId);
}

interface PaperSnapshotRepository extends JpaRepository<PaperSnapshot, Long> {
    List<PaperSnapshot> findByUserIdOrderByDayAsc(Long userId);

    Optional<PaperSnapshot> findByUserIdAndDay(Long userId, LocalDate day);

    List<PaperSnapshot> findByUserId(Long userId);
}

@Service
class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final String key;
    private final String model;
    private final ObjectMapper mapper;
    private final RestClient client;

    LlmClient(@Value("${app.gemini-key}") String key, @Value("${app.gemini-model}") String model, ObjectMapper mapper) {
        this.key = key;
        this.model = model;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(60000);
        this.client = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com").requestFactory(f).build();
    }

    boolean enabled() { return !key.isBlank(); }

    Optional<JsonNode> generateJson(String prompt) {
        Map<String, Object> body = Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
            "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.3));
        long[] waitsMs = {0, 4000, 10000};
        for (int attempt = 0; attempt < waitsMs.length; attempt++) {
            try {
                if (waitsMs[attempt] > 0) Thread.sleep(waitsMs[attempt]);
                String raw = client.post().uri("/v1beta/models/{m}:generateContent", model).header("x-goog-api-key", key)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
                JsonNode r = mapper.readTree(raw == null ? "{}" : raw);
                String text = r.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
                if (text.isBlank()) {
                    log.warn("LLM returned no text. Finish reason: {}", r.path("candidates").path(0).path("finishReason").asText("unknown"));
                    return Optional.empty();
                }
                return Optional.of(mapper.readTree(stripFences(text)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (org.springframework.web.client.RestClientResponseException e) {
                int code = e.getStatusCode().value();
                log.warn("LLM call failed (attempt {}): {}", attempt + 1, code);
                if (code != 429 && code != 503 && code != 500) return Optional.empty();
            } catch (Exception e) {
                log.warn("LLM call failed: {}", e.toString());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    static String stripFences(String t) {
        String s = t.strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*", "");
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        }
        return s.strip();
    }
}

@Service
class PaperEngine {
    record Proposal(String side, String symbol, long qty, String reason) {}

    static final List<String> UNIVERSE = List.of("RELIANCE.NS", "TCS.NS", "HDFCBANK.NS", "INFY.NS", "ICICIBANK.NS", "HINDUNILVR.NS",
        "ITC.NS", "SBIN.NS", "BHARTIARTL.NS", "LT.NS", "KOTAKBANK.NS", "AXISBANK.NS", "MARUTI.NS", "SUNPHARMA.NS", "NIFTYBEES.NS");
    static final String BENCHMARK = "^NSEI";
    static final int MAX_ORDERS = 3;
    static final BigDecimal MAX_POSITION_SHARE = new BigDecimal("0.40");
    static final BigDecimal SLIPPAGE = new BigDecimal("0.0005");
    static final BigDecimal BUY_RATE = new BigDecimal("0.0012");
    static final BigDecimal SELL_RATE = new BigDecimal("0.00105");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(PaperEngine.class);

    private final PaperAccountRepository accounts;
    private final PaperPositionRepository positions;
    private final PaperOrderRepository orders;
    private final PaperSnapshotRepository snapshots;
    private final PriceService prices;
    private final LlmClient llm;

    PaperEngine(PaperAccountRepository accounts, PaperPositionRepository positions, PaperOrderRepository orders,
                PaperSnapshotRepository snapshots, PriceService prices, LlmClient llm) {
        this.accounts = accounts;
        this.positions = positions;
        this.orders = orders;
        this.snapshots = snapshots;
        this.prices = prices;
        this.llm = llm;
    }

    static BigDecimal fees(String side, BigDecimal value) {
        return value.multiply(side.equals("BUY") ? BUY_RATE : SELL_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    BigDecimal lastClose(String symbol) {
        List<PriceService.Bar> b = prices.bars(symbol);
        return b.isEmpty() ? null : b.get(b.size() - 1).close();
    }

    BigDecimal equity(PaperAccount a, List<PaperPosition> pos) {
        BigDecimal e = a.cash;
        for (PaperPosition p : pos) {
            BigDecimal px = lastClose(p.symbol);
            e = e.add((px == null ? p.avgCost : px).multiply(BigDecimal.valueOf(p.qty)));
        }
        return e.setScale(2, RoundingMode.HALF_UP);
    }

    static final BigDecimal MIN_AMOUNT = new BigDecimal("1000");
    static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");

    static BigDecimal checkAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount between 1,000 and 1,00,00,000");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    PaperAccount start(Long userId, BigDecimal amount) {
        BigDecimal start = checkAmount(amount);
        return accounts.findById(userId).orElseGet(() -> {
            PaperAccount a = new PaperAccount();
            a.userId = userId;
            a.cash = start;
            a.startCash = start;
            a.startBenchmark = lastClose(BENCHMARK);
            return accounts.save(a);
        });
    }

    void deposit(PaperAccount a, BigDecimal amount) {
        BigDecimal add = checkAmount(amount);
        a.cash = a.cash.add(add);
        a.startCash = a.startCash.add(add);
        accounts.save(a);
    }

    void setAuto(PaperAccount a, boolean enabled) {
        a.autoTrade = enabled;
        accounts.save(a);
    }

    void approve(PaperAccount a, Long orderId, LocalDate today) {
        PaperOrder o = orders.findById(orderId).filter(x -> x.userId.equals(a.userId) && "SUGGESTED".equals(x.status))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        o.status = "PENDING";
        o.decidedOn = today;
        orders.save(o);
    }

    void decline(PaperAccount a, Long orderId) {
        PaperOrder o = orders.findById(orderId).filter(x -> x.userId.equals(a.userId) && "SUGGESTED".equals(x.status))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        o.status = "REJECTED";
        o.reason = (o.reason == null ? "" : o.reason + " ") + "[declined]";
        orders.save(o);
    }

    void reset(Long userId) {
        orders.deleteAll(orders.findByUserId(userId));
        positions.deleteAll(positions.findByUserId(userId));
        snapshots.deleteAll(snapshots.findByUserId(userId));
        accounts.deleteById(userId);
    }

    static String pct(BigDecimal now, BigDecimal then) {
        if (then == null || then.signum() == 0) return "n/a";
        return now.subtract(then).multiply(BigDecimal.valueOf(100)).divide(then, 1, RoundingMode.HALF_UP) + "%";
    }

    static String buildPrompt(PaperAccount a, List<PaperPosition> pos, Map<String, List<PriceService.Bar>> bars, BigDecimal equity) {
        StringBuilder sb = new StringBuilder();
        sb.append("You manage a small long-only paper-trading portfolio of Indian large-cap stocks and one Nifty ETF. ")
          .append("This is a simulation. Be conservative: holding cash is a valid choice, and fewer trades are better because every trade has costs. ")
          .append("Decide the orders to place for the next market open.\n\n");
        sb.append("Rules:\n- Long only, whole shares only, no leverage, no short selling.\n")
          .append("- At most ").append(MAX_ORDERS).append(" orders.\n")
          .append("- No single stock may exceed ").append(MAX_POSITION_SHARE.multiply(BigDecimal.valueOf(100)).intValue()).append("% of total equity.\n")
          .append("- Only use symbols from the list below. Prices are in INR.\n");
        if (a.strategy != null && !a.strategy.isBlank()) {
            sb.append("\nThe user's own strategy preferences (follow them where sensible, but never break the rules above):\n")
              .append(a.strategy.strip()).append("\n");
        }
        sb.append("\n");
        sb.append("Account: cash ").append(a.cash).append(", total equity ").append(equity).append(".\n");
        sb.append("Holdings:\n");
        if (pos.isEmpty()) sb.append("- none\n");
        for (PaperPosition p : pos) sb.append("- ").append(p.symbol).append(": ").append(p.qty).append(" shares, average cost ").append(p.avgCost).append("\n");
        sb.append("\nMarket data (daily closes, oldest to newest for the last 10 sessions, plus returns):\n");
        for (Map.Entry<String, List<PriceService.Bar>> e : bars.entrySet()) {
            List<PriceService.Bar> b = e.getValue();
            if (b.size() < 21) continue;
            BigDecimal last = b.get(b.size() - 1).close();
            sb.append("- ").append(e.getKey()).append(": last ").append(last.setScale(2, RoundingMode.HALF_UP))
              .append(", 5d ").append(pct(last, b.get(b.size() - 6).close()))
              .append(", 20d ").append(pct(last, b.get(b.size() - 21).close()));
            if (b.size() > 60) sb.append(", 60d ").append(pct(last, b.get(b.size() - 61).close()));
            sb.append(", last10 [");
            for (int i = b.size() - 10; i < b.size(); i++) sb.append(b.get(i).close().setScale(1, RoundingMode.HALF_UP)).append(i < b.size() - 1 ? "," : "");
            sb.append("]\n");
        }
        sb.append("\nReply with JSON only, in exactly this shape:\n")
          .append("{\"summary\":\"one or two sentences on your view\",\"orders\":[{\"side\":\"BUY\",\"symbol\":\"TCS.NS\",\"qty\":1,\"reason\":\"short reason\"}]}\n")
          .append("Use an empty orders array if you want to do nothing.");
        return sb.toString();
    }

    static List<Proposal> validate(JsonNode json, BigDecimal cash, BigDecimal equity, List<PaperPosition> pos, Map<String, BigDecimal> last) {
        List<Proposal> out = new ArrayList<>();
        Map<String, Long> held = new LinkedHashMap<>();
        for (PaperPosition p : pos) held.put(p.symbol, p.qty);
        BigDecimal cashLeft = cash;
        for (JsonNode o : json.path("orders")) {
            if (out.size() >= MAX_ORDERS) break;
            String side = o.path("side").asText("").toUpperCase();
            String symbol = o.path("symbol").asText("");
            long qty = o.path("qty").asLong(0);
            String reason = o.path("reason").asText("");
            BigDecimal px = last.get(symbol);
            if (!UNIVERSE.contains(symbol) || px == null || qty <= 0 || !(side.equals("BUY") || side.equals("SELL"))) continue;
            if (reason.length() > 300) reason = reason.substring(0, 300);
            if (side.equals("SELL")) {
                long have = held.getOrDefault(symbol, 0L);
                if (qty > have) qty = have;
                if (qty <= 0) continue;
                held.put(symbol, have - qty);
            } else {
                BigDecimal est = px.multiply(BigDecimal.ONE.add(SLIPPAGE));
                BigDecimal maxByShare = equity.multiply(MAX_POSITION_SHARE).subtract(px.multiply(BigDecimal.valueOf(held.getOrDefault(symbol, 0L))));
                long byShare = maxByShare.signum() <= 0 ? 0 : maxByShare.divide(est, 0, RoundingMode.DOWN).longValue();
                long byCash = cashLeft.divide(est.multiply(BigDecimal.ONE.add(BUY_RATE)), 0, RoundingMode.DOWN).longValue();
                qty = Math.min(qty, Math.min(byShare, byCash));
                if (qty <= 0) continue;
                cashLeft = cashLeft.subtract(est.multiply(BigDecimal.valueOf(qty)).multiply(BigDecimal.ONE.add(BUY_RATE)));
                held.merge(symbol, qty, Long::sum);
            }
            out.add(new Proposal(side, symbol, qty, reason));
        }
        return out;
    }

    String decide(PaperAccount a, LocalDate today, boolean manual) {
        if (!llm.enabled()) return "The AI is not set up on the server yet.";
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        if (a.lastAttemptAt != null && a.lastAttemptAt.isAfter(nowUtc.minusMinutes(manual ? 2 : 60))) return "Tried recently. Please wait a bit.";
        if (today.equals(a.lastDecisionOn)) return "Already decided for today.";
        a.lastAttemptAt = nowUtc;
        accounts.save(a);

        Map<String, List<PriceService.Bar>> bars = new LinkedHashMap<>();
        Map<String, BigDecimal> last = new LinkedHashMap<>();
        for (String s : UNIVERSE) {
            List<PriceService.Bar> b = prices.bars(s);
            if (!b.isEmpty()) {
                bars.put(s, b);
                last.put(s, b.get(b.size() - 1).close());
            }
        }
        if (bars.size() < 5) return "Market data is unavailable right now.";

        List<PaperPosition> pos = positions.findByUserId(a.userId);
        BigDecimal equity = equity(a, pos);
        Optional<JsonNode> reply = llm.generateJson(buildPrompt(a, pos, bars, equity));
        if (reply.isEmpty()) return "The AI is busy or did not answer. Try again in a few minutes.";

        for (String open : List.of("PENDING", "SUGGESTED")) {
            for (PaperOrder stale : orders.findByUserIdAndStatus(a.userId, open)) {
                stale.status = "CANCELLED";
                orders.save(stale);
            }
        }
        List<Proposal> props = validate(reply.get(), a.cash, equity, pos, last);
        for (Proposal p : props) {
            PaperOrder o = new PaperOrder();
            o.userId = a.userId;
            o.symbol = p.symbol();
            o.side = p.side();
            o.qty = p.qty();
            o.status = a.autoTrade ? "PENDING" : "SUGGESTED";
            o.reason = p.reason();
            o.decidedOn = today;
            orders.save(o);
        }
        String summary = reply.get().path("summary").asText("");
        a.lastSummary = (summary.length() > 500 ? summary.substring(0, 500) : summary) + (props.isEmpty() ? " (No trades.)" : "");
        a.lastDecisionOn = today;
        accounts.save(a);
        if (props.isEmpty()) return "The AI suggests holding for now.";
        return a.autoTrade ? "The AI placed " + props.size() + " order(s) for the next open."
            : "The AI has " + props.size() + " suggestion(s) for you to approve or decline.";
    }

    void execute(PaperAccount a, LocalDate today) {
        List<PaperOrder> pending = new ArrayList<>(orders.findByUserIdAndStatus(a.userId, "PENDING"));
        pending.sort(Comparator.comparing((PaperOrder o) -> o.side.equals("SELL") ? 0 : 1).thenComparing(o -> o.id));
        List<PaperPosition> pos = new ArrayList<>(positions.findByUserId(a.userId));
        for (PaperOrder o : pending) {
            if (!o.decidedOn.isBefore(today)) continue;
            PriceService.Bar bar = prices.bars(o.symbol).stream().filter(b -> b.date().equals(today)).findFirst().orElse(null);
            if (bar == null) {
                if (o.decidedOn.isBefore(today.minusDays(4))) {
                    o.status = "REJECTED";
                    o.reason = (o.reason == null ? "" : o.reason + " ") + "[expired]";
                    orders.save(o);
                }
                continue;
            }
            BigDecimal price = bar.open().multiply(o.side.equals("BUY") ? BigDecimal.ONE.add(SLIPPAGE) : BigDecimal.ONE.subtract(SLIPPAGE)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal value = price.multiply(BigDecimal.valueOf(o.qty));
            BigDecimal fee = fees(o.side, value);
            PaperPosition held = pos.stream().filter(p -> p.symbol.equals(o.symbol)).findFirst().orElse(null);
            if (o.side.equals("BUY")) {
                BigDecimal cost = value.add(fee);
                if (cost.compareTo(a.cash) > 0) {
                    o.status = "REJECTED";
                    o.reason = (o.reason == null ? "" : o.reason + " ") + "[not enough cash]";
                    orders.save(o);
                    continue;
                }
                a.cash = a.cash.subtract(cost);
                if (held == null) {
                    held = new PaperPosition();
                    held.userId = a.userId;
                    held.symbol = o.symbol;
                    held.qty = 0L;
                    held.avgCost = BigDecimal.ZERO;
                    pos.add(held);
                }
                BigDecimal totalCost = held.avgCost.multiply(BigDecimal.valueOf(held.qty)).add(value.add(fee));
                held.qty += o.qty;
                held.avgCost = totalCost.divide(BigDecimal.valueOf(held.qty), 4, RoundingMode.HALF_UP);
                positions.save(held);
            } else {
                if (held == null || held.qty < o.qty) {
                    o.status = "REJECTED";
                    o.reason = (o.reason == null ? "" : o.reason + " ") + "[not enough shares]";
                    orders.save(o);
                    continue;
                }
                a.cash = a.cash.add(value.subtract(fee));
                held.qty -= o.qty;
                if (held.qty == 0) {
                    positions.delete(held);
                    pos.remove(held);
                } else {
                    positions.save(held);
                }
            }
            o.status = "FILLED";
            o.fillPrice = price;
            o.fees = fee;
            o.filledOn = today;
            orders.save(o);
        }
        accounts.save(a);
    }

    void snapshot(PaperAccount a, LocalDate today) {
        List<PriceService.Bar> bench = prices.bars(BENCHMARK);
        if (bench.isEmpty() || !bench.get(bench.size() - 1).date().equals(today)) return;
        PaperSnapshot s = snapshots.findByUserIdAndDay(a.userId, today).orElseGet(PaperSnapshot::new);
        s.userId = a.userId;
        s.day = today;
        s.equity = equity(a, positions.findByUserId(a.userId));
        s.benchmark = bench.get(bench.size() - 1).close();
        snapshots.save(s);
    }

    @Scheduled(initialDelay = 120000, fixedDelay = 900000)
    void tick() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) return;
        LocalDate today = now.toLocalDate();
        LocalTime t = now.toLocalTime();
        for (PaperAccount a : accounts.findAll()) {
            try {
                if (t.isAfter(LocalTime.of(9, 30))) execute(a, today);
                if (t.isAfter(LocalTime.of(16, 0))) {
                    snapshot(a, today);
                    decide(a, today, false);
                }
            } catch (Exception e) {
                log.warn("Paper trading step failed for user {}: {}", a.userId, e.toString());
            }
        }
    }
}

record PositionView(String symbol, BigDecimal qty, BigDecimal avgCost, BigDecimal price, BigDecimal value, BigDecimal pnl) {}

record OrderView(Long id, String symbol, String side, BigDecimal qty, String status, String reason, LocalDate decidedOn,
                 BigDecimal fillPrice, BigDecimal fees, BigDecimal amount) {}

record SnapView(LocalDate day, BigDecimal equity, BigDecimal benchmark) {}

record PaperView(BigDecimal cash, BigDecimal startCash, BigDecimal startBenchmark, BigDecimal equity, BigDecimal returnPct,
                 BigDecimal benchmarkPct, List<PositionView> positions, List<OrderView> orders, List<SnapView> history,
                 String lastSummary, LocalDate lastDecisionOn, boolean autoTrade, String strategy) {}

@RestController
@RequestMapping("/api/paper")
class PaperController {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PaperEngine engine;
    private final LlmClient llm;
    private final PaperAccountRepository accounts;
    private final PaperPositionRepository positions;
    private final PaperOrderRepository orders;
    private final PaperSnapshotRepository snapshots;

    PaperController(PaperEngine engine, LlmClient llm, PaperAccountRepository accounts, PaperPositionRepository positions,
                    PaperOrderRepository orders, PaperSnapshotRepository snapshots) {
        this.engine = engine;
        this.llm = llm;
        this.accounts = accounts;
        this.positions = positions;
        this.orders = orders;
        this.snapshots = snapshots;
    }

    private static Long uid(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }

    @GetMapping("/status")
    Map<String, Boolean> status(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("available", llm.enabled(), "started", accounts.existsById(uid(jwt)));
    }

    record AmountIn(BigDecimal amount) {}

    record AutoIn(boolean enabled) {}

    private PaperAccount account(Jwt jwt) {
        return accounts.findById(uid(jwt)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/start") @ResponseStatus(HttpStatus.NO_CONTENT)
    void start(@AuthenticationPrincipal Jwt jwt, @RequestBody AmountIn in) {
        if (!llm.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The AI is not set up on the server yet");
        engine.start(uid(jwt), in.amount());
    }

    @PostMapping("/deposit") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deposit(@AuthenticationPrincipal Jwt jwt, @RequestBody AmountIn in) { engine.deposit(account(jwt), in.amount()); }

    record StrategyIn(String text) {}

    @PostMapping("/strategy") @ResponseStatus(HttpStatus.NO_CONTENT)
    void strategy(@AuthenticationPrincipal Jwt jwt, @RequestBody StrategyIn in) {
        PaperAccount a = account(jwt);
        a.strategy = CryptoEngine.cleanStrategy(in.text());
        accounts.save(a);
    }

    @PostMapping("/auto") @ResponseStatus(HttpStatus.NO_CONTENT)
    void auto(@AuthenticationPrincipal Jwt jwt, @RequestBody AutoIn in) { engine.setAuto(account(jwt), in.enabled()); }

    @PostMapping("/orders/{id}/approve") @ResponseStatus(HttpStatus.NO_CONTENT)
    void approve(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) { engine.approve(account(jwt), id, LocalDate.now(IST)); }

    @PostMapping("/orders/{id}/decline") @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) { engine.decline(account(jwt), id); }

    @PostMapping("/reset") @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@AuthenticationPrincipal Jwt jwt) { engine.reset(uid(jwt)); }

    @PostMapping("/run")
    Map<String, String> run(@AuthenticationPrincipal Jwt jwt) {
        PaperAccount a = accounts.findById(uid(jwt)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return Map.of("message", engine.decide(a, LocalDate.now(IST), true));
    }

    @GetMapping
    PaperView view(@AuthenticationPrincipal Jwt jwt) {
        PaperAccount a = accounts.findById(uid(jwt)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<PaperPosition> pos = positions.findByUserId(a.userId);
        List<PositionView> pv = new ArrayList<>();
        for (PaperPosition p : pos) {
            BigDecimal px = engine.lastClose(p.symbol);
            BigDecimal value = px == null ? null : px.multiply(BigDecimal.valueOf(p.qty)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal pnl = value == null ? null : value.subtract(p.avgCost.multiply(BigDecimal.valueOf(p.qty))).setScale(2, RoundingMode.HALF_UP);
            pv.add(new PositionView(p.symbol, BigDecimal.valueOf(p.qty), p.avgCost, px, value, pnl));
        }
        BigDecimal equity = engine.equity(a, pos);
        BigDecimal ret = equity.subtract(a.startCash).multiply(BigDecimal.valueOf(100)).divide(a.startCash, 2, RoundingMode.HALF_UP);
        BigDecimal benchNow = engine.lastClose(PaperEngine.BENCHMARK);
        BigDecimal benchPct = a.startBenchmark == null || benchNow == null || a.startBenchmark.signum() == 0 ? null
            : benchNow.subtract(a.startBenchmark).multiply(BigDecimal.valueOf(100)).divide(a.startBenchmark, 2, RoundingMode.HALF_UP);
        List<OrderView> ov = orders.findTop30ByUserIdOrderByIdDesc(a.userId).stream()
            .map(o -> new OrderView(o.id, o.symbol, o.side, BigDecimal.valueOf(o.qty), o.status, o.reason, o.decidedOn, o.fillPrice, o.fees, null)).toList();
        List<SnapView> hv = snapshots.findByUserIdOrderByDayAsc(a.userId).stream().map(s -> new SnapView(s.day, s.equity, s.benchmark)).toList();
        return new PaperView(a.cash, a.startCash, a.startBenchmark, equity, ret, benchPct, pv, ov, hv, a.lastSummary, a.lastDecisionOn, a.autoTrade, a.strategy);
    }
}
