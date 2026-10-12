package ru.marketplace.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicIdGeneratorTest {

    @Test
    void generate_sixUppercaseAlphanumerics() {
        for (int i = 0; i < 1_000; i++) {
            String id = PublicIdGenerator.generate();
            assertEquals(6, id.length());
            assertTrue(id.matches("[A-Z0-9]{6}"), id);
        }
    }

    @Test
    void generate_isRandom() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            ids.add(PublicIdGenerator.generate());
        }
        // 36^6 ≈ 2 млрд вариантов — на тысяче повторов практически не бывает
        assertTrue(ids.size() > 990);
    }

    @Test
    void generateUnique_skipsTakenIds() {
        AtomicInteger calls = new AtomicInteger();
        Set<String> taken = new HashSet<>();
        String id = PublicIdGenerator.generateUnique(candidate -> {
            // Первые два кандидата «заняты»
            if (calls.incrementAndGet() <= 2) {
                taken.add(candidate);
                return true;
            }
            return false;
        });
        assertEquals(3, calls.get());
        assertTrue(id.matches("[A-Z0-9]{6}"));
        taken.forEach(t -> assertNotEquals(t, id));
    }

    @Test
    void generateUnique_givesUpWhenEverythingIsTaken() {
        assertThrows(IllegalStateException.class, () -> PublicIdGenerator.generateUnique(candidate -> true));
    }
}
