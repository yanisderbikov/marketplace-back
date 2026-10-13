package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import ru.marketplace.model.PaymentProvider;

import java.util.List;
import java.util.UUID;

/**
 * Магазин глазами его владельца: всё для вкладок «Дизайн» и «Настройки» плюс готовность к заказам.
 * Пароль терминала и ключ ЮKassa не отдаются никогда — только признак, что они сохранены.
 */
@Builder
@Schema(description = "Магазин текущего мерчанта")
public record MerchantShopDTO(
        UUID id,
        String slug,
        String name,
        @Schema(description = "false — магазин выключен супер-админом: витрина отвечает 404") boolean active,
        @Schema(description = "Путь витрины на фронте", example = "/shop/candle-shop") String storefrontPath,
        @Schema(description = "Свой домен витрины (punycode, без www)", nullable = true, example = "shop.example.ru")
        String customDomain,
        String brandColor,
        @Schema(nullable = true) String logoUrl,
        @Schema(nullable = true) String heroImageUrl,
        @Schema(nullable = true) String tagline,
        @Schema(nullable = true) String description,
        @Schema(nullable = true) String about,
        @Schema(nullable = true) String supportTelegram,
        @Schema(nullable = true) String contactEmail,
        @Schema(nullable = true) String contactPhone,
        @Schema(nullable = true) String sellerName,
        @Schema(nullable = true) String sellerInn,
        @Schema(nullable = true) String sellerOgrn,
        @Schema(nullable = true) String sellerAddress,
        @Schema(nullable = true) String cdekFromPvzCode,
        @Schema(nullable = true) String cdekFromCity,
        @Schema(nullable = true) String cdekFromAddress,
        @Schema(description = "Account СДЭК магазина", nullable = true) String cdekClientId,
        @Schema(description = "Secure password СДЭК сохранён (сам секрет не отдаётся)") boolean cdekSecretSet,
        @Schema(description = "Через кого витрина принимает оплату; null — ни один провайдер не подключён",
                nullable = true) PaymentProvider paymentProvider,
        @Schema(nullable = true) String tinkoffTerminalKey,
        @Schema(description = "Пароль терминала сохранён (сам пароль не отдаётся)") boolean tinkoffPasswordSet,
        boolean tinkoffReceiptEnabled,
        @Schema(nullable = true) String tinkoffTaxation,
        @Schema(nullable = true) String tinkoffTax,
        @Schema(description = "shopId магазина в ЮKassa", nullable = true) String yookassaShopId,
        @Schema(description = "Секретный ключ ЮKassa сохранён (сам ключ не отдаётся)") boolean yookassaSecretKeySet,
        boolean yookassaReceiptEnabled,
        @Schema(nullable = true) String yookassaTaxation,
        @Schema(nullable = true) String yookassaTax,
        @Schema(description = "Магазин включён и всё настроено — витрина принимает заказы") boolean acceptsOrders,
        @Schema(description = "Чего не хватает для приёма заказов: PAYMENT, DELIVERY, SELLER") List<String> missingForOrders
) {
}
