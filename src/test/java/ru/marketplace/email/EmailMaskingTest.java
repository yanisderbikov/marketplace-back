package ru.marketplace.email;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailMaskingTest {

    @Test
    void keepsFirstLetterAndDomainOnly() {
        assertEquals("s***@example.com", EmailMasking.mask("shop@example.com"));
        assertEquals("s***@example.com", EmailMasking.mask(" shop@example.com "));
        assertEquals("***", EmailMasking.mask("no-at-sign"));
        assertEquals("***", EmailMasking.mask("@example.com"));
        assertEquals("null", EmailMasking.mask(null));
    }
}
