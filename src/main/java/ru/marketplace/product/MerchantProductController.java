package ru.marketplace.product;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.product.dto.MerchantProductDTO;
import ru.marketplace.product.dto.PhotoOrderRequestDTO;
import ru.marketplace.product.dto.PhotoRegisterRequestDTO;
import ru.marketplace.product.dto.ProductOrderRequestDTO;
import ru.marketplace.product.dto.ProductSaveRequestDTO;
import ru.marketplace.storage.dto.PresignUploadRequestDTO;
import ru.marketplace.storage.dto.PresignUploadResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * Каталог своего магазина (вкладка «Каталог» админки). Доступ — ROLE_MERCHANT (правило /api/merchant/** в
 * WebSecurityConfig); магазин всегда берётся из токена, поэтому товар чужого магазина отвечает 404.
 */
@RestController
@RequestMapping("/api/merchant/products")
@RequiredArgsConstructor
@Tag(name = "MerchantProducts", description = "Каталог товаров своего магазина")
public class MerchantProductController {

    private final ProductService productService;
    private final ProductPhotoService productPhotoService;

    @Operation(summary = "Все товары магазина",
            description = "Включая выключенные из продажи, в порядке каталога (orderNumber)",
            security = @SecurityRequirement(name = "Bearer"))
    @GetMapping
    public ResponseEntity<List<MerchantProductDTO>> list() {
        return ResponseEntity.ok(productService.getMerchantProducts());
    }

    @Operation(summary = "Изменить порядок каталога",
            description = "id всех товаров магазина в нужном порядке; orderNumber = индекс. Набор не совпал с товарами магазина — 400",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/order")
    public ResponseEntity<List<MerchantProductDTO>> reorder(@Valid @RequestBody ProductOrderRequestDTO request) {
        return ResponseEntity.ok(productService.reorder(request.getProductIds()));
    }

    @Operation(summary = "Товар по id",
            description = "Для страницы редактирования товара; чужой или несуществующий — 404",
            security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/{id}")
    public ResponseEntity<MerchantProductDTO> get(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(productService.getMerchantProduct(id));
    }

    @Operation(summary = "Создать товар",
            description = "Товар встаёт в конец каталога. Фото загружаются уже после создания: presign → PUT в S3 → POST /{id}/photos",
            security = @SecurityRequirement(name = "Bearer"))
    @PostMapping
    public ResponseEntity<MerchantProductDTO> create(@Valid @RequestBody ProductSaveRequestDTO request) {
        return ResponseEntity.ok(productService.create(request));
    }

    @Operation(summary = "Обновить товар",
            description = "Полная замена полей карточки. variants — полная замена набора: с id — обновить, без id — создать, "
                    + "не перечисленные удаляются",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/{id}")
    public ResponseEntity<MerchantProductDTO> update(@PathVariable("id") UUID id,
                                                     @Valid @RequestBody ProductSaveRequestDTO request) {
        return ResponseEntity.ok(productService.update(id, request));
    }

    @Operation(summary = "Удалить товар",
            description = "Фото удаляются из S3; в оформленных заказах остаётся снапшот названия и цены",
            security = @SecurityRequirement(name = "Bearer"))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Presigned URL для загрузки фото товара",
            description = "Файл уходит из браузера сразу в S3-папку товара (PUT по uploadUrl с тем же Content-Type), "
                    + "бэкенд только подписывает. После загрузки — POST /{id}/photos с key. Требует CORS на бакете",
            security = @SecurityRequirement(name = "Bearer"))
    @PostMapping("/{id}/photos/presign")
    public ResponseEntity<PresignUploadResponseDTO> presignPhotoUpload(@PathVariable("id") UUID id,
                                                                       @Valid @RequestBody PresignUploadRequestDTO request) {
        return ResponseEntity.ok(productPhotoService.presign(id, request.getFilename(), request.getContentType(),
                request.getSize()));
    }

    @Operation(summary = "Привязать загруженное фото к товару",
            description = "Ключ должен лежать в папке этого товара (иначе 400); фото встаёт в конец; больше "
                    + ProductPhotoService.MAX_PHOTOS + " фото — 400",
            security = @SecurityRequirement(name = "Bearer"))
    @PostMapping("/{id}/photos")
    public ResponseEntity<MerchantProductDTO> registerPhoto(@PathVariable("id") UUID id,
                                                            @Valid @RequestBody PhotoRegisterRequestDTO request) {
        return ResponseEntity.ok(productPhotoService.register(id, request.getKey()));
    }

    @Operation(summary = "Удалить фото товара",
            description = "Удаляет фото из карточки и объект из S3",
            security = @SecurityRequirement(name = "Bearer"))
    @DeleteMapping("/{id}/photos/{photoId}")
    public ResponseEntity<MerchantProductDTO> deletePhoto(@PathVariable("id") UUID id,
                                                          @PathVariable("photoId") UUID photoId) {
        return ResponseEntity.ok(productPhotoService.delete(id, photoId));
    }

    @Operation(summary = "Изменить порядок фото товара",
            description = "id всех фото товара в нужном порядке; первое — главное фото на витрине",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/{id}/photos/order")
    public ResponseEntity<MerchantProductDTO> reorderPhotos(@PathVariable("id") UUID id,
                                                            @Valid @RequestBody PhotoOrderRequestDTO request) {
        return ResponseEntity.ok(productPhotoService.reorder(id, request.getPhotoIds()));
    }
}
