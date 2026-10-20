package com.example.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Service
class CasService {
    private static final Logger log = LoggerFactory.getLogger(CasService.class);

    private final RestClient client;
    private final String apiKey;
    private final ObjectMapper mapper;

    CasService(@Value("${app.casparser-key}") String apiKey, ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(70000);
        this.client = RestClient.builder().baseUrl("https://api.casparser.in").requestFactory(f).build();
    }

    boolean enabled() { return !apiKey.isBlank(); }

    String requestOtp(String pan, String boId, String dob) {
        JsonNode n = call("/v4/cdsl/fetch", Map.of("pan", pan, "bo_id", boId, "dob", dob));
        String id = n.path("session_id").asText("");
        if (id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not request the OTP");
        return id;
    }

    JsonNode fetchLatest(String sessionId, String otp, String pan) {
        JsonNode files = call("/v4/cdsl/fetch/" + sessionId + "/verify", Map.of("otp", otp, "num_periods", 1));
        JsonNode first = files.path("files").path(0);
        if (first.path("url").asText("").isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No statement was returned");
        return call("/v4/smart/parse", Map.of("pdf_url", first.path("url").asText(), "password", pan));
    }

    private JsonNode call(String path, Map<String, Object> body) {
        try {
            JsonNode n = client.post().uri(path).header("x-api-key", apiKey).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class);
            if (n == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty response from the statement service");
            if ("failed".equalsIgnoreCase(n.path("status").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, clean(n.path("msg").asText("Request failed")));
            }
            return n;
        } catch (RestClientResponseException e) {
            String msg = "Statement service error";
            try {
                msg = clean(mapper.readTree(e.getResponseBodyAsString()).path("msg").asText(msg));
            } catch (Exception ignored) {
                // keep the generic message
            }
            log.warn("CAS Parser {} returned {}", path, e.getStatusCode().value());
            if (e.getStatusCode().value() == 403) msg = "This feature is not available on the statement service plan";
            throw new ResponseStatusException(e.getStatusCode().is4xxClientError() ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY, msg);
        } catch (RestClientException e) {
            log.warn("CAS Parser {} failed: {}", path, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach the statement service");
        }
    }

    private static String clean(String s) {
        String t = s.replaceAll("[\\r\\n]+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private static final class Acc {
        String name;
        BigDecimal units = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        boolean costKnown = true;
    }

    private static BigDecimal num(JsonNode n) {
        return n != null && n.isNumber() ? n.decimalValue() : null;
    }

    private static void add(Map<String, Acc> m, JsonNode x, BigDecimal cost) {
        String isin = x.path("isin").asText("");
        BigDecimal units = num(x.path("units"));
        if (isin.isBlank() || units == null || units.signum() <= 0) return;
        BigDecimal value = num(x.path("value"));
        if (value == null && num(x.path("nav")) != null) value = units.multiply(num(x.path("nav")));
        Acc a = m.computeIfAbsent(isin, k -> new Acc());
        if (a.name == null) a.name = x.path("name").asText(isin);
        a.units = a.units.add(units);
        a.value = a.value.add(value == null ? BigDecimal.ZERO : value);
        if (cost == null || cost.signum() <= 0) a.costKnown = false;
        else a.cost = a.cost.add(cost);
    }

    static List<Holding> toHoldings(Long userId, JsonNode r) {
        Map<String, Acc> m = new LinkedHashMap<>();
        for (JsonNode d : r.path("demat_accounts")) {
            for (String key : List.of("equities", "demat_mutual_funds")) {
                for (JsonNode x : d.path("holdings").path(key)) add(m, x, null);
            }
        }
        for (JsonNode f : r.path("mutual_funds")) {
            for (JsonNode s : f.path("schemes")) add(m, s, num(s.path("cost")));
        }
        String asOf = r.path("meta").path("statement_period").path("to").asText("");
        List<Holding> out = new ArrayList<>();
        for (Map.Entry<String, Acc> e : m.entrySet()) {
            Acc a = e.getValue();
            Holding h = new Holding();
            h.userId = userId;
            h.type = "cas";
            h.source = "cas";
            h.symbol = e.getKey();
            h.name = a.name.length() > 255 ? a.name.substring(0, 255) : a.name;
            h.units = a.units;
            h.invested = a.costKnown ? a.cost.setScale(2, RoundingMode.HALF_UP) : null;
            h.snapPrice = a.value.signum() > 0 ? a.value.divide(a.units, 6, RoundingMode.HALF_UP) : null;
            h.snapAt = asOf;
            out.add(h);
        }
        return out;
    }
}

@RestController
@RequestMapping("/api/cas")
class CasController {
    record StartIn(@NotBlank @Pattern(regexp = "[A-Za-z]{5}[0-9]{4}[A-Za-z]") String pan,
                   @NotBlank @Pattern(regexp = "[0-9]{16}") String boId,
                   @NotBlank @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String dob) {}

    record VerifyIn(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{8,128}") String sessionId,
                    @NotBlank @Pattern(regexp = "[0-9]{4,8}") String otp,
                    @NotBlank @Pattern(regexp = "[A-Za-z]{5}[0-9]{4}[A-Za-z]") String pan) {}

    private final CasService cas;
    private final HoldingRepository holdings;

    CasController(CasService cas, HoldingRepository holdings) {
        this.cas = cas;
        this.holdings = holdings;
    }

    private static Long uid(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }

    private void requireEnabled() {
        if (!cas.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Statement import is not set up on the server");
    }

    @GetMapping("/status")
    Map<String, Boolean> status() {
        return Map.of("available", cas.enabled());
    }

    @PostMapping("/cdsl/start")
    Map<String, String> start(@Valid @RequestBody StartIn in) {
        requireEnabled();
        return Map.of("sessionId", cas.requestOtp(in.pan().toUpperCase(), in.boId(), in.dob()));
    }

    @PostMapping("/cdsl/verify")
    Map<String, Integer> verify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VerifyIn in) {
        requireEnabled();
        Long userId = uid(jwt);
        List<Holding> fresh = CasService.toHoldings(userId, cas.fetchLatest(in.sessionId(), in.otp(), in.pan().toUpperCase()));
        Map<String, Holding> existing = new LinkedHashMap<>();
        for (Holding h : holdings.findByUserIdAndSource(userId, "cas")) existing.put(h.symbol, h);
        for (Holding f : fresh) {
            Holding h = existing.remove(f.symbol);
            if (h == null) h = f;
            else {
                h.name = f.name;
                h.units = f.units;
                if (f.invested != null) h.invested = f.invested;
                h.snapPrice = f.snapPrice;
                h.snapAt = f.snapAt;
            }
            holdings.save(h);
        }
        holdings.deleteAll(existing.values());
        return Map.of("synced", fresh.size());
    }
}

@org.springframework.web.bind.annotation.RestControllerAdvice
class ApiErrors {
    @org.springframework.web.bind.annotation.ExceptionHandler(ResponseStatusException.class)
    org.springframework.http.ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "" : e.getReason()));
    }
}
