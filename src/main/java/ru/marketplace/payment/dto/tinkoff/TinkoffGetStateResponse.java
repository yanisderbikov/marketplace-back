package ru.marketplace.payment.dto.tinkoff;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TinkoffGetStateResponse {

    @JsonProperty("Success")
    private Boolean success;

    @JsonProperty("ErrorCode")
    private String errorCode;

    @JsonProperty("Message")
    private String message;

    @JsonProperty("Details")
    private String details;

    @JsonProperty("TerminalKey")
    private String terminalKey;

    /** Текущий статус платежа: NEW, FORM_SHOWED, CONFIRMED, REJECTED, DEADLINE_EXPIRED и т.д. */
    @JsonProperty("Status")
    private String status;

    @JsonProperty("PaymentId")
    private String paymentId;

    @JsonProperty("OrderId")
    private String orderId;

    /** Сумма в копейках. */
    @JsonProperty("Amount")
    private Long amount;
}
