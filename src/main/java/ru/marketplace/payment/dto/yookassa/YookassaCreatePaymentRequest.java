package ru.marketplace.payment.dto.yookassa;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** POST /v3/payments: одностадийный платёж (capture=true) с редиректом на страницу ЮKassa. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class YookassaCreatePaymentRequest {

    private YookassaAmount amount;

    /** true — деньги списываются сразу, без отдельного подтверждения (капчура) мерчантом. */
    private Boolean capture;

    private Confirmation confirmation;

    private String description;

    /** order_id — номер заказа: по нему сверяем платёж с заказом (у ЮKassa нет своего OrderId). */
    private Map<String, String> metadata;

    private YookassaReceipt receipt;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Confirmation {
        private String type;
        @JsonProperty("return_url")
        private String returnUrl;
    }
}
