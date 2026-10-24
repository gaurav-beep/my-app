package com.example.app;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PaperSharedTest {
    @Test
    void amountMustBeWithinLimits() {
        assertEquals(new BigDecimal("25000.00"), PaperLimits.checkAmount(new BigDecimal("25000")));
        assertThrows(ResponseStatusException.class, () -> PaperLimits.checkAmount(new BigDecimal("999")));
        assertThrows(ResponseStatusException.class, () -> PaperLimits.checkAmount(new BigDecimal("10000001")));
        assertThrows(ResponseStatusException.class, () -> PaperLimits.checkAmount(null));
    }

    @Test
    void stripsMarkdownFencesFromModelOutput() {
        assertEquals("{\"a\":1}", LlmClient.stripFences("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", LlmClient.stripFences("{\"a\":1}"));
    }
}
