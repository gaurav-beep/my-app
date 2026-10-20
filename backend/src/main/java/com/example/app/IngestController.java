package com.example.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ingest")
class IngestController {
    record SmsIn(String message, String date) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AppUserRepository users;
    private final ExpenseRepository expenses;

    private final ObjectMapper mapper;

    IngestController(AppUserRepository users, ExpenseRepository expenses, ObjectMapper mapper) {
        this.users = users;
        this.expenses = expenses;
        this.mapper = mapper;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private AppUser me(Jwt jwt) {
        return users.findById(Long.valueOf(jwt.getSubject())).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    @GetMapping("/status")
    Map<String, Boolean> status(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("enabled", me(jwt).ingestTokenHash != null);
    }

    @PostMapping("/token")
    Map<String, String> createToken(@AuthenticationPrincipal Jwt jwt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AppUser u = me(jwt);
        u.ingestTokenHash = sha256(token);
        users.save(u);
        return Map.of("token", token);
    }

    @DeleteMapping("/token") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeToken(@AuthenticationPrincipal Jwt jwt) {
        AppUser u = me(jwt);
        u.ingestTokenHash = null;
        users.save(u);
    }

    @PostMapping("/sms")
    ResponseEntity<Map<String, Object>> sms(
            @RequestHeader(value = "X-Ingest-Token", required = false) String headerToken,
            @RequestParam(value = "token", required = false) String queryToken,
            @RequestParam(value = "date", required = false) String queryDate,
            @RequestBody String raw) {
        SmsIn body = readBody(raw, queryDate);
        String token = headerToken != null ? headerToken : queryToken;
        AppUser user = token == null || token.isBlank() ? null : users.findByIngestTokenHash(sha256(token.trim())).orElse(null);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        Optional<SmsParser.Parsed> parsed = SmsParser.parse(body.message());
        if (parsed.isEmpty()) return ResponseEntity.ok(Map.of("status", "ignored"));

        String ref = sha256(body.message().strip().toLowerCase());
        if (expenses.existsByUserIdAndExternalRef(user.id, ref)) return ResponseEntity.ok(Map.of("status", "duplicate"));

        Expense e = new Expense();
        e.userId = user.id;
        e.amount = parsed.get().amount();
        e.description = parsed.get().description();
        e.category = parsed.get().category();
        e.spentOn = parseDate(body.date());
        e.source = "sms";
        e.externalRef = ref;
        e = expenses.save(e);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "added", "id", e.id));
    }

    private SmsIn readBody(String raw, String queryDate) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("{")) {
            try {
                SmsIn in = mapper.readValue(text, SmsIn.class);
                return new SmsIn(in.message(), in.date() != null ? in.date() : queryDate);
            } catch (Exception ignored) {
                // not valid JSON (for example an SMS containing quotes): treat the whole body as the message
            }
        }
        return new SmsIn(text, queryDate);
    }

    private static LocalDate parseDate(String s) {
        try {
            if (s != null && s.length() >= 10) return LocalDate.parse(s.substring(0, 10));
        } catch (Exception ignored) {
            // fall back to today
        }
        return LocalDate.now(IST);
    }
}
