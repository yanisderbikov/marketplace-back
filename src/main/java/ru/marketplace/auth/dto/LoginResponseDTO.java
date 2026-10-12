package ru.marketplace.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Ответ с токеном")
public class LoginResponseDTO {
    @Schema(description = "JWT токен")
    private String token;
}
