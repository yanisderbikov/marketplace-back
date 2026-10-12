package ru.marketplace.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.common.ClientIp;
import ru.marketplace.common.PublicRateLimiter;
import ru.marketplace.payment.dto.CheckoutRequestDTO;
import ru.marketplace.payment.dto.CheckoutResponseDTO;
import ru.marketplace.payment.dto.OrderStatusDTO;

/** Оформление заказа на витрине и статус оплаты — публичные ручки, токен не нужен. Ошибки — GlobalExceptionHandler. */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@Tag(name = "Checkout", description = "Оформление заказа и оплата через терминал Т-Банка магазина")
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final PublicRateLimiter publicRateLimiter;

    @Operation(summary = "Оформить заказ",
            description = "Считает сумму корзины по серверным ценам, создаёт заказ (AWAITING_PAYMENT) и платёж "
                    + "в Т-Банке терминалом магазина, возвращает ссылку на оплату. Доставка в сумму не входит. "
                    + "409 — магазин выключен или не настроен, 400 — ошибки корзины, 429 — лимит заказов с одного IP "
                    + "за час, 502 — Т-Банк не создал платёж")
    @PostMapping("/shops/{slug}/checkout")
    public ResponseEntity<CheckoutResponseDTO> checkout(@PathVariable("slug") String slug,
                                                        @Valid @RequestBody CheckoutRequestDTO request,
                                                        HttpServletRequest httpRequest) {
        publicRateLimiter.checkout(ClientIp.of(httpRequest));
        return ResponseEntity.ok(checkoutService.checkout(slug, request));
    }

    @Operation(summary = "Статус оплаты заказа",
            description = "Для страницы после оплаты: состав и сумма без персональных данных. Пока заказ ждёт "
                    + "оплаты, не чаще раза в 5 секунд сверяется с Т-Банком (GetState). 429 — лимит запросов с одного IP за час")
    @GetMapping("/orders/{publicId}/status")
    public ResponseEntity<OrderStatusDTO> status(@PathVariable("publicId") String publicId,
                                                 HttpServletRequest httpRequest) {
        publicRateLimiter.orderStatus(ClientIp.of(httpRequest));
        return ResponseEntity.ok(checkoutService.orderStatus(publicId));
    }
}
