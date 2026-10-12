package ru.marketplace.storage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import ru.marketplace.storage.S3FileStorage;

@Data
public class PresignUploadRequestDTO {
    @NotBlank(message = "не указано имя файла")
    private String filename;
    @NotBlank(message = "не указан тип файла")
    private String contentType;
    /**
     * Размер файла в байтах (file.size в браузере). С ним Content-Length входит в подпись PUT, и S3 не примет
     * файл другого размера — иначе одна подпись принимала бы до 5 ГБ. Пока необязателен, чтобы не сломать
     * фронт, который его ещё не шлёт: без него размер проверяется при привязке ключа.
     */
    @Positive(message = "пустой файл")
    @Max(value = S3FileStorage.MAX_UPLOAD_BYTES, message = S3FileStorage.TOO_LARGE)
    @Schema(description = "Размер файла в байтах, до 10 МБ; входит в подпись загрузки", nullable = true,
            example = "524288")
    private Long size;
}
