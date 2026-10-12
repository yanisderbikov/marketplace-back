package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Свой аккаунт СДЭК мерчанта: доставка считается по тарифам его договора, у платформы ключей СДЭК нет. */
@Data
@Schema(description = "Ключи API СДЭК магазина")
public class ShopCdekRequestDTO {
    @Schema(description = "Account из раздела «Интеграция» личного кабинета СДЭК; пусто — отключить СДЭК "
            + "(сбрасывает и Secure password)", nullable = true)
    private String clientId;
    @Schema(description = "Secure password; пусто — оставить сохранённый (если Account не менялся). "
            + "Наружу не отдаётся никогда", nullable = true)
    private String clientSecret;
}
