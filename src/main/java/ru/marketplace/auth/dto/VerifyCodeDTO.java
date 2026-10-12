package ru.marketplace.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Подтверждение кода из письма")
public class VerifyCodeDTO {
    @NotBlank(message = "Укажите почту")
    @Email(message = "Некорректная почта")
    @Size(max = 255, message = "Слишком длинная почта")
    @Schema(description = "Почта, на которую отправлен код", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "shop@example.com")
    private String email;

    @NotBlank(message = "Введите код из письма")
    @Pattern(regexp = "\\d{6}", message = "Код — 6 цифр")
    @Schema(description = "Шестизначный код из письма", requiredMode = Schema.RequiredMode.REQUIRED, example = "482913")
    private String code;

    /** Как в RequestCodeDTO: пробелы по краям — не повод отбивать почту. */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }

    /** Код копируют из письма вместе с пробелами — обрезаем, чтобы не сжечь попытку зря. */
    public void setCode(String code) {
        this.code = code == null ? null : code.trim();
    }
}
