package ru.marketplace.cdek;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.cdek.dto.CdekPvzDTO;
import ru.marketplace.cdek.dto.DeliveryCostRequestDTO;
import ru.marketplace.cdek.dto.DeliveryCostResponseDTO;
import ru.marketplace.common.ClientIp;
import ru.marketplace.common.PublicRateLimiter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@Tag(name = "CDEK", description = "Интеграция со СДЭК: пункты выдачи заказов и расчёт доставки на витрине")
public class CdekPvzController {

    private final CdekPvzService cdekPvzService;
    private final CdekDeliveryCostService deliveryCostService;
    private final PublicRateLimiter publicRateLimiter;

    @Operation(summary = "Саджест ПВЗ СДЭК: текстовый поиск по улице/городу/региону по всем странам присутствия СДЭК (для дропдауна на чекауте и в настройках магазина)")
    @GetMapping("/cdek/pvz")
    public ResponseEntity<List<CdekPvzDTO>> pvz(@RequestParam(required = false) String query) {
        return ResponseEntity.ok(cdekPvzService.search(query));
    }

    @Operation(summary = "Расчёт стоимости и срока доставки СДЭК от ПВЗ отправки магазина до выбранного ПВЗ по позициям корзины (для чекаута)",
            description = "429 — лимит расчётов с одного IP за час")
    @PostMapping("/shops/{slug}/delivery-cost")
    public ResponseEntity<DeliveryCostResponseDTO> deliveryCost(@PathVariable String slug,
                                                                @Valid @RequestBody DeliveryCostRequestDTO request,
                                                                HttpServletRequest httpRequest) {
        publicRateLimiter.deliveryCost(ClientIp.of(httpRequest));
        return ResponseEntity.ok(deliveryCostService.calculate(slug, request));
    }

    /** Отказ СДЭК — ошибка внешнего сервиса (502), а не 500: фронт покажет текст СДЭК покупателю */
    @ExceptionHandler(CdekCalculationException.class)
    public ResponseEntity<Map<String, String>> handleCalculationError(CdekCalculationException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
