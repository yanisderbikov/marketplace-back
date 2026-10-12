package ru.marketplace.shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.shop.dto.PublicShopCardDTO;
import ru.marketplace.shop.dto.PublicShopDTO;
import ru.marketplace.shop.dto.SlugAvailabilityDTO;

import java.util.List;

/** Публичные данные магазинов: главная платформы, шапка/подвал витрины и проверка адреса на онбординге. */
@RestController
@RequestMapping("/api/public/shops")
@RequiredArgsConstructor
@Tag(name = "PublicShop", description = "Магазины платформы для покупателей")
public class PublicShopController {

    private final ShopService shopService;

    @Operation(summary = "Витрины на главной",
            description = "Включённые магазины, у которых продаётся хотя бы один товар, новые сверху")
    @GetMapping
    public ResponseEntity<List<PublicShopCardDTO>> list() {
        return ResponseEntity.ok(shopService.getPublicShops());
    }

    @Operation(summary = "Свободен ли адрес витрины",
            description = "Для живой проверки на онбординге: available=false и готовый текст причины — "
                    + "адрес не по правилам, зарезервирован или занят")
    @GetMapping("/slug-available")
    public ResponseEntity<SlugAvailabilityDTO> slugAvailable(@RequestParam("slug") String slug) {
        return ResponseEntity.ok(shopService.checkSlug(slug));
    }

    @Operation(summary = "Магазин по адресу витрины",
            description = "Оформление, контакты и реквизиты продавца. Нет или выключен — 404")
    @GetMapping("/{slug}")
    public ResponseEntity<PublicShopDTO> get(@PathVariable("slug") String slug) {
        return ResponseEntity.ok(shopService.getPublicShop(slug));
    }
}
