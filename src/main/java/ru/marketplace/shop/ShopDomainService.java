package ru.marketplace.shop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.domain.DomainProvisioner;
import ru.marketplace.domain.DomainProvisioningException;
import ru.marketplace.domain.dto.ShopDomainDTO;
import ru.marketplace.model.Shop;
import ru.marketplace.repository.ShopRepository;
import ru.marketplace.shop.dto.DomainResolveDTO;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Свой домен витрины: привязка к магазину, подключение к гейтвею (DomainProvisioner) и DNS-записи
 * для мерчанта, а также поиск магазина по домену для фронта и гейтвея.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopDomainService {

    static final String DOMAIN_TAKEN = "Этот домен уже привязан к другому магазину";
    static final String PROVISIONING_FAILED =
            "Не удалось подключить домен к платформе — попробуйте ещё раз через пару минут";

    private final ShopRepository shopRepository;
    private final CurrentMerchant currentMerchant;
    private final ShopDomains shopDomains;
    private final DomainProvisioner domainProvisioner;
    private final TransactionTemplate transactionTemplate;

    /**
     * Привязать домен (пусто — отвязать) и вернуть DNS-записи для регистратора. Внешний API подключения
     * зовём вне транзакции: сначала подключаем новый домен, потом пишем в БД, потом отключаем старый.
     * 400 — некорректный домен, 409 — у другого магазина, 502 — подключение не удалось (домен не сохранён).
     */
    public ShopDomainDTO save(String raw) {
        String domain;
        try {
            domain = shopDomains.normalize(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        Shop current = transactionTemplate.execute(tx -> currentMerchant.shop());
        UUID shopId = current.getId();
        String previous = current.getCustomDomain();
        if (domain != null && takenByOther(domain, shopId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, DOMAIN_TAKEN);
        }

        ShopDomainDTO dns = domain == null ? ShopDomainDTO.none() : attach(domain);
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                Shop shop = currentMerchant.shop();
                shop.setCustomDomain(domain);
                shopRepository.saveAndFlush(shop);
            });
        } catch (DataIntegrityViolationException e) {
            // Домен успели занять между проверкой и записью (custom_domain UNIQUE)
            if (domain != null && !domain.equals(previous)) {
                detachQuietly(domain);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, DOMAIN_TAKEN);
        }
        if (previous != null && !previous.equals(domain)) {
            detachQuietly(previous);
        }
        log.info("Магазин {}: свой домен {}", current.getSlug(), domain == null ? "отвязан" : domain);
        return dns;
    }

    /** Домен магазина и его DNS-записи со статусом; внешний API не ответил — ERROR с пояснением. */
    public ShopDomainDTO describe() {
        String domain = transactionTemplate.execute(tx -> currentMerchant.shop().getCustomDomain());
        if (domain == null) {
            return ShopDomainDTO.none();
        }
        try {
            return domainProvisioner.describe(domain);
        } catch (DomainProvisioningException e) {
            log.error("Не удалось получить статус домена {}: {}", domain, e.getMessage());
            return new ShopDomainDTO(domain, ShopDomainDTO.ERROR,
                    "Не удалось проверить домен — попробуйте ещё раз через пару минут", List.of());
        }
    }

    /** Включённый магазин по хосту запроса: точное совпадение или тот же домен с/без www; иначе 404. */
    public DomainResolveDTO resolve(String host) {
        String key = ShopDomains.lookupKey(host);
        return transactionTemplate.execute(tx -> Optional.ofNullable(key)
                .flatMap(k -> shopRepository.findByCustomDomain(k)
                        .or(() -> shopRepository.findByCustomDomain(ShopDomains.wwwVariant(k))))
                .filter(shop -> Boolean.TRUE.equals(shop.getActive()))
                .map(shop -> new DomainResolveDTO(shop.getSlug(), shop.getCustomDomain()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Магазин не найден")));
    }

    /** shop.ru и www.shop.ru — один сайт: у разных магазинов их быть не может. */
    private boolean takenByOther(String domain, UUID shopId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(tx ->
                List.of(domain, ShopDomains.wwwVariant(domain)).stream()
                        .map(shopRepository::findByCustomDomain)
                        .flatMap(Optional::stream)
                        .anyMatch(other -> !other.getId().equals(shopId))));
    }

    private ShopDomainDTO attach(String domain) {
        try {
            return domainProvisioner.attach(domain);
        } catch (DomainProvisioningException e) {
            log.error("Не удалось подключить домен {}: {}", domain, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, PROVISIONING_FAILED);
        }
    }

    private void detachQuietly(String domain) {
        try {
            domainProvisioner.detach(domain);
        } catch (DomainProvisioningException e) {
            // Домен уже не привязан к магазину: гейтвей на него витрину не покажет, хвост уберут руками
            log.warn("Не удалось отключить домен {} от гейтвея: {}", domain, e.getMessage());
        }
    }
}
