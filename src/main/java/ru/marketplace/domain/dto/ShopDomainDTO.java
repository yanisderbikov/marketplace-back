package ru.marketplace.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Свой домен витрины и что с ним сделать: DNS-записи для регистратора и статус подключения. */
@Schema(description = "Свой домен витрины и его DNS-записи")
public record ShopDomainDTO(
        @Schema(description = "Домен; null — не привязан", nullable = true, example = "shop.example.ru") String domain,
        @Schema(description = "NONE — домена нет, PENDING — ждём DNS и сертификат, ACTIVE — витрина открывается "
                + "на домене, ERROR — подключение не удалось (см. message)") String status,
        @Schema(description = "Пояснение к статусу для мерчанта", nullable = true) String message,
        @Schema(description = "Записи, которые нужно добавить у регистратора домена") List<DnsRecordDTO> records
) {
    public static final String NONE = "NONE";
    public static final String PENDING = "PENDING";
    public static final String ACTIVE = "ACTIVE";
    public static final String ERROR = "ERROR";

    public static ShopDomainDTO none() {
        return new ShopDomainDTO(null, NONE, null, List.of());
    }
}
