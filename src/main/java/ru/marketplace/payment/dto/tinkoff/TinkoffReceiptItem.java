package ru.marketplace.payment.dto.tinkoff;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TinkoffReceiptItem {

    @JsonProperty("Name")
    private String name;

    /** Цена за единицу в копейках. */
    @JsonProperty("Price")
    private Long price;

    @JsonProperty("Quantity")
    private Integer quantity;

    /** Сумма позиции в копейках (Price × Quantity). */
    @JsonProperty("Amount")
    private Long amount;

    /** Ставка НДС: none, vat0, vat5, vat7, vat10, vat22 (vat20 из API Т-Банка убран с 2026 года). */
    @JsonProperty("Tax")
    private String tax;

    @JsonProperty("PaymentMethod")
    private String paymentMethod;

    @JsonProperty("PaymentObject")
    private String paymentObject;
}
