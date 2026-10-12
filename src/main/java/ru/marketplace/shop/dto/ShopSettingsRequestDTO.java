package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Вкладка «Настройки»: контакты, продавец (для оферты и подвала) и ПВЗ отправки СДЭК. Пустые строки → null. */
@Data
@Schema(description = "Контакты, реквизиты продавца и ПВЗ отправки")
public class ShopSettingsRequestDTO {
    @Schema(description = "Username телеграма поддержки без @: латиница, цифры и _, 4–64 символа", nullable = true)
    private String supportTelegram;
    @Schema(nullable = true)
    private String contactEmail;
    @Schema(nullable = true)
    private String contactPhone;
    @Schema(description = "Название организации или ФИО ИП/самозанятого", nullable = true)
    private String sellerName;
    @Schema(description = "ИНН: 10 или 12 цифр", nullable = true)
    private String sellerInn;
    @Schema(description = "ОГРН (13 цифр) или ОГРНИП (15 цифр)", nullable = true)
    private String sellerOgrn;
    @Schema(nullable = true)
    private String sellerAddress;
    @Schema(description = "Код ПВЗ СДЭК, откуда магазин отправляет посылки; город и адрес берутся из справочника СДЭК",
            nullable = true)
    private String cdekFromPvzCode;
}
