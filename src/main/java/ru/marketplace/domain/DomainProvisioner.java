package ru.marketplace.domain;

import ru.marketplace.domain.dto.ShopDomainDTO;

/**
 * Подключение своих доменов витрин к гейтвею платформы. Реализация выбирается DOMAIN_PROVISIONER:
 * railway — домен регистрируется на сервисе гейтвея через Railway API (Railway выдаёт CNAME и TXT
 * и сам выпускает сертификат); static — свой сервер, гейтвей выпускает сертификаты сам (on-demand TLS),
 * записи строятся из конфига. Любая неудача внешнего API — {@link DomainProvisioningException}.
 */
public interface DomainProvisioner {

    /** Подключить домен (повторный вызов для уже подключённого — не ошибка) и вернуть его DNS-записи. */
    ShopDomainDTO attach(String domain);

    /** Текущие DNS-записи и статус подключённого домена. */
    ShopDomainDTO describe(String domain);

    /** Отключить домен; неизвестный домен — не ошибка. */
    void detach(String domain);
}
