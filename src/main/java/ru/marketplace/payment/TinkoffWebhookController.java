package ru.marketplace.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Нотификации Т-Банка о смене статуса платежа. Тело берём сырой строкой: Token считается по всем
 * корневым полям JSON, в том числе тем, которых нет в нашем DTO. Банк ждёт ровно «OK», иначе повторяет.
 */
@RestController
@RequestMapping("/api/public/payment")
@RequiredArgsConstructor
@Tag(name = "TinkoffWebhook", description = "Нотификации Т-Банка о статусе платежей магазинов")
public class TinkoffWebhookController {

    private final OrderPaymentService orderPaymentService;

    @Operation(summary = "Нотификация Т-Банка",
            description = "Проверяет терминал и Token паролем магазина заказа, сумму, и применяет статус. "
                    + "Принято — 200 «OK», иначе 400 «fail» (банк повторит)")
    @PostMapping("/tinkoff-webhook")
    public ResponseEntity<String> tinkoffWebhook(@RequestBody(required = false) String rawBody) {
        boolean accepted = rawBody != null && !rawBody.isBlank()
                && orderPaymentService.acceptTinkoffNotification(rawBody);
        // Content-Type задан явно: ответ text/plain при любом Accept банка
        return ResponseEntity.status(accepted ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_PLAIN)
                .body(accepted ? "OK" : "fail");
    }
}
