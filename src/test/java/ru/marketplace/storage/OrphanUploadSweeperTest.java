package ru.marketplace.storage;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrphanUploadSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofHours(25));
    private static final Instant FRESH = NOW.minus(Duration.ofHours(23));
    private static final String SHOP = "0b6f2c1e-1111-4a2b-9c3d-123456789abc";
    private static final String PRODUCT = "7d3e2a10-2222-4b5c-8d9e-abcdefabcdef";

    private final S3FileStorage storage = mock(S3FileStorage.class);
    private final StorageReferenceRepository references = mock(StorageReferenceRepository.class);
    private final OrphanUploadSweeper sweeper = new OrphanUploadSweeper(storage, references, Clock.fixed(NOW, ZoneOffset.UTC));

    private static String photo(String name) {
        return "shops/" + SHOP + "/products/" + PRODUCT + "/" + name;
    }

    @SafeVarargs
    private void bucketPages(List<StoredObject>... pages) {
        doAnswer(inv -> {
            Predicate<List<StoredObject>> handler = inv.getArgument(1);
            for (List<StoredObject> page : pages) {
                if (!handler.test(page)) {
                    break;
                }
            }
            return null;
        }).when(storage).listObjects(eq("shops/"), any());
    }

    @Test
    void deletesOnlyOldUnreferencedUploadsOfOurFormat() {
        String orphan = photo("orphan.jpg");
        String registered = photo("live.jpg");
        String freshOrphan = photo("uploading.jpg");
        String orphanLogo = "shops/" + SHOP + "/branding/logo/old.png";
        String registeredHero = "shops/" + SHOP + "/branding/hero/hero.jpg";
        String foreign = "shops/" + SHOP + "/exports/report.csv";
        bucketPages(List.of(
                new StoredObject(orphan, OLD),
                new StoredObject(registered, OLD),
                new StoredObject(freshOrphan, FRESH),
                new StoredObject(orphanLogo, OLD),
                new StoredObject(registeredHero, OLD),
                new StoredObject(foreign, OLD)));
        when(references.referencedAmong(anyCollection())).thenReturn(Set.of(registered, registeredHero));

        assertEquals(2, sweeper.sweep());

        verify(storage).delete(orphan);
        verify(storage).delete(orphanLogo);
        verify(storage, times(2)).delete(anyString());
        // Свежие и чужие ключи даже не сверяем с БД
        verify(references).referencedAmong(List.of(orphan, registered, orphanLogo, registeredHero));
    }

    @Test
    void databaseErrorAbortsBeforeAnyDelete() {
        bucketPages(List.of(new StoredObject(photo("a.jpg"), OLD)));
        doThrow(new IllegalStateException("БД недоступна")).when(references).referencedAmong(anyCollection());

        sweeper.tick();

        verify(storage, never()).delete(anyString());
    }

    @Test
    void referencesAreCheckedInBatches() {
        List<StoredObject> page = IntStream.range(0, OrphanUploadSweeper.BATCH + 10)
                .mapToObj(i -> new StoredObject(photo(i + ".jpg"), OLD))
                .toList();
        bucketPages(page);
        List<Integer> batchSizes = new ArrayList<>();
        when(references.referencedAmong(anyCollection())).thenAnswer(inv -> {
            batchSizes.add(inv.<Collection<String>>getArgument(0).size());
            return Set.of();
        });

        assertEquals(OrphanUploadSweeper.BATCH + 10, sweeper.sweep());
        assertEquals(List.of(OrphanUploadSweeper.BATCH, 10), batchSizes);
    }

    @Test
    void stopsAtDeleteLimitPerRun() {
        List<StoredObject> page = IntStream.range(0, OrphanUploadSweeper.MAX_DELETES_PER_RUN + 5)
                .mapToObj(i -> new StoredObject(photo(i + ".jpg"), OLD))
                .toList();
        bucketPages(page, List.of(new StoredObject(photo("next-page.jpg"), OLD)));
        when(references.referencedAmong(anyCollection())).thenReturn(Set.of());

        assertEquals(OrphanUploadSweeper.MAX_DELETES_PER_RUN, sweeper.sweep());
        verify(storage, never()).delete(photo("next-page.jpg"));
    }

    @Test
    void storageErrorDoesNotEscapeTick() {
        doThrow(new RuntimeException("S3 недоступен")).when(storage).listObjects(anyString(), any());

        sweeper.tick();

        verify(references, never()).referencedAmong(anyCollection());
    }
}
