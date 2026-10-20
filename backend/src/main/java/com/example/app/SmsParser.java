package com.example.app;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SmsParser {
    record Parsed(BigDecimal amount, String description, String category) {}

    private static final String NUM = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)";
    private static final String CUR = "(?:rs\\.?|inr|\u20B9)\\s*";
    private static final String DEBIT_WORDS = "debited|spent|paid|sent|withdrawn|purchase[d]?|debit";

    private static final Pattern OTP = Pattern.compile("(?i)\\b(otp|one[- ]time password|verification code)\\b");
    private static final Pattern FAILED = Pattern.compile("(?i)\\b(failed|declined|unsuccessful|insufficient|not processed|reversed)\\b");
    private static final Pattern DEBIT = Pattern.compile("(?i)\\b(" + DEBIT_WORDS + ")\\b");

    private static final Pattern AMOUNT_BEFORE_KEYWORD = Pattern.compile("(?i)" + CUR + NUM + "[^0-9]{0,40}?\\b(?:" + DEBIT_WORDS + ")\\b");
    private static final Pattern AMOUNT_AFTER_KEYWORD = Pattern.compile("(?i)\\b(?:" + DEBIT_WORDS + ")\\b\\s*(?:by|with|of)?\\s*(?:" + CUR + ")?" + NUM);
    private static final Pattern AMOUNT_ANY = Pattern.compile("(?i)" + CUR + NUM);

    private static final Pattern VPA = Pattern.compile("(?i)\\bVPA\\s+(\\S+)");
    private static final Pattern PLACE = Pattern.compile(
        "(?i)\\b(?:at|to|towards)\\s+(.+?)(?=\\s+on\\b|\\s+via\\b|\\s+ref|\\s+upi\\b|\\s+avl\\b|\\s+bal\\b|\\s+using\\b|\\s+from\\b|\\s*\\(|\\.\\s|\\.$|,|$)");

    private static final Map<Pattern, String> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put(Pattern.compile("(?i)swiggy|zomato|restaurant|cafe|pizza|burger|dominos|kfc|mcdonald|bakery|food|dine|starbucks|chai"), "Food");
        CATEGORIES.put(Pattern.compile("(?i)uber|ola\\b|rapido|irctc|metro|petrol|fuel|bpcl|hpcl|indianoil|redbus|fastag|parking|railway"), "Transport");
        CATEGORIES.put(Pattern.compile("(?i)electric|bill|recharge|jio|airtel|vodafone|vi\\b|broadband|gas|water|insurance|rent|emi|bescom|tata ?power"), "Bills");
        CATEGORIES.put(Pattern.compile("(?i)amazon|flipkart|myntra|ajio|meesho|mart|bazaar|bazar|store|mall|retail|nykaa|dmart|reliance"), "Shopping");
        CATEGORIES.put(Pattern.compile("(?i)pharm|apollo|hospital|medic|clinic|diagnostic|lab\\b|1mg|netmeds|doctor|health"), "Health");
        CATEGORIES.put(Pattern.compile("(?i)netflix|spotify|hotstar|bookmyshow|prime video|youtube|inox|pvr|gaming|steam|cinema"), "Entertainment");
    }

    static Optional<Parsed> parse(String message) {
        if (message == null || message.isBlank() || message.length() > 1000) return Optional.empty();
        if (OTP.matcher(message).find() || FAILED.matcher(message).find() || !DEBIT.matcher(message).find()) return Optional.empty();

        BigDecimal amount = firstAmount(message);
        if (amount == null || amount.signum() <= 0) return Optional.empty();

        String description = merchant(message);
        return Optional.of(new Parsed(amount, description, category(description + " " + message)));
    }

    private static BigDecimal firstAmount(String m) {
        for (Pattern p : new Pattern[] { AMOUNT_BEFORE_KEYWORD, AMOUNT_AFTER_KEYWORD, AMOUNT_ANY }) {
            Matcher x = p.matcher(m);
            while (x.find()) {
                try {
                    BigDecimal v = new BigDecimal(x.group(1).replace(",", ""));
                    if (v.signum() > 0) return v;
                } catch (NumberFormatException ignored) {
                    // try next match
                }
            }
        }
        return null;
    }

    private static String merchant(String m) {
        Matcher v = VPA.matcher(m);
        String raw = v.find() ? v.group(1) : null;
        if (raw == null) {
            Matcher p = PLACE.matcher(m);
            if (p.find()) raw = p.group(1);
        }
        if (raw == null) return "Bank debit";
        raw = raw.replaceAll("[\\s.,;:)(]+$", "").replaceAll("^[\\s.,;:(]+", "").trim();
        if (raw.isEmpty() || raw.matches("(?i)(a/c|account|your|you|card|bank)\\b.*")) return "Bank debit";
        return raw.length() > 60 ? raw.substring(0, 60) : raw;
    }

    private static String category(String text) {
        for (Map.Entry<Pattern, String> e : CATEGORIES.entrySet()) {
            if (e.getKey().matcher(text).find()) return e.getValue();
        }
        return "Other";
    }

    private SmsParser() {}
}
