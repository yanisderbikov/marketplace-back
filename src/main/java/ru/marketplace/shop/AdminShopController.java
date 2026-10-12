package ru.marketplace.shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.shop.dto.AdminShopActiveRequestDTO;
import ru.marketplace.shop.dto.AdminShopDTO;

import java.util.List;
import java.util.UUID;

/** Доступ — только супер-админам из ADMIN_SUPER_EMAILS: правило /api/admin/** в WebSecurityConfig. */
@RestController
@RequestMapping("/api/admin/shops")
@RequiredArgsConstructor
@Tag(name = "AdminShops", description = "Магазины платформы (только супер-админ из ADMIN_SUPER_EMAILS)")
public class AdminShopController {

    private final AdminShopService adminShopService;

    @Operation(summary = "Все магазины платформы",
            description = "Новые сверху; с владельцем, числом товаров, оплаченными заказами и готовностью принимать заказы",
            security = @SecurityRequirement(name = "Bearer"))
    @GetMapping
    public ResponseEntity<List<AdminShopDTO>> list() {
        return ResponseEntity.ok(adminShopService.list());
    }

    @Operation(summary = "Включить или выключить магазин",
            description = "Выключенный магазин: витрина 404, оформление заказа 409. Мерчант по-прежнему входит в админку",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/{id}/active")
    public ResponseEntity<AdminShopDTO> setActive(@PathVariable("id") UUID id,
                                                  @Valid @RequestBody AdminShopActiveRequestDTO request) {
        return ResponseEntity.ok(adminShopService.setActive(id, request.getActive()));
    }
}
