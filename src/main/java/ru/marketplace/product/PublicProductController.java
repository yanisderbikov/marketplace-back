package ru.marketplace.product;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.product.dto.PublicProductDTO;

import java.util.List;

/** Товары витрины /shop/{slug}: без авторизации (правило /api/public/** в WebSecurityConfig). */
@RestController
@RequestMapping("/api/public/shops/{slug}/products")
@RequiredArgsConstructor
@Tag(name = "StorefrontProducts", description = "Товары витрины магазина")
public class PublicProductController {

    private final ProductService productService;

    @Operation(summary = "Товары витрины",
            description = "Только включённые товары включённого магазина, в порядке каталога. Нет/выключен магазин — 404")
    @GetMapping
    public ResponseEntity<List<PublicProductDTO>> list(@PathVariable("slug") String slug) {
        return ResponseEntity.ok(productService.getStorefrontProducts(slug));
    }

    @Operation(summary = "Товар витрины по id",
            description = "Выключенный, чужой или несуществующий товар — 404")
    @GetMapping("/{id}")
    public ResponseEntity<PublicProductDTO> get(@PathVariable("slug") String slug,
                                                @PathVariable("id") String id) {
        return ResponseEntity.ok(productService.getStorefrontProduct(slug, id));
    }
}
