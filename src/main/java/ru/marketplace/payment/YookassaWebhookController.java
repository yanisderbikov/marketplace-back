package ru.marketplace.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP-уведомления ЮKassa (payment.succeeded, payment.canceled, refund.succeeded). Адрес мерчант
 * указывает в своём кабинете ЮKassa. Телу не доверяем — статус перепроверяется запросом к API.
 * ЮKassa ждёт 200, иначе повторяет уведомление в течение суток.
 */
@RestController
@RequestMapping("/api/public/payment")
@RequiredArgsConstructor
@Tag(name = "YookassaWebhook", description = "Уведомления ЮKassa о статусе платежей магазинов")
public class YookassaWebhookController {

    private final OrderPaymentService orderPaymentService;

    @Operation(summary = "Уведомление ЮKassa",
            description = "Берёт из тела id платежа, запрашивает платёж у ЮKassa ключами магазина заказа и применяет "
                    + "статус. 200 — обработано или платёж не наш, 503 — ЮKassa не ответила (она повторит)")
    @PostMapping("/yookassa-webhook")
    public ResponseEntity<Void> yookassaWebhook(@RequestBody(required = false) String rawBody) {
        boolean handled = rawBody == null || rawBody.isBlank()
                || orderPaymentService.acceptYookassaNotification(rawBody);
        return ResponseEntity.status(handled ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
