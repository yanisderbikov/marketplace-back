package ru.marketplace.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Деньги везде в копейках (long); здесь — только перевод в строки для людей и провайдеров. */
public final class MoneyUtil {

    /** Минимальная сумма платежа у провайдеров (1 ₽) — дешевле товар не продать. */
    public static final long MIN_PAYABLE_KOPECKS = 100;

    private MoneyUtil() {
    }

    /** 119050 → "1190.50" (две цифры после точки, без группировки). */
    public static String kopecksToString(long kopecks) {
        return BigDecimal.valueOf(kopecks)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** «3 000 ₽» или «2 999,50 ₽» — для писем и сообщений пользователю. */
    public static String formatRubles(long kopecks) {
        String sign = kopecks < 0 ? "-" : "";
        long abs = Math.abs(kopecks);
        long rub = abs / 100;
        long kop = abs % 100;
        String grouped = Long.toString(rub).replaceAll("\\B(?=(\\d{3})+(?!\\d))", " ");
        return sign + (kop == 0 ? grouped + " ₽" : grouped + String.format(",%02d ₽", kop));
    }

    /** "1190.50" → 119050. Пусто — ошибка: сумма не может молча стать нулём. */
    public static long stringToKopecks(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Сумма не может быть пустой");
        }
        return new BigDecimal(value.trim().replace(',', '.'))
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
