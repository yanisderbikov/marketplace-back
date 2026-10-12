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
public class TinkoffGetStateRequest {

    @JsonProperty("TerminalKey")
    private String terminalKey;

    @JsonProperty("PaymentId")
    private String paymentId;

    @JsonProperty("Token")
    @ToString.Exclude
    private String token;
}
