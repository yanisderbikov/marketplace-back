package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Свой магазин мерчанта в ЮKassa: деньги покупателей идут ему напрямую, платформа их не касается. */
@Data
@Schema(description = "Ключи API ЮKassa и настройки чека")
public class ShopYookassaRequestDTO {
    @Schema(description = "shopId из раздела «Интеграция → Ключи API» кабинета ЮKassa; пусто — отключить ЮKassa "
            + "(сбрасывает и секретный ключ)", nullable = true)
    private String shopId;
    @Schema(description = "Секретный ключ (live_… или test_…); пусто — оставить сохранённый, если shopId не менялся. "
            + "Наружу не отдаётся никогда", nullable = true)
    private String secretKey;
    @Schema(description = "Передавать чек (54-ФЗ) в платеже")
    private Boolean receiptEnabled;
    @Schema(description = "Система налогообложения: osn, usn_income, usn_income_outcome, esn, patent", nullable = true)
    private String taxation;
    @Schema(description = "НДС позиций чека: none, vat0, vat5, vat7, vat10, vat22", nullable = true)
    private String tax;
}
