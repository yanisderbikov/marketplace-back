package ru.marketplace.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Одна DNS-запись, которую мерчант добавляет у регистратора своего домена. */
@Schema(description = "DNS-запись для своего домена витрины")
public record DnsRecordDTO(
        @Schema(description = "Тип записи", example = "CNAME") String type,
        @Schema(description = "Имя записи относительно домена, как его вводят у регистратора; @ — сам домен",
                example = "shop") String host,
        @Schema(description = "Полное имя записи", example = "shop.example.ru") String fqdn,
        @Schema(description = "Значение записи", example = "abc123.up.railway.app") String value,
        @Schema(description = "ROUTING — направить трафик на платформу, VERIFICATION — подтвердить владение доменом")
        String purpose,
        @Schema(description = "Запись уже видна в DNS; null — проверить не удалось", nullable = true) Boolean ok
) {
    public static final String ROUTING = "ROUTING";
    public static final String VERIFICATION = "VERIFICATION";
}
