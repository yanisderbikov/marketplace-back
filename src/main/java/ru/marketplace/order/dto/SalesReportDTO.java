package ru.marketplace.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/** Отчёт по продажам магазина за период: сколько товаров куплено и на какую сумму. */
@Schema(description = "Продажи магазина за период: дни по Москве, границы включительно")
public record SalesReportDTO(
        String shopSlug,
        String shopName,
        LocalDate from,
        LocalDate to,
        @Schema(description = "Оплаченных заказов за период") long ordersCount,
        @Schema(description = "Куплено товаров (сумма количеств по всем позициям)") long itemsCount,
        @Schema(description = "Общая сумма оплаченных заказов в копейках") long totalKopecks,
        @Schema(description = "Разбивка по товарам, самые прибыльные сверху") List<ProductSalesDTO> products
) {
}
