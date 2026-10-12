package ru.marketplace.order;

import ru.marketplace.order.dto.SalesReportDTO;

import java.time.LocalDate;

public interface SalesReportService {

    /**
     * Продажи магазина текущего мерчанта: оплаченные заказы с paidAt в днях [from..to] по Москве.
     * from позже to — 400.
     */
    SalesReportDTO getReport(LocalDate from, LocalDate to);
}
