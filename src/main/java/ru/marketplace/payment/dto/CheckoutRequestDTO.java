package ru.marketplace.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Оформление заказа на витрине: корзина + данные покупателя + выбранный ПВЗ СДЭК.
 * Цен здесь нет намеренно — сумму платежа сервер считает сам по своим ценам.
 */
@Data
@Schema(description = "Оформление заказа на витрине магазина")
public class CheckoutRequestDTO {

    /** Лимит строк корзины: живому покупателю хватит с запасом, а запрос на тысячи позиций — это не покупка. */
    public static final int MAX_ITEMS = 100;

    @NotEmpty(message = "Корзина пуста")
    @Size(max = MAX_ITEMS, message = "Слишком много позиций в корзине")
    @Valid
    @Schema(description = "Позиции корзины", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<@NotNull(message = "Пустая позиция корзины") CheckoutItemDTO> items;

    @NotBlank(message = "Укажите ФИО")
    @Size(max = 255, message = "Слишком длинное ФИО")
    @Schema(description = "ФИО покупателя", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fullName;

    @NotBlank(message = "Укажите телефон")
    @Size(max = 32, message = "Слишком длинный телефон")
    @Schema(description = "Телефон покупателя, например +79991234567", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @NotBlank(message = "Укажите почту")
    @Email(message = "Некорректная почта")
    @Size(max = 255, message = "Слишком длинная почта")
    @Schema(description = "Почта для чека и подтверждения оплаты", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @NotBlank(message = "Выберите пункт выдачи СДЭК")
    @Size(max = 64, message = "Некорректный код ПВЗ")
    @Schema(description = "Код ПВЗ СДЭК", requiredMode = Schema.RequiredMode.REQUIRED)
    private String pvzCode;

    @Size(max = 255, message = "Слишком длинный город ПВЗ")
    @Schema(description = "Город ПВЗ СДЭК", nullable = true)
    private String pvzCity;

    @Size(max = 512, message = "Слишком длинный адрес ПВЗ")
    @Schema(description = "Адрес ПВЗ СДЭК", nullable = true)
    private String pvzAddress;

    @Size(max = 2000, message = "Комментарий — до 2000 символов")
    @Schema(description = "Комментарий к заказу", nullable = true)
    private String comment;

    @Schema(description = "Согласие на рассылку")
    private Boolean marketingConsent;

    @Schema(description = "Согласие с офертой и политикой конфиденциальности; без него заказ не оформляется",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean acceptTerms;

    @NotBlank(message = "Не указан адрес возврата после оплаты")
    @Size(max = 1024, message = "Слишком длинный адрес возврата")
    @Schema(description = "Страница успеха витрины; сервер допишет ?order=…&status=success|fail. "
            + "Хост — только из PAYMENT_ALLOWED_RETURN_HOSTS", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "https://example.com/shop/my-shop/success")
    private String returnUrl;

    /** Пробелы по краям почты — не повод отбивать заказ (как в VerifyCodeDTO). */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}
