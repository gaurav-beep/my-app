package com.example.app;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class SmsParserTest {
    private static SmsParser.Parsed ok(String sms) {
        return SmsParser.parse(sms).orElseThrow(() -> new AssertionError("not parsed: " + sms));
    }

    @Test
    void upiDebitWithVpa() {
        var p = ok("Rs.500.00 debited from a/c **1234 on 20-10-26 to VPA zomato@paytm (UPI Ref No 1234567890). Not you? Call 1800");
        assertEquals(new BigDecimal("500.00"), p.amount());
        assertEquals("zomato@paytm", p.description());
        assertEquals("Food", p.category());
    }

    @Test
    void cardSpend() {
        var p = ok("Spent Rs.1,350.50 On HDFC Bank Card x1234 At SWIGGY On 2026-10-20:12:30:11. Avl bal Rs 9,000");
        assertEquals(new BigDecimal("1350.50"), p.amount());
        assertEquals("SWIGGY", p.description());
        assertEquals("Food", p.category());
    }

    @Test
    void sbiStyleWithoutCurrencyPrefix() {
        var p = ok("Dear UPI user A/C X1234 debited by 250.0 on date 20Oct26 trf to Uber India Refno 123456");
        assertEquals(new BigDecimal("250.0"), p.amount());
        assertEquals("Uber India", p.description());
        assertEquals("Transport", p.category());
    }

    @Test
    void unknownMerchantIsOther() {
        var p = ok("INR 99.00 debited from your account for payment at Local Shop on 20-10-26");
        assertEquals(new BigDecimal("99.00"), p.amount());
        assertEquals("Other", p.category());
    }

    @Test
    void ignoresCreditsOtpsAndFailures() {
        assertTrue(SmsParser.parse("Rs.5000 credited to your a/c XX1234 on 20-10-26").isEmpty());
        assertTrue(SmsParser.parse("123456 is your OTP for transaction of Rs.500 debited").isEmpty());
        assertTrue(SmsParser.parse("Transaction of Rs.500 failed. Amount debited will be reversed").isEmpty());
        assertTrue(SmsParser.parse("Hello, your parcel has arrived").isEmpty());
        assertTrue(SmsParser.parse(null).isEmpty());
    }
}
