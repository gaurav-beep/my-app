package com.example.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

record HoldingView(Long id, String type, String symbol, String name, BigDecimal units, BigDecimal invested,
                   BigDecimal price, String priceDate, BigDecimal value, BigDecimal gain, BigDecimal gainPct, String source) {
    static HoldingView of(Holding h, PriceService.Quote q) {
        if (q == null) return new HoldingView(h.id, h.type, h.symbol, h.name, h.units, h.invested, null, null, null, null, null, h.source);
        BigDecimal value = h.units.multiply(q.price()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gain = h.invested == null ? null : value.subtract(h.invested);
        BigDecimal pct = gain != null && h.invested.signum() > 0 ? gain.multiply(BigDecimal.valueOf(100)).divide(h.invested, 2, RoundingMode.HALF_UP) : null;
        return new HoldingView(h.id, h.type, h.symbol, h.name, h.units, h.invested, q.price(), q.asOf(), value, gain, pct, h.source);
    }
}

record Portfolio(List<HoldingView> holdings, BigDecimal totalInvested, BigDecimal totalValue, BigDecimal totalGain,
                 long unknownCost, boolean complete) {
    static Portfolio of(List<HoldingView> views) {
        BigDecimal invested = views.stream().filter(v -> v.invested() != null && v.gain() != null).map(HoldingView::invested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal value = views.stream().map(v -> v.value() != null ? v.value() : v.invested() != null ? v.invested() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gain = views.stream().map(HoldingView::gain).filter(g -> g != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        long unknown = views.stream().filter(v -> v.invested() == null).count();
        boolean complete = views.stream().allMatch(v -> v.value() != null);
        return new Portfolio(views, invested, value, gain, unknown, complete);
    }
}

@RestController
@RequestMapping("/api")
class HoldingController {
    private final HoldingRepository repo;
    private final PriceService prices;

    HoldingController(HoldingRepository repo, PriceService prices) {
        this.repo = repo;
        this.prices = prices;
    }

    private static Long uid(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }

    private Holding owned(Long id, Jwt jwt) {
        return repo.findByIdAndUserId(id, uid(jwt)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Optional<PriceService.Quote> quote(Holding h, Map<String, PriceService.Quote> crypto) {
        Optional<PriceService.Quote> live = switch (h.type) {
            case "mf" -> prices.mfNav(h.symbol);
            case "stock" -> prices.stock(h.symbol);
            case "coindcx" -> prices.coindcx(h.symbol);
            case "kitemf" -> Optional.empty();
            default -> Optional.ofNullable(crypto.get(h.symbol));
        };
        if (live.isEmpty() && h.snapPrice != null && h.snapPrice.signum() > 0) {
            return Optional.of(new PriceService.Quote(h.snapPrice, h.snapAt == null || h.snapAt.isBlank() ? "last sync" : h.snapAt, Instant.now()));
        }
        return live;
    }

    @GetMapping("/holdings")
    Portfolio list(@AuthenticationPrincipal Jwt jwt) {
        List<Holding> all = repo.findByUserIdOrderByIdAsc(uid(jwt));
        Map<String, PriceService.Quote> crypto = prices.crypto(
            all.stream().filter(h -> h.type.equals("crypto")).map(h -> h.symbol).distinct().collect(Collectors.toList()));
        return Portfolio.of(all.stream().map(h -> HoldingView.of(h, quote(h, crypto).orElse(null))).toList());
    }

    @PostMapping("/holdings") @ResponseStatus(HttpStatus.CREATED)
    Long create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Holding h) {
        if (h.invested == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invested is required");
        if (h.type.equals("kitemf")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported type");
        h.id = null;
        h.userId = uid(jwt);
        h.source = "manual";
        h.snapPrice = null;
        h.snapAt = null;
        return repo.save(h).id;
    }

    @PutMapping("/holdings/{id}")
    void update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody Holding h) {
        if (h.invested == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invested is required");
        Holding x = owned(id, jwt);
        if ("kite".equals(x.source)) return;
        if (!"coindcx".equals(x.type)) x.units = h.units;
        x.invested = h.invested;
        repo.save(x);
    }

    @DeleteMapping("/holdings/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) { repo.delete(owned(id, jwt)); }

    @GetMapping("/search/{type}")
    List<Map<String, String>> search(@PathVariable String type, @RequestParam String q) {
        String term = q.strip();
        if (term.length() < 2 || term.length() > 60) return List.of();
        return switch (type) {
            case "mf" -> prices.searchMf(term);
            case "stock" -> prices.searchStocks(term);
            case "crypto" -> prices.searchCrypto(term);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }
}
