package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * Магазин для витрины: оформление, контакты и реквизиты продавца для подвала и оферты.
 * Без терминала, ПВЗ отправки и прочих настроек — покупателю они не нужны.
 */
@Builder
@Schema(description = "Публичные данные магазина для витрины /shop/{slug}")
public record PublicShopDTO(
        String slug,
        String name,
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
        @Schema(description = "false — кнопки «В корзину» неактивны: магазин ещё не настроил оплату, доставку или реквизиты")
        boolean acceptsOrders
) {
}
