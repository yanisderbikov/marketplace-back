package ru.marketplace.shop;

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
import ru.marketplace.shop.dto.CreateShopRequestDTO;
import ru.marketplace.shop.dto.ShopCdekRequestDTO;
import ru.marketplace.shop.dto.MerchantShopDTO;
import ru.marketplace.shop.dto.ShopDesignRequestDTO;
import ru.marketplace.shop.dto.ShopDomainRequestDTO;
import ru.marketplace.domain.dto.ShopDomainDTO;
import ru.marketplace.shop.dto.ShopImageRequestDTO;
import ru.marketplace.shop.dto.ShopPaymentProviderRequestDTO;
import ru.marketplace.shop.dto.ShopPaymentRequestDTO;
import ru.marketplace.shop.dto.ShopSettingsRequestDTO;
import ru.marketplace.shop.dto.ShopYookassaRequestDTO;
import ru.marketplace.storage.dto.PresignUploadRequestDTO;
import ru.marketplace.storage.dto.PresignUploadResponseDTO;

/**
 * Магазин текущего мерчанта. Какой именно магазин — решает CurrentMerchant по токену,
 * id магазина в запросах нет: чужой магазин отредактировать нельзя в принципе.
 */
@RestController
@RequestMapping("/api/merchant/shop")
@RequiredArgsConstructor
@Tag(name = "MerchantShop", description = "Онбординг, оформление витрины и настройки магазина мерчанта")
public class MerchantShopController {

    private final ShopService shopService;
    private final ShopImageService shopImageService;
    private final ShopDomainService shopDomainService;

    @Operation(summary = "Создать магазин (онбординг)",
            description = "Название, адрес витрины и цвет. Адрес после создания не меняется. "
                    + "409 — магазин уже есть или адрес занят, 400 — невалидные поля",
            security = @SecurityRequirement(name = "Bearer"))
    @PostMapping
    public ResponseEntity<MerchantShopDTO> create(@Valid @RequestBody CreateShopRequestDTO request) {
        return ResponseEntity.ok(shopService.create(request));
    }

    @Operation(summary = "Мой магазин",
            description = "Оформление, контакты, продавец, ПВЗ отправки, терминал (без пароля) и чего не хватает для приёма заказов. "
                    + "409 — магазин ещё не создан",
            security = @SecurityRequirement(name = "Bearer"))
    @GetMapping
    public ResponseEntity<MerchantShopDTO> get() {
        return ResponseEntity.ok(shopService.getMine());
    }

    @Operation(summary = "Сохранить оформление витрины",
            description = "Название, цвет, заголовок, описание и «О магазине». Пустые строки сохраняются как null",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/design")
    public ResponseEntity<MerchantShopDTO> saveDesign(@Valid @RequestBody ShopDesignRequestDTO request) {
        return ResponseEntity.ok(shopService.saveDesign(request));
    }

    @Operation(summary = "Presigned URL для загрузки логотипа или фото витрины",
            description = "kind — logo или hero. Файл уходит из браузера сразу в S3 (PUT по uploadUrl с тем же Content-Type), "
                    + "затем PUT /images/{kind} с полученным key",
            security = @SecurityRequirement(name = "Bearer"))
    @PostMapping("/images/{kind}/presign")
    public ResponseEntity<PresignUploadResponseDTO> presignImage(@PathVariable("kind") String kind,
                                                                 @Valid @RequestBody PresignUploadRequestDTO request) {
        return ResponseEntity.ok(shopImageService.presign(kind, request.getFilename(), request.getContentType(),
                request.getSize()));
    }

    @Operation(summary = "Привязать загруженную картинку к витрине",
            description = "key должен лежать в папке этого магазина и этой картинки; прежний файл удаляется из S3",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/images/{kind}")
    public ResponseEntity<MerchantShopDTO> registerImage(@PathVariable("kind") String kind,
                                                         @Valid @RequestBody ShopImageRequestDTO request) {
        return ResponseEntity.ok(shopImageService.register(kind, request.getKey()));
    }

    @Operation(summary = "Убрать логотип или фото витрины",
            description = "Файл удаляется из S3",
            security = @SecurityRequirement(name = "Bearer"))
    @DeleteMapping("/images/{kind}")
    public ResponseEntity<MerchantShopDTO> deleteImage(@PathVariable("kind") String kind) {
        return ResponseEntity.ok(shopImageService.delete(kind));
    }

    @Operation(summary = "Сохранить контакты, продавца и ПВЗ отправки",
            description = "Город и адрес ПВЗ берутся из справочника СДЭК по коду; неизвестный код — 400. Пустые строки → null",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/settings")
    public ResponseEntity<MerchantShopDTO> saveSettings(@Valid @RequestBody ShopSettingsRequestDTO request) {
        return ResponseEntity.ok(shopService.saveSettings(request));
    }

    @Operation(summary = "Сохранить терминал Т-Банка и настройки чека",
            description = "Пустой пароль — оставить сохранённый (если TerminalKey не менялся); пустой TerminalKey — "
                    + "отключить оплату. Пароль хранится зашифрованным и не возвращается",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/payment")
    public ResponseEntity<MerchantShopDTO> savePayment(@Valid @RequestBody ShopPaymentRequestDTO request) {
        return ResponseEntity.ok(shopService.savePayment(request));
    }

    @Operation(summary = "Подключить свой магазин ЮKassa и настроить чек",
            description = "Новые ключи проверяются запросом в ЮKassa: не приняты — 400, ЮKassa не ответила — 502. "
                    + "Пустой секретный ключ — оставить сохранённый (если shopId не менялся); пустой shopId — "
                    + "отключить ЮKassa. Ключ хранится зашифрованным и не возвращается",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/payment/yookassa")
    public ResponseEntity<MerchantShopDTO> saveYookassa(@Valid @RequestBody ShopYookassaRequestDTO request) {
        return ResponseEntity.ok(shopService.saveYookassa(request));
    }

    @Operation(summary = "Выбрать, через кого витрина принимает оплату",
            description = "TINKOFF или YOOKASSA; провайдер должен быть подключён, иначе 400. Только что подключённый "
                    + "провайдер выбирается сам",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/payment/provider")
    public ResponseEntity<MerchantShopDTO> selectPaymentProvider(
            @Valid @RequestBody ShopPaymentProviderRequestDTO request) {
        return ResponseEntity.ok(shopService.selectPaymentProvider(request));
    }

    @Operation(summary = "Подключить свой аккаунт СДЭК: ключи API для расчёта доставки",
            description = "Новые ключи проверяются запросом токена в СДЭК: не приняты — 400, СДЭК не ответил — 502. "
                    + "Пустой Secure password — оставить сохранённый (если Account не менялся); пустой Account — "
                    + "отключить СДЭК. Secure password хранится зашифрованным и не возвращается",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/cdek")
    public ResponseEntity<MerchantShopDTO> saveCdek(@Valid @RequestBody ShopCdekRequestDTO request) {
        return ResponseEntity.ok(shopService.saveCdek(request));
    }

    @Operation(summary = "Привязать свой домен витрины",
            description = "Домен подключается к гейтвею платформы, в ответе — DNS-записи, которые мерчант добавляет "
                    + "у регистратора. Витрина откроется на домене с корня, когда записи появятся в DNS. Пусто — "
                    + "отвязать. 400 — некорректный домен или домен платформы, 409 — домен у другого магазина, "
                    + "502 — не удалось подключить домен (он не сохранён)",
            security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/domain")
    public ResponseEntity<ShopDomainDTO> saveDomain(@Valid @RequestBody ShopDomainRequestDTO request) {
        return ResponseEntity.ok(shopDomainService.save(request.getDomain()));
    }

    @Operation(summary = "Отвязать свой домен витрины",
            description = "Витрина остаётся доступна по адресу на платформе, домен отключается от гейтвея. "
                    + "Домен не привязан — не ошибка. В ответе status NONE",
            security = @SecurityRequirement(name = "Bearer"))
    @DeleteMapping("/domain")
    public ResponseEntity<ShopDomainDTO> deleteDomain() {
        return ResponseEntity.ok(shopDomainService.unbind());
    }

    @Operation(summary = "Свой домен витрины: DNS-записи и статус",
            description = "Что добавить у регистратора и видны ли уже записи; status NONE — домен не привязан",
            security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/domain")
    public ResponseEntity<ShopDomainDTO> getDomain() {
        return ResponseEntity.ok(shopDomainService.describe());
    }
}
