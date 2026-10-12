package ru.marketplace.payment.dto.tinkoff;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Нотификация Т-Банка о смене статуса платежа — типизированный вид уже проверенного тела.
 * Token проверяется по сырому JSON (все корневые скалярные поля), а не по этому классу:
 * поля, о которых мы не знаем, тоже входят в подпись.
 * PaymentId и CardId строками: у банка это Number(20), в Long такое может не влезть.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TinkoffNotification {

    @JsonProperty("TerminalKey")
    private String terminalKey;

    @JsonProperty("OrderId")
    private String orderId;

    @JsonProperty("Success")
    private Boolean success;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("PaymentId")
    private String paymentId;

    @JsonProperty("ErrorCode")
    private String errorCode;

    /** Сумма в копейках. */
    @JsonProperty("Amount")
    private Long amount;

    @JsonProperty("CardId")
    private String cardId;

    @JsonProperty("Pan")
    private String pan;

    @JsonProperty("ExpDate")
    private String expDate;

    @JsonProperty("RebillId")
    private String rebillId;

    @JsonProperty("Token")
    @ToString.Exclude
    private String token;
}
