package ru.marketplace.payment.dto.yookassa;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** Чек 54-ФЗ в платеже ЮKassa: покупатель, позиции и система налогообложения. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class YookassaReceipt {

    private Customer customer;

    private List<Item> items;

    /** 1 ОСН, 2 УСН доходы, 3 УСН доходы минус расходы, 5 ЕСХН, 6 патент. */
    @JsonProperty("tax_system_code")
    private Integer taxSystemCode;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Customer {
        private String email;
        /** E.164 без «+»: 79001234567. */
        private String phone;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Item {
        private String description;
        private BigDecimal quantity;
        /** Цена за единицу (не сумма позиции). */
        private YookassaAmount amount;
        @JsonProperty("vat_code")
        private Integer vatCode;
        @JsonProperty("payment_mode")
        private String paymentMode;
        @JsonProperty("payment_subject")
        private String paymentSubject;
    }
}
