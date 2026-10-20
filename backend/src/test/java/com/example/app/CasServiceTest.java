package com.example.app;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class CasServiceTest {
    private static final String SAMPLE = """
        {
          "meta": {"cas_type": "CDSL", "statement_period": {"from": "2024-01-01", "to": "2024-01-31"}},
          "demat_accounts": [
            {"demat_type": "CDSL", "holdings": {
              "equities": [
                {"isin": "INE002A01018", "name": "Reliance Industries Ltd", "units": 100.0, "value": 250000.00},
                {"isin": "INE000000001", "name": "Zero Units Ltd", "units": 0, "value": 0}
              ],
              "demat_mutual_funds": [
                {"isin": "INF000000002", "name": "Some Fund", "units": 10.5, "value": 1050.0}
              ]}},
            {"demat_type": "NSDL", "holdings": {
              "equities": [
                {"isin": "INE002A01018", "name": "Reliance Industries Ltd", "units": 50.0, "value": 125000.00}
              ]}}
          ],
          "mutual_funds": [
            {"folio_number": "1", "schemes": [
              {"isin": "INF000000003", "name": "Folio Fund", "units": 20.0, "nav": 15.0, "value": 300.0, "cost": 250.0}
            ]}
          ]
        }
        """;

    @Test
    void mapsHoldingsAndMergesSameIsinAcrossAccounts() throws Exception {
        List<Holding> out = CasService.toHoldings(7L, new ObjectMapper().readTree(SAMPLE));
        assertEquals(3, out.size());
        Holding reliance = out.stream().filter(h -> h.symbol.equals("INE002A01018")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("150").compareTo(reliance.units));
        assertEquals(0, new BigDecimal("2500").compareTo(reliance.snapPrice));
        assertNull(reliance.invested);
        assertEquals("2024-01-31", reliance.snapAt);
        assertEquals("cas", reliance.source);
        assertEquals(7L, reliance.userId);
    }

    @Test
    void usesCostWhenKnownAndSkipsZeroUnits() throws Exception {
        List<Holding> out = CasService.toHoldings(7L, new ObjectMapper().readTree(SAMPLE));
        assertTrue(out.stream().noneMatch(h -> h.symbol.equals("INE000000001")));
        Holding folio = out.stream().filter(h -> h.symbol.equals("INF000000003")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("250").compareTo(folio.invested));
        assertEquals(0, new BigDecimal("15").compareTo(folio.snapPrice));
    }
}
