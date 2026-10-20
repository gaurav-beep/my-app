package com.example.app;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "coindcx_link")
class CoinDcxLink {
    @Id
    @Column(name = "user_id")
    Long userId;
    @Column(name = "api_key_enc")
    String apiKeyEnc;
    @Column(name = "api_secret_enc")
    String apiSecretEnc;
}

interface CoinDcxLinkRepository extends JpaRepository<CoinDcxLink, Long> {}

@Service
class CoinDcxService {
    private final RestClient client = PriceService.client("https://api.coindcx.com");

    Map<String, BigDecimal> balances(String key, String secret) {
        String body = "{\"timestamp\":" + System.currentTimeMillis() + "}";
        JsonNode n = client.post().uri("/exchange/v1/users/balances")
            .header("X-AUTH-APIKEY", key).header("X-AUTH-SIGNATURE", sign(secret, body))
            .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        if (n == null || !n.isArray()) throw new IllegalStateException("Unexpected response from CoinDCX");
        Map<String, BigDecimal> out = new TreeMap<>();
        for (JsonNode x : n) {
            String cur = x.path("currency").asText().toUpperCase();
            BigDecimal total = new BigDecimal(x.path("balance").asText("0")).add(new BigDecimal(x.path("locked_balance").asText("0")));
            if (!cur.isBlank() && !cur.equals("INR") && total.signum() > 0) out.merge(cur, total, BigDecimal::add);
        }
        return out;
    }

    private static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

@RestController
@RequestMapping("/api/coindcx")
class CoinDcxController {
    record KeyIn(@NotBlank @Size(max = 200) String apiKey, @NotBlank @Size(max = 200) String apiSecret) {}

    private final SecretBox box;
    private final CoinDcxService dcx;
    private final CoinDcxLinkRepository links;
    private final HoldingRepository holdings;

    CoinDcxController(SecretBox box, CoinDcxService dcx, CoinDcxLinkRepository links, HoldingRepository holdings) {
        this.box = box;
        this.dcx = dcx;
        this.links = links;
        this.holdings = holdings;
    }

    private static Long uid(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }

    private int sync(Long userId, String key, String secret) {
        Map<String, BigDecimal> balances;
        try {
            balances = dcx.balances(key, secret);
        } catch (RestClientException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "CoinDCX rejected the request. Check the key and its permissions.");
        }
        List<Holding> existing = holdings.findByUserIdAndType(userId, "coindcx");
        List<Holding> stale = new ArrayList<>();
        for (Holding h : existing) {
            if (!balances.containsKey(h.symbol)) stale.add(h);
        }
        holdings.deleteAll(stale);
        for (Map.Entry<String, BigDecimal> b : balances.entrySet()) {
            Holding h = existing.stream().filter(x -> x.symbol.equals(b.getKey())).findFirst().orElseGet(() -> {
                Holding n = new Holding();
                n.userId = userId;
                n.type = "coindcx";
                n.symbol = b.getKey();
                n.name = b.getKey() + " (CoinDCX)";
                return n;
            });
            h.units = b.getValue();
            holdings.save(h);
        }
        return balances.size();
    }

    @GetMapping("/status")
    Map<String, Boolean> status(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("available", box.enabled(), "linked", links.existsById(uid(jwt)));
    }

    @PutMapping("/key")
    Map<String, Integer> connect(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody KeyIn in) {
        if (!box.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CoinDCX sync is not configured on the server");
        String key = in.apiKey().trim();
        String secret = in.apiSecret().trim();
        int n = sync(uid(jwt), key, secret);
        CoinDcxLink link = new CoinDcxLink();
        link.userId = uid(jwt);
        link.apiKeyEnc = box.encrypt(key);
        link.apiSecretEnc = box.encrypt(secret);
        links.save(link);
        return Map.of("synced", n);
    }

    @PostMapping("/sync")
    Map<String, Integer> resync(@AuthenticationPrincipal Jwt jwt) {
        CoinDcxLink link = links.findById(uid(jwt)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return Map.of("synced", sync(uid(jwt), box.decrypt(link.apiKeyEnc), box.decrypt(link.apiSecretEnc)));
    }

    @DeleteMapping("/key") @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(@AuthenticationPrincipal Jwt jwt) {
        holdings.deleteAll(holdings.findByUserIdAndType(uid(jwt), "coindcx"));
        links.deleteById(uid(jwt));
    }
}
