package com.example.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
                   BigDecimal price, String priceDate, BigDecimal value, BigDecimal gain, BigDecimal gainPct) {
    static HoldingView of(Holding h, PriceService.Quote q) {
        if (q == null) return new HoldingView(h.id, h.type, h.symbol, h.name, h.units, h.invested, null, null, null, null, null);
        BigDecimal value = h.units.multiply(q.price()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gain = value.subtract(h.invested);
        BigDecimal pct = h.invested.signum() > 0 ? gain.multiply(BigDecimal.valueOf(100)).divide(h.invested, 2, RoundingMode.HALF_UP) : null;
        return new HoldingView(h.id, h.type, h.symbol, h.name, h.units, h.invested, q.price(), q.asOf(), value, gain, pct);
    }
}

record Portfolio(List<HoldingView> holdings, BigDecimal totalInvested, BigDecimal totalValue, boolean complete) {}

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

    @GetMapping("/holdings")
    Portfolio list(@AuthenticationPrincipal Jwt jwt) {
        List<Holding> all = repo.findByUserIdOrderByIdAsc(uid(jwt));
        Map<String, PriceService.Quote> crypto = prices.crypto(
            all.stream().filter(h -> h.type.equals("crypto")).map(h -> h.symbol).distinct().collect(Collectors.toList()));
        List<HoldingView> views = all.stream().map(h -> {
            Optional<PriceService.Quote> q = h.type.equals("mf") ? prices.mfNav(h.symbol) : Optional.ofNullable(crypto.get(h.symbol));
            return HoldingView.of(h, q.orElse(null));
        }).toList();
        BigDecimal invested = views.stream().map(HoldingView::invested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal value = views.stream().map(v -> v.value() != null ? v.value() : v.invested()).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean complete = views.stream().allMatch(v -> v.value() != null);
        return new Portfolio(views, invested, value, complete);
    }

    @PostMapping("/holdings") @ResponseStatus(HttpStatus.CREATED)
    Long create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Holding h) {
        h.id = null;
        h.userId = uid(jwt);
        return repo.save(h).id;
    }

    @PutMapping("/holdings/{id}")
    void update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody Holding h) {
        Holding x = owned(id, jwt);
        x.units = h.units;
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
            case "crypto" -> prices.searchCrypto(term);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }
}
