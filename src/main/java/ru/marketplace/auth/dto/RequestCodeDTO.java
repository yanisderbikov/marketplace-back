package ru.marketplace.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Запрос кода для входа мерчанта (первый вход — это и есть регистрация)")
public class RequestCodeDTO {
    @NotBlank(message = "Укажите почту")
    @Email(message = "Некорректная почта")
    @Size(max = 255, message = "Слишком длинная почта")
    @Schema(description = "Почта мерчанта", requiredMode = Schema.RequiredMode.REQUIRED, example = "shop@example.com")
    private String email;

    /**
     * Пробелы по краям обрезаем до валидации: почту часто вставляют из буфера с пробелом,
     * и @Email отбил бы её как некорректную. Регистр приводит сервис.
     */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}
