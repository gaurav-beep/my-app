package com.example.app;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "kite_link")
class KiteLink {
    @Id
    @Column(name = "user_id")
    Long userId;
    @Column(name = "api_key_enc")
    String apiKeyEnc;
    @Column(name = "api_secret_enc")
    String apiSecretEnc;
    @Column(name = "access_token_enc")
    String accessTokenEnc;
    @Column(name = "token_at")
    LocalDateTime tokenAt;
    String state;
    @Column(name = "state_at")
    LocalDateTime stateAt;
}

interface KiteLinkRepository extends JpaRepository<KiteLink, Long> {
    java.util.Optional<KiteLink> findByState(String state);
}

class KiteTokenExpired extends RuntimeException {}

@Service
class KiteService {
    private static final Logger log = LoggerFactory.getLogger(KiteService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final RestClient client = PriceService.client("https://api.kite.trade");

    static boolean tokenValid(KiteLink l) {
        if (l.accessTokenEnc == null || l.tokenAt == null) return false;
        ZonedDateTime at = l.tokenAt.atZone(ZoneOffset.UTC).withZoneSameInstant(IST);
        ZonedDateTime expiry = at.toLocalDate().atTime(LocalTime.of(6, 0)).atZone(IST);
        if (!at.toLocalTime().isBefore(LocalTime.of(6, 0))) expiry = expiry.plusDays(1);
        return ZonedDateTime.now(IST).isBefore(expiry);
    }

    String exchangeToken(String apiKey, String apiSecret, String requestToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("api_key", apiKey);
        form.add("request_token", requestToken);
        form.add("checksum", sha256(apiKey + requestToken + apiSecret));
        JsonNode n = client.post().uri("/session/token").header("X-Kite-Version", "3")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        String token = n == null ? "" : n.path("data").path("access_token").asText("");
        if (token.isBlank()) throw new IllegalStateException("No access token returned");
        return token;
    }

    private JsonNode get(String path, String apiKey, String accessToken) {
        try {
            JsonNode n = client.get().uri(path).header("X-Kite-Version", "3")
                .header(HttpHeaders.AUTHORIZATION, "token " + apiKey + ":" + accessToken).retrieve().body(JsonNode.class);
            return n == null ? null : n.path("data");
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 403) throw new KiteTokenExpired();
            throw e;
        }
    }

    List<Holding> fetch(Long userId, String apiKey, String accessToken) {
        List<Holding> out = new ArrayList<>();
        JsonNode stocks = get("/portfolio/holdings", apiKey, accessToken);
        if (stocks != null && stocks.isArray()) {
            for (JsonNode x : stocks) {
                String ex = x.path("exchange").asText();
                if (!ex.equals("NSE") && !ex.equals("BSE")) continue;
                BigDecimal units = new BigDecimal(x.path("quantity").asText("0")).add(new BigDecimal(x.path("t1_quantity").asText("0")));
                if (units.signum() <= 0) continue;
                Holding h = base(userId, "stock", x.path("tradingsymbol").asText() + (ex.equals("BSE") ? ".BO" : ".NS"), x.path("tradingsymbol").asText(), units);
                h.invested = units.multiply(new BigDecimal(x.path("average_price").asText("0"))).setScale(2, java.math.RoundingMode.HALF_UP);
                h.snapPrice = new BigDecimal(x.path("last_price").asText("0"));
                out.add(h);
            }
        }
        try {
            JsonNode mfs = get("/mf/holdings", apiKey, accessToken);
            if (mfs != null && mfs.isArray()) {
                for (JsonNode x : mfs) {
                    BigDecimal units = new BigDecimal(x.path("quantity").asText("0"));
                    if (units.signum() <= 0) continue;
                    Holding h = base(userId, "kitemf", x.path("tradingsymbol").asText(), x.path("fund").asText(x.path("tradingsymbol").asText()), units);
                    h.invested = units.multiply(new BigDecimal(x.path("average_price").asText("0"))).setScale(2, java.math.RoundingMode.HALF_UP);
                    h.snapPrice = new BigDecimal(x.path("last_price").asText("0"));
                    h.snapAt = x.path("last_price_date").asText("");
                    out.add(h);
                }
            }
        } catch (KiteTokenExpired e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("Kite mutual fund holdings unavailable: {}", e.toString());
        }
        return out;
    }

    private static Holding base(Long userId, String type, String symbol, String name, BigDecimal units) {
        Holding h = new Holding();
        h.userId = userId;
        h.type = type;
        h.symbol = symbol;
        h.name = name.length() > 255 ? name.substring(0, 255) : name;
        h.units = units;
        h.source = "kite";
        return h;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

@RestController
@RequestMapping("/api/kite")
class KiteController {
    record AppIn(@NotBlank @Size(max = 100) String apiKey, @NotBlank @Size(max = 100) String apiSecret) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretBox box;
    private final KiteService kite;
    private final KiteLinkRepository links;
    private final HoldingRepository holdings;

    KiteController(SecretBox box, KiteService kite, KiteLinkRepository links, HoldingRepository holdings) {
        this.box = box;
        this.kite = kite;
        this.links = links;
        this.holdings = holdings;
    }

    private static Long uid(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }

    private KiteLink link(Jwt jwt) {
        return links.findById(uid(jwt)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private int sync(KiteLink l) {
        List<Holding> fresh;
        try {
            fresh = kite.fetch(l.userId, box.decrypt(l.apiKeyEnc), box.decrypt(l.accessTokenEnc));
        } catch (KiteTokenExpired e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Zerodha login required");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Zerodha request failed");
        }
        Map<String, Holding> existing = new LinkedHashMap<>();
        for (Holding h : holdings.findByUserIdAndSource(l.userId, "kite")) existing.put(h.type + ":" + h.symbol, h);
        for (Holding f : fresh) {
            Holding h = existing.remove(f.type + ":" + f.symbol);
            if (h == null) h = f;
            else {
                h.name = f.name;
                h.units = f.units;
                h.invested = f.invested;
                h.snapPrice = f.snapPrice;
                h.snapAt = f.snapAt;
            }
            holdings.save(h);
        }
        holdings.deleteAll(existing.values());
        return fresh.size();
    }

    @GetMapping("/status")
    Map<String, Boolean> status(@AuthenticationPrincipal Jwt jwt) {
        KiteLink l = links.findById(uid(jwt)).orElse(null);
        return Map.of("available", box.enabled(), "linked", l != null, "connected", l != null && KiteService.tokenValid(l));
    }

    @PutMapping("/app") @ResponseStatus(HttpStatus.NO_CONTENT)
    void saveApp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AppIn in) {
        if (!box.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        KiteLink l = links.findById(uid(jwt)).orElseGet(KiteLink::new);
        l.userId = uid(jwt);
        l.apiKeyEnc = box.encrypt(in.apiKey().trim());
        l.apiSecretEnc = box.encrypt(in.apiSecret().trim());
        l.accessTokenEnc = null;
        l.tokenAt = null;
        links.save(l);
    }

    @PostMapping("/login")
    Map<String, String> login(@AuthenticationPrincipal Jwt jwt) {
        KiteLink l = link(jwt);
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        l.state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        l.stateAt = LocalDateTime.now(ZoneOffset.UTC);
        links.save(l);
        String params = URLEncoder.encode("state=" + l.state, StandardCharsets.UTF_8);
        return Map.of("url", "https://kite.zerodha.com/connect/login?v=3&api_key="
            + URLEncoder.encode(box.decrypt(l.apiKeyEnc), StandardCharsets.UTF_8) + "&redirect_params=" + params);
    }

    @GetMapping("/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String request_token,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String state) {
        String result = "error";
        KiteLink l = state == null || state.isBlank() ? null : links.findByState(state).orElse(null);
        if (l != null && l.stateAt != null && l.stateAt.isAfter(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(15))
            && "success".equals(status) && request_token != null && !request_token.isBlank()) {
            l.state = null;
            l.stateAt = null;
            try {
                String token = kite.exchangeToken(box.decrypt(l.apiKeyEnc), box.decrypt(l.apiSecretEnc), request_token);
                l.accessTokenEnc = box.encrypt(token);
                l.tokenAt = LocalDateTime.now(ZoneOffset.UTC);
                links.save(l);
                result = "ok";
            } catch (RuntimeException e) {
                links.save(l);
            }
            if (result.equals("ok")) {
                try {
                    sync(l);
                } catch (RuntimeException e) {
                    // connected, but the first sync failed: the user can press Sync now
                }
            }
        } else if (l != null) {
            l.state = null;
            l.stateAt = null;
            links.save(l);
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/?kite=" + result).build();
    }

    @PostMapping("/sync")
    Map<String, Integer> resync(@AuthenticationPrincipal Jwt jwt) {
        KiteLink l = link(jwt);
        if (!KiteService.tokenValid(l)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Zerodha login required");
        return Map.of("synced", sync(l));
    }

    @DeleteMapping("/app") @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(@AuthenticationPrincipal Jwt jwt) {
        holdings.deleteAll(holdings.findByUserIdAndSource(uid(jwt), "kite"));
        links.deleteById(uid(jwt));
    }
}
