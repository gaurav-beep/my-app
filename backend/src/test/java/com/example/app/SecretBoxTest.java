package com.example.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SecretBoxTest {
    @Test
    void roundTripsAndRandomizesCiphertext() {
        var box = new SecretBox("some-long-test-secret");
        assertTrue(box.enabled());
        String a = box.encrypt("my-api-secret");
        String b = box.encrypt("my-api-secret");
        assertNotEquals(a, b);
        assertFalse(a.contains("my-api-secret"));
        assertEquals("my-api-secret", box.decrypt(a));
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String enc = new SecretBox("key-one").encrypt("value");
        assertThrows(IllegalStateException.class, () -> new SecretBox("key-two").decrypt(enc));
    }

    @Test
    void blankKeyDisablesFeature() {
        assertFalse(new SecretBox("").enabled());
    }
}
