package ru.marketplace.storage.dto;

import ru.marketplace.storage.PresignedUpload;

public record PresignUploadResponseDTO(String uploadUrl, String key) {

    public static PresignUploadResponseDTO of(PresignedUpload upload) {
        return new PresignUploadResponseDTO(upload.uploadUrl(), upload.key());
    }
}
