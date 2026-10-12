package ru.marketplace.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginCodeEmailTest {

    @Test
    void subjectHasLowercaseBrand() {
        assertEquals("Код для входа в marketplace", LoginCodeEmail.SUBJECT);
    }

    @Test
    void letterIsWrappedInPlatformLayoutWithCodeCell() {
        String html = LoginCodeEmail.build("048213", 10);

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains(">048213</td>"));
        assertTrue(html.contains("Он действует 10&nbsp;минут и работает один раз."));
        assertTrue(html.contains("© marketplace"));
    }

    @Test
    void minutesAreDeclined() {
        assertEquals("минуту", LoginCodeEmail.minutesWord(1));
        assertEquals("минуты", LoginCodeEmail.minutesWord(2));
        assertEquals("минуты", LoginCodeEmail.minutesWord(4));
        assertEquals("минут", LoginCodeEmail.minutesWord(5));
        assertEquals("минут", LoginCodeEmail.minutesWord(10));
        assertEquals("минут", LoginCodeEmail.minutesWord(11));
        assertEquals("минут", LoginCodeEmail.minutesWord(14));
        assertEquals("минуту", LoginCodeEmail.minutesWord(21));
        assertEquals("минуты", LoginCodeEmail.minutesWord(32));
        assertEquals("минут", LoginCodeEmail.minutesWord(111));
    }
}
