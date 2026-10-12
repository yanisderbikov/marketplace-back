package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import ru.marketplace.model.PaymentProvider;

@Data
@Schema(description = "Через кого витрина принимает оплату")
public class ShopPaymentProviderRequestDTO {
    @Schema(description = "TINKOFF или YOOKASSA — провайдер должен быть подключён")
    private PaymentProvider provider;
}
