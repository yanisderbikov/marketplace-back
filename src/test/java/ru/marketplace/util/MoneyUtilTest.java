package ru.marketplace.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyUtilTest {

    @Test
    void formatRubles_wholeRubles() {
        assertEquals("1 190 ₽", MoneyUtil.formatRubles(1_190_00));
        assertEquals("0 ₽", MoneyUtil.formatRubles(0));
        assertEquals("990 ₽", MoneyUtil.formatRubles(990_00));
        assertEquals("1 000 000 ₽", MoneyUtil.formatRubles(1_000_000_00));
    }

    @Test
    void formatRubles_withKopecks() {
        assertEquals("1 190,50 ₽", MoneyUtil.formatRubles(1_190_50));
        assertEquals("0,05 ₽", MoneyUtil.formatRubles(5));
    }

    @Test
    void formatRubles_negativeKeepsSign() {
        assertEquals("-1 190,50 ₽", MoneyUtil.formatRubles(-1_190_50));
        assertEquals("-0,50 ₽", MoneyUtil.formatRubles(-50));
    }

    @Test
    void kopecksToString_twoDecimals() {
        assertEquals("1190.50", MoneyUtil.kopecksToString(1_190_50));
        assertEquals("1190.00", MoneyUtil.kopecksToString(1_190_00));
        assertEquals("0.01", MoneyUtil.kopecksToString(1));
    }

    @Test
    void stringToKopecks_parsesDotAndComma() {
        assertEquals(119_050, MoneyUtil.stringToKopecks("1190.50"));
        assertEquals(119_050, MoneyUtil.stringToKopecks(" 1190,5 "));
        assertEquals(119_000, MoneyUtil.stringToKopecks("1190"));
    }

    @Test
    void stringToKopecks_rejectsEmptyAndGarbage() {
        assertThrows(IllegalArgumentException.class, () -> MoneyUtil.stringToKopecks(null));
        assertThrows(IllegalArgumentException.class, () -> MoneyUtil.stringToKopecks("  "));
        assertThrows(NumberFormatException.class, () -> MoneyUtil.stringToKopecks("abc"));
    }
}
