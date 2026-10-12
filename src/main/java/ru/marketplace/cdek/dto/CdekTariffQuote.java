package ru.marketplace.cdek.dto;

import java.math.BigDecimal;

/** Ответ калькулятора СДЭК: стоимость в рублях и срок доставки в днях (сроки могут не прийти). */
public record CdekTariffQuote(BigDecimal deliverySum, Integer periodMin, Integer periodMax) {
}
