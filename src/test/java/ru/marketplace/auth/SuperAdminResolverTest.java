package ru.marketplace.auth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuperAdminResolverTest {

    @Test
    void parsesCommaSeparatedListNormalized() {
        SuperAdminResolver resolver = new SuperAdminResolver(" Owner@Example.com , second@example.com,,  ");
        assertEquals(Set.of("owner@example.com", "second@example.com"), resolver.emails());
    }

    @Test
    void matchesIgnoringCaseAndSpaces() {
        SuperAdminResolver resolver = new SuperAdminResolver("owner@example.com,second@example.com");
        assertTrue(resolver.isSuperAdmin("owner@example.com"));
        assertTrue(resolver.isSuperAdmin("  OWNER@Example.COM "));
        assertTrue(resolver.isSuperAdmin("second@example.com"));
    }

    @Test
    void otherEmailsAreNotSuperAdmins() {
        SuperAdminResolver resolver = new SuperAdminResolver("owner@example.com");
        assertFalse(resolver.isSuperAdmin("merchant@example.com"));
        assertFalse(resolver.isSuperAdmin("owner@example.co"));
        assertFalse(resolver.isSuperAdmin(""));
        assertFalse(resolver.isSuperAdmin(null));
    }

    @Test
    void emptyListFailsOnStartup() {
        assertThrows(IllegalStateException.class, () -> new SuperAdminResolver(""));
        assertThrows(IllegalStateException.class, () -> new SuperAdminResolver(" , ,"));
        assertThrows(IllegalStateException.class, () -> new SuperAdminResolver(null));
    }

    @Test
    void normalize() {
        assertEquals("a@b.ru", SuperAdminResolver.normalize("  A@B.RU "));
        assertNull(SuperAdminResolver.normalize(null));
    }
}
