package ru.marketplace.payment.dto.yookassa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Тело ошибки ЮKassa: {"type":"error","code":"invalid_request","description":"…","parameter":"…"}. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class YookassaError {
    private String code;
    private String description;
    private String parameter;
}
