package ru.marketplace.cdek;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.marketplace.common.SecretCipher;
import ru.marketplace.model.Shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ключи СДЭК магазинов в расшифрованном виде. Аккаунта платформы нет: расчёт доставки идёт по ключам
 * магазина, а общий справочник ПВЗ (он у всех аккаунтов одинаковый) — по ключам любого подключённого магазина.
 */
@Component
@RequiredArgsConstructor
public class CdekAccounts {

    private static final Logger logger = LoggerFactory.getLogger(CdekAccounts.class);

    private final CdekShopAccountRepository shopAccountRepository;
    private final SecretCipher secretCipher;

    /** Ключи магазина; пусто — СДЭК не подключён или секрет не расшифровывается (сменили PAYMENT_SECRETS_KEY). */
    public Optional<CdekCredentials> of(Shop shop) {
        if (!CdekCredentials.isConnected(shop)) {
            return Optional.empty();
        }
        try {
            return Optional.of(CdekCredentials.of(shop, secretCipher));
        } catch (IllegalStateException e) {
            logger.error("Не удалось получить ключи СДЭК магазина {}: {}", shop.getSlug(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Ключи всех магазинов с подключённым СДЭК, нерасшифровываемые пропускаем. */
    public List<CdekCredentials> connected() {
        List<CdekCredentials> result = new ArrayList<>();
        for (Shop shop : shopAccountRepository.findAllWithCdekKeys()) {
            of(shop).filter(c -> !result.contains(c)).ifPresent(result::add);
        }
        return result;
    }
}
