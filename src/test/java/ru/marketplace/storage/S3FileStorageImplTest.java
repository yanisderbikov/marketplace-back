package ru.marketplace.storage;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3FileStorageImplTest {

    private static final String BUCKET = "marketplace-test";
    private static final String KEY = "shops/0b6f2c1e-1111-4a2b-9c3d-123456789abc/branding/logo/a.png";

    private final S3Client s3Client = mock(S3Client.class);
    /** Настоящий презайнер: подпись считается локально, без сети — проверяем, что именно попало в подпись */
    private final S3Presigner presigner = S3Presigner.builder()
            .region(Region.of("ru-central1"))
            .endpointOverride(URI.create("https://storage.yandexcloud.net"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")))
            .build();
    private final S3FileStorageImpl storage = new S3FileStorageImpl(s3Client, presigner, BUCKET);

    private static String signedHeaders(String url) {
        String query = URI.create(url).getRawQuery();
        for (String param : query.split("&")) {
            if (param.startsWith("X-Amz-SignedHeaders=")) {
                return URLDecoder.decode(param.substring("X-Amz-SignedHeaders=".length()), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static HttpStatus statusOf(Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    // ── presign ──

    @Test
    void declaredSizeIsPartOfSignature() {
        PresignedUpload upload = storage.presignUpload("shops/s/branding/logo", "logo.PNG", "image/png", 524_288L);

        // S3 сверит Content-Length PUT с подписанным — файл другого размера не загрузится
        assertTrue(signedHeaders(upload.uploadUrl()).contains("content-length"), upload.uploadUrl());
        assertTrue(signedHeaders(upload.uploadUrl()).contains("content-type"));
        assertTrue(upload.key().startsWith("shops/s/branding/logo/"));
        assertTrue(upload.key().endsWith(".png"));
    }

    @Test
    void withoutSizeSignatureHasNoLengthForBackwardCompatibility() {
        PresignedUpload upload = storage.presignUpload("shops/s/branding/logo", "logo.png", "image/png", null);

        assertFalse(signedHeaders(upload.uploadUrl()).contains("content-length"));
    }

    @Test
    void sizeOutsideLimitsIsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST,
                statusOf(() -> storage.presignUpload("p", "a.png", "image/png", S3FileStorage.MAX_UPLOAD_BYTES + 1)));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> storage.presignUpload("p", "a.png", "image/png", 0L)));
        assertDoesNotThrow(() -> storage.presignUpload("p", "a.png", "image/png", S3FileStorage.MAX_UPLOAD_BYTES));
    }

    @Test
    void nonImageIsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> storage.presignUpload("p", "a.exe", "application/x-msdownload", 10L)));
    }

    // ── requireUploaded ──

    @Test
    void uploadedFileWithinLimitPasses() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1_000L).build());

        assertDoesNotThrow(() -> storage.requireUploaded(KEY));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void oversizedFileIsDeletedAndRejected() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(S3FileStorage.MAX_UPLOAD_BYTES + 1).build());

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> storage.requireUploaded(KEY));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals(S3FileStorage.TOO_LARGE, e.getReason());
        verify(s3Client).deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build());
    }

    @Test
    void missingFileIsRejected() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).message("Not Found").build());

        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> storage.requireUploaded(KEY)));
    }

    @Test
    void unreachableStorageDoesNotBlockMerchant() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: storage.yandexcloud.net"));
        assertDoesNotThrow(() -> storage.requireUploaded(KEY));

        reset(s3Client);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(503).message("Slow Down").build());
        assertDoesNotThrow(() -> storage.requireUploaded(KEY));
    }

    // ── listObjects ──

    @Test
    void listingWalksAllPagesUntilHandlerStops() {
        when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenAnswer(inv -> new ListObjectsV2Iterable(s3Client, inv.getArgument(0)));
        Instant t = Instant.parse("2026-09-01T00:00:00Z");
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(inv -> {
            ListObjectsV2Request request = inv.getArgument(0);
            assertEquals(BUCKET, request.bucket());
            assertEquals("shops/", request.prefix());
            int page = request.continuationToken() == null ? 0 : Integer.parseInt(request.continuationToken());
            ListObjectsV2Response.Builder response = ListObjectsV2Response.builder()
                    .contents(S3Object.builder().key("k" + page).lastModified(t).build());
            return page < 2
                    ? response.isTruncated(true).nextContinuationToken(String.valueOf(page + 1)).build()
                    : response.isTruncated(false).build();
        });

        List<String> seen = new ArrayList<>();
        storage.listObjects("shops/", page -> {
            page.forEach(o -> seen.add(o.key()));
            return true;
        });
        assertEquals(List.of("k0", "k1", "k2"), seen);

        seen.clear();
        storage.listObjects("shops/", page -> {
            page.forEach(o -> seen.add(o.key()));
            return false;
        });
        assertEquals(List.of("k0"), seen);
    }
}
