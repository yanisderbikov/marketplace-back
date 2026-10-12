package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Свой терминал Т-Банка мерчанта: деньги покупателей идут ему напрямую, платформа их не касается. */
@Data
@Schema(description = "Терминал Т-Банка и настройки чека")
public class ShopPaymentRequestDTO {
    @Schema(description = "TerminalKey из личного кабинета Т-Бизнеса; пусто — отключить оплату (сбрасывает и пароль)",
            nullable = true)
    private String terminalKey;
    @Schema(description = "Пароль терминала; пусто — оставить сохранённый. Наружу не отдаётся никогда", nullable = true)
    private String password;
    @Schema(description = "Передавать чек (54-ФЗ) в Init")
    private Boolean receiptEnabled;
    @Schema(description = "Система налогообложения: osn, usn_income, usn_income_outcome, esn, patent", nullable = true)
    private String taxation;
    @Schema(description = "НДС позиций чека: none, vat0, vat5, vat7, vat10, vat22", nullable = true)
    private String tax;
}
