package ru.marketplace.payment.dto.tinkoff;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TinkoffInitRequest {

    @JsonProperty("TerminalKey")
    private String terminalKey;

    /** Сумма в копейках. */
    @JsonProperty("Amount")
    private Long amount;

    /** Уникальный идентификатор заказа в системе мерчанта — у нас публичный номер заказа. */
    @JsonProperty("OrderId")
    private String orderId;

    @JsonProperty("Description")
    private String description;

    /** O — одностадийный платёж, T — двухстадийный. */
    @JsonProperty("PayType")
    private String payType;

    @JsonProperty("SuccessURL")
    private String successURL;

    @JsonProperty("FailURL")
    private String failURL;

    @JsonProperty("NotificationURL")
    private String notificationURL;

    /** Срок жизни ссылки на оплату, {@code yyyy-MM-dd'T'HH:mm:ssXXX}; после него оплата по ссылке недоступна. */
    @JsonProperty("RedirectDueDate")
    private String redirectDueDate;

    @JsonProperty("Receipt")
    private TinkoffReceipt receipt;

    /** SHA-256 подпись запроса паролем терминала магазина — в логи не пишем. */
    @JsonProperty("Token")
    @ToString.Exclude
    private String token;
}
