package ru.marketplace.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.common.PageDTO;
import ru.marketplace.order.dto.FulfillmentUpdateRequestDTO;
import ru.marketplace.order.dto.MerchantOrderDTO;
import ru.marketplace.order.dto.SalesReportDTO;

import java.time.LocalDate;

/**
 * Заказы и аналитика продаж магазина текущего мерчанта. Какой магазин — решает CurrentMerchant
 * по токену, поэтому shopSlug (как в anyforms /api/orders/shop-report) здесь не передаётся вовсе.
 */
@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
@Tag(name = "MerchantOrders", description = "Заказы магазина мерчанта и отчёт по продажам")
public class MerchantOrderController {

    private final MerchantOrderService merchantOrderService;
    private final SalesReportService salesReportService;

    @Operation(summary = "Заказы магазина",
            description = "Новые сверху. payment: PAID | AWAITING_PAYMENT | FAILED | CANCELED | ALL (по умолчанию PAID); "
                    + "fulfillment: NEW | SHIPPED | DONE | CANCELLED | ALL (по умолчанию ALL); page с 0; size до 100. "
                    + "Неизвестный статус — 400",
            security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/orders")
    public ResponseEntity<PageDTO<MerchantOrderDTO>> list(
            @RequestParam(name = "payment", defaultValue = "PAID") String payment,
            @RequestParam(name = "fulfillment", defaultValue = "ALL") String fulfillment,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(merchantOrderService.list(payment, fulfillment, page, size));
    }

    @Operation(summary = "Сменить статус исполнения и трек-номер",
            description = "Только свой (иначе 404) и только оплаченный (иначе 409) заказ. "
                    + "trackingNumber до 64 символов, пусто — убрать",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/orders/{publicId}/fulfillment")
    public ResponseEntity<MerchantOrderDTO> updateFulfillment(@PathVariable("publicId") String publicId,
                                                              @RequestBody FulfillmentUpdateRequestDTO request) {
        return ResponseEntity.ok(merchantOrderService.updateFulfillment(publicId, request));
    }

    @Operation(summary = "Отчёт по продажам магазина за период",
            description = "Оплаченные заказы с датой оплаты в днях [from..to] по Москве включительно: "
                    + "число заказов, товаров, сумма и разбивка по товарам. from позже to — 400",
            security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/analytics/sales")
    public ResponseEntity<SalesReportDTO> getSalesReport(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(salesReportService.getReport(from, to));
    }
}
