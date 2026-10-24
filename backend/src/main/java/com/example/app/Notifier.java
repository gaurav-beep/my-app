package com.example.app;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
class Notifier {
    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final String key;
    private final String from;
    private final String appUrl;
    private final ObjectMapper mapper;
    private final AppUserRepository users;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notifier");
        t.setDaemon(true);
        return t;
    });
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    Notifier(@Value("${app.resend-key}") String key, @Value("${app.mail-from}") String from, @Value("${app.app-url}") String appUrl,
             ObjectMapper mapper, AppUserRepository users) {
        this.key = key;
        this.from = from;
        this.appUrl = appUrl;
        this.mapper = mapper;
        this.users = users;
    }

    boolean configured() { return !key.isBlank(); }

    String appUrl() { return appUrl; }

    void sendAsync(Long userId, String subject, String body) {
        if (!configured()) return;
        executor.submit(() -> {
            String err = sendNow(userId, subject, body);
            if (err != null) log.warn("Email to user {} not sent: {}", userId, err);
        });
    }

    String sendNow(Long userId, String subject, String body) {
        if (!configured()) return "Email is not set up on the server yet.";
        AppUser u = users.findById(userId).orElse(null);
        if (u == null || u.email == null || u.email.isBlank()) return "No email address on your account.";
        try {
            String payload = mapper.writeValueAsString(Map.of("from", from, "to", List.of(u.email), "subject", subject, "text", body));
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.resend.com/emails"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build();
            java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) return null;
            String detail = resp.body();
            try {
                JsonNode n = mapper.readTree(resp.body());
                if (n.path("message").isTextual()) detail = n.path("message").asText();
            } catch (Exception ignored) {
                // keep the raw body
            }
            return "HTTP " + resp.statusCode() + ": " + (detail.length() > 240 ? detail.substring(0, 240) : detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        } catch (Exception e) {
            return e.toString();
        }
    }

    static String money(BigDecimal v) {
        return v == null ? "n/a" : "Rs " + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    static String tradesBody(List<CryptoOrder> orders, BigDecimal equity, BigDecimal cash, String appUrl, String headline) {
        StringBuilder sb = new StringBuilder(headline).append("\n\n");
        for (CryptoOrder o : orders) {
            String side = "BUY".equals(o.side) ? "Bought" : "Sold";
            if ("SUGGESTED".equals(o.status)) side = "BUY".equals(o.side) ? "Suggested buy of" : "Suggested sell of";
            sb.append("- ").append(side).append(' ').append(o.qty == null ? "" : o.qty.stripTrailingZeros().toPlainString()).append(' ').append(o.symbol);
            if (o.fillPrice != null) sb.append(" at ").append(money(o.fillPrice)).append(" (fee ").append(money(o.fees)).append(')');
            else if (o.amountInr != null) sb.append(", about ").append(money(o.amountInr));
            if (!"FILLED".equals(o.status) && !"SUGGESTED".equals(o.status)) sb.append(" [").append(o.status.toLowerCase()).append(']');
            sb.append('\n');
            if (o.reason != null && !o.reason.isBlank()) sb.append("  Reason: ").append(o.reason.replaceAll("\\s+", " ")).append('\n');
        }
        sb.append("\nPortfolio value: ").append(money(equity)).append("\nCash: ").append(money(cash)).append("\n\nOpen the app: ").append(appUrl)
          .append("\n\nThis is a paper-trading simulation with virtual money. You can turn these emails off in the app under Account.\n");
        return sb.toString();
    }
}
