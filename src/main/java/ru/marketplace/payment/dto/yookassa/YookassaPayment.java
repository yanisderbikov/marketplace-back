package ru.marketplace.payment.dto.yookassa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Объект платежа ЮKassa — ответ на создание и на GET /v3/payments/{id}. */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class YookassaPayment {

    private String id;

    /** pending, waiting_for_capture, succeeded, canceled. */
    private String status;

    private YookassaAmount amount;

    @JsonProperty("refunded_amount")
    private YookassaAmount refundedAmount;

    private Confirmation confirmation;

    private Map<String, String> metadata;

    private Boolean test;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Confirmation {
        private String type;
        @JsonProperty("confirmation_url")
        private String confirmationUrl;
    }
}
