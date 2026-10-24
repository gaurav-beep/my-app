package com.example.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class PaperLimits {
    static final BigDecimal MIN_AMOUNT = new BigDecimal("1000");
    static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");

    static BigDecimal checkAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount between 1,000 and 1,00,00,000");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private PaperLimits() {}
}

record AmountIn(BigDecimal amount) {}

record AutoIn(boolean enabled) {}

record StrategyIn(String text) {}

@Service
class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final String key;
    private final String model;
    private final ObjectMapper mapper;
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    LlmClient(@Value("${app.gemini-key}") String key, @Value("${app.gemini-model}") String model, ObjectMapper mapper) {
        this.key = key;
        this.model = model;
        this.mapper = mapper;
    }

    boolean enabled() { return !key.isBlank(); }

    Optional<JsonNode> generateJson(String prompt) {
        Map<String, Object> body = Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
            "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.3));
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("LLM request could not be built: {}", e.toString());
            return Optional.empty();
        }
        List<String> models = new java.util.ArrayList<>(List.of(model));
        for (String fallback : FALLBACK_MODELS) {
            if (!models.contains(fallback)) models.add(fallback);
        }
        for (String m : models) {
            try {
                java.net.URI uri = java.net.URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent");
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(22))
                    .header("Content-Type", "application/json").header("x-goog-api-key", key)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build();
                java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200) {
                    JsonNode r = mapper.readTree(resp.body());
                    String text = r.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
                    if (text.isBlank()) {
                        log.warn("LLM {} returned no text. Finish reason: {}", m, r.path("candidates").path(0).path("finishReason").asText("unknown"));
                        continue;
                    }
                    return Optional.of(mapper.readTree(stripFences(text)));
                }
                String snippet = resp.body() == null ? "" : resp.body().replaceAll("\\s+", " ");
                log.warn("LLM model {} failed: HTTP {} {}", m, code, snippet.length() > 300 ? snippet.substring(0, 300) : snippet);
                if (code == 400 || code == 401 || code == 403) return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (java.io.IOException e) {
                log.warn("LLM model {} failed: {}", m, e.toString());
            } catch (Exception e) {
                log.warn("LLM model {} response could not be used: {}", m, e.toString());
            }
        }
        return Optional.empty();
    }

    private static final List<String> FALLBACK_MODELS = List.of("gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite");

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

record PositionView(String symbol, BigDecimal qty, BigDecimal avgCost, BigDecimal price, BigDecimal value, BigDecimal pnl) {}

record OrderView(Long id, String symbol, String side, BigDecimal qty, String status, String reason, LocalDate decidedOn,
                 BigDecimal fillPrice, BigDecimal fees, BigDecimal amount, BigDecimal triggerPrice, String triggerDir, String source) {}

record SnapView(LocalDate day, BigDecimal equity, BigDecimal benchmark) {}

record PaperView(BigDecimal cash, BigDecimal startCash, BigDecimal startBenchmark, BigDecimal equity, BigDecimal returnPct,
                 BigDecimal benchmarkPct, List<PositionView> positions, List<OrderView> orders, List<SnapView> history,
                 String lastSummary, LocalDate lastDecisionOn, boolean autoTrade, String strategy) {}
