package ru.marketplace.shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.shop.dto.DomainResolveDTO;

/**
 * Свои домены витрин. Одна ручка на двух клиентов: фронт на чужом домене узнаёт, чью витрину показать,
 * а гейтвей (Caddy on_demand_tls ask) — можно ли выпускать сертификат на пришедший домен.
 */
@RestController
@RequestMapping("/api/public/domains")
@RequiredArgsConstructor
@Tag(name = "PublicDomain", description = "Свои домены витрин")
public class PublicDomainController {

    private final ShopDomainService shopDomainService;

    @Operation(summary = "Магазин по своему домену",
            description = "domain — хост как есть (регистр, порт, www не важны). Включённый магазин с этим "
                    + "доменом — 200 { slug, domain }; нет или магазин выключен — 404")
    @GetMapping("/resolve")
    public ResponseEntity<DomainResolveDTO> resolve(@RequestParam("domain") String domain) {
        return ResponseEntity.ok(shopDomainService.resolve(domain));
    }
}
