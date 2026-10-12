package ru.marketplace.payment.dto.yookassa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.marketplace.util.MoneyUtil;

/** Сумма ЮKassa: строка рублей с копейками («1190.50») и валюта. */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class YookassaAmount {

    public static final String RUB = "RUB";

    private String value;
    private String currency;

    public static YookassaAmount rub(long kopecks) {
        return new YookassaAmount(MoneyUtil.kopecksToString(kopecks), RUB);
    }

    /** Сумма в копейках; null — суммы нет или она не в рублях. */
    public Long toKopecks() {
        if (value == null || !RUB.equals(currency)) {
            return null;
        }
        return MoneyUtil.stringToKopecks(value);
    }
}
