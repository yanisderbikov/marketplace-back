package ru.marketplace.payment.dto.tinkoff;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TinkoffReceipt {

    @JsonProperty("Email")
    private String email;

    @JsonProperty("Phone")
    private String phone;

    /** Система налогообложения: osn, usn_income, usn_income_outcome, esn, patent. */
    @JsonProperty("Taxation")
    private String taxation;

    @JsonProperty("Items")
    private List<TinkoffReceiptItem> items;
}
