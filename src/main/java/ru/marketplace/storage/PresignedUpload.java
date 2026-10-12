package ru.marketplace.storage;

/** Подписанный URL для прямой загрузки из браузера в S3 (PUT) и ключ, под которым ляжет файл. */
public record PresignedUpload(String uploadUrl, String key) {
}
