package ru.marketplace.cdek;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import ru.marketplace.cdek.dto.CdekPvzDTO;
import ru.marketplace.repository.CdekPvzJdbcRepo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Справочник ПВЗ СДЭК: снимок в памяти (поиск на чекауте и в настройках магазина) поверх таблицы cdek_pvz.
 * При старте снимок поднимается из БД — тянуть сотни мегабайт из СДЭК на каждый деплой незачем;
 * пустая таблица (первый запуск) — фоном грузим из API. Раз в сутки обновляет {@link CdekPvzSyncTask}.
 * Аккаунта СДЭК у платформы нет: справочник у всех аккаунтов одинаковый, поэтому грузим его ключами
 * любого магазина, подключившего СДЭК. Пока таких нет — справочник пуст, а первое подключение
 * его заполняет ({@link #onCdekConnected}).
 */
@Service
@RequiredArgsConstructor
public class CdekPvzService implements CdekPvzLookup {

    private static final Logger logger = LoggerFactory.getLogger(CdekPvzService.class);
    private static final int MAX_RESULTS = 50;
    private static final int MIN_QUERY_LENGTH = 3;
    private static final String HOME_COUNTRY = "RU";

    private final CdekDeliveryPointsGateway deliveryPointsGateway;
    private final CdekAccounts cdekAccounts;
    private final CdekAuthService cdekAuthService;
    private final CdekPvzJdbcRepo repo;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private volatile List<CdekPvzDTO> snapshot = List.of();
    private volatile Map<String, CdekPvzDTO> byCode = Map.of();

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            List<CdekPvzDTO> stored = repo.findAll();
            if (!stored.isEmpty()) {
                replaceSnapshot(stored);
                logger.info("ПВЗ СДЭК: загружено из БД {} пунктов", stored.size());
                return;
            }
        } catch (Exception e) {
            logger.error("ПВЗ СДЭК: не удалось прочитать кэш из БД: {}", e.getMessage());
        }
        CompletableFuture.runAsync(this::refresh);
    }

    public List<CdekPvzDTO> search(String query) {
        if (query == null || query.trim().length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        List<CdekPvzDTO> points = snapshot;
        if (points.isEmpty()) {
            return List.of();
        }
        String[] tokens = query.trim().toLowerCase(Locale.ROOT).split("[\\s,]+");
        List<CdekPvzDTO> matched = new ArrayList<>();
        for (CdekPvzDTO p : points) {
            if (matches(p, tokens)) {
                matched.add(p);
            }
        }
        matched.sort(Comparator
                .comparing((CdekPvzDTO p) -> HOME_COUNTRY.equalsIgnoreCase(p.getCountryCode()) ? 0 : 1)
                .thenComparing(p -> nullToEmpty(p.getCountryCode()))
                .thenComparing(p -> nullToEmpty(p.getCity()))
                .thenComparing(p -> nullToEmpty(p.getAddress())));
        return matched.size() > MAX_RESULTS ? List.copyOf(matched.subList(0, MAX_RESULTS)) : matched;
    }

    public boolean refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            logger.info("ПВЗ СДЭК: обновление уже идёт, пропускаем");
            return false;
        }
        try {
            Optional<CdekCredentials> account = workingAccount();
            if (account.isEmpty()) {
                logger.warn("ПВЗ СДЭК: ни у одного магазина нет рабочих ключей СДЭК, кэш не обновлён ({} пунктов в памяти)",
                        snapshot.size());
                return false;
            }
            List<CdekPvzDTO> fresh = deliveryPointsGateway.fetchAllPickupPoints(account.get());
            if (fresh.isEmpty()) {
                logger.warn("ПВЗ СДЭК: список из API пуст, кэш не обновлён ({} пунктов в памяти)", snapshot.size());
                return false;
            }
            repo.replaceAll(fresh);
            replaceSnapshot(fresh);
            logger.info("ПВЗ СДЭК: кэш обновлён, {} пунктов", fresh.size());
            return true;
        } catch (Exception e) {
            logger.error("ПВЗ СДЭК: ошибка обновления кэша: {}", e.getMessage(), e);
            return false;
        } finally {
            refreshing.set(false);
        }
    }

    /**
     * Магазин только что подключил СДЭК: если справочник ещё пуст (ни у кого не было ключей),
     * заполняем его фоном — иначе мерчанту не из чего выбрать пункт отправки.
     */
    @EventListener
    public void onCdekConnected(CdekConnectedEvent event) {
        if (snapshot.isEmpty() && !refreshing.get()) {
            logger.info("ПВЗ СДЭК: справочник пуст, загружаем его после подключения СДЭК магазином {}", event.shopSlug());
            CompletableFuture.runAsync(this::refresh);
        }
    }

    /**
     * Первые ключи, по которым СДЭК выдал токен (токен кэшируется — gateway возьмёт его же).
     * Отозванные ключи одного мерчанта не должны оставить без справочника всю платформу
     */
    private Optional<CdekCredentials> workingAccount() {
        for (CdekCredentials credentials : cdekAccounts.connected()) {
            if (cdekAuthService.getAccessToken(credentials) != null) {
                return Optional.of(credentials);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<CdekPvzDTO> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String trimmed = code.trim();
        Map<String, CdekPvzDTO> points = byCode;
        if (!points.isEmpty()) {
            return Optional.ofNullable(points.get(trimmed.toUpperCase(Locale.ROOT)));
        }
        // Снимок ещё пуст: веб-сервер принимает запросы чуть раньше ApplicationReadyEvent, а на первом запуске
        // таблица заполняется фоном. Спросим БД напрямую, чтобы сохранение ПВЗ отправки и расчёт доставки
        // не отвечали «ПВЗ не найден» из-за гонки со стартом
        try {
            return repo.findByCode(trimmed).stream().findFirst();
        } catch (Exception e) {
            logger.warn("ПВЗ СДЭК: не удалось найти {} в БД: {}", trimmed, e.getMessage());
            return Optional.empty();
        }
    }

    public int size() {
        return snapshot.size();
    }

    private void replaceSnapshot(List<CdekPvzDTO> points) {
        snapshot = List.copyOf(points);
        byCode = points.stream()
                .filter(p -> p.getCode() != null)
                .collect(Collectors.toUnmodifiableMap(p -> p.getCode().toUpperCase(Locale.ROOT), Function.identity(), (a, b) -> a));
    }

    private boolean matches(CdekPvzDTO p, String[] tokens) {
        String haystack = (nullToEmpty(p.getAddress()) + " "
                + nullToEmpty(p.getFullAddress()) + " "
                + nullToEmpty(p.getCity()) + " "
                + nullToEmpty(p.getRegion()) + " "
                + nullToEmpty(p.getName())).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (!token.isBlank() && !haystack.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
