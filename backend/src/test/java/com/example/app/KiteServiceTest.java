package com.example.app;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class KiteServiceTest {
    private static KiteLink linkWithToken(LocalDateTime at) {
        KiteLink l = new KiteLink();
        l.accessTokenEnc = "x";
        l.tokenAt = at;
        return l;
    }

    @Test
    void freshTokenIsValidOldTokenIsNot() {
        assertTrue(KiteService.tokenValid(linkWithToken(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1))));
        assertFalse(KiteService.tokenValid(linkWithToken(LocalDateTime.now(ZoneOffset.UTC).minusDays(2))));
    }

    @Test
    void missingTokenIsInvalid() {
        assertFalse(KiteService.tokenValid(new KiteLink()));
    }

    @Test
    void checksumMatchesKnownSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", KiteService.sha256("abc"));
    }
}
