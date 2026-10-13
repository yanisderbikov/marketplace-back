package ru.marketplace.domain;

import com.google.common.net.InternetDomainName;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.marketplace.domain.dto.DnsRecordDTO;
import ru.marketplace.domain.dto.ShopDomainDTO;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Свой сервер: гейтвей сам выпускает сертификат на домен при первом заходе (Caddy on-demand TLS),
 * регистрировать домен нигде не нужно. Мерчанту — CNAME на DOMAIN_CNAME_TARGET; корневому домену
 * (CNAME там поддерживают не все регистраторы) — A-запись на DOMAIN_A_RECORD_IP, если он задан.
 * Статус — по тому, что уже видно в DNS.
 */
@Component
@ConditionalOnProperty(name = "domains.provisioner", havingValue = "static")
public class StaticDomainProvisioner implements DomainProvisioner {

    private final DnsLookup dnsLookup;
    private final String cnameTarget;
    private final String aRecordIp;

    public StaticDomainProvisioner(DnsLookup dnsLookup,
                                   @Value("${domains.static.cname-target}") String cnameTarget,
                                   @Value("${domains.static.a-record-ip}") String aRecordIp) {
        if (cnameTarget == null || cnameTarget.isBlank()) {
            throw new IllegalStateException("DOMAIN_CNAME_TARGET не задан: некуда направлять домены магазинов");
        }
        this.dnsLookup = dnsLookup;
        this.cnameTarget = DnsLookup.stripDot(cnameTarget.trim().toLowerCase(Locale.ROOT));
        this.aRecordIp = aRecordIp == null || aRecordIp.isBlank() ? null : aRecordIp.trim();
    }

    @Override
    public ShopDomainDTO attach(String domain) {
        return describe(domain);
    }

    @Override
    public ShopDomainDTO describe(String domain) {
        String host = hostLabel(domain);
        boolean apex = host.equals("@");
        DnsRecordDTO record;
        String message = null;
        if (apex && aRecordIp != null) {
            record = new DnsRecordDTO("A", host, domain, aRecordIp, DnsRecordDTO.ROUTING, aRecordOk(domain));
        } else {
            record = new DnsRecordDTO("CNAME", host, domain, cnameTarget, DnsRecordDTO.ROUTING, cnameOk(domain));
            if (apex) {
                message = "Для корневого домена CNAME поддерживают не все регистраторы: если не получается, "
                        + "используйте запись ALIAS/ANAME или домен с www.";
            }
        }
        boolean active = Boolean.TRUE.equals(record.ok());
        if (message == null) {
            message = active
                    ? "DNS настроен. Сертификат HTTPS выпустится при первом заходе на домен."
                    : "Добавьте запись у регистратора домена. Обновление DNS занимает от нескольких минут до суток.";
        }
        return new ShopDomainDTO(domain, active ? ShopDomainDTO.ACTIVE : ShopDomainDTO.PENDING, message,
                List.of(record));
    }

    @Override
    public void detach(String domain) {
        // Регистрировать было нечего: без привязки к магазину гейтвей просто не выпустит сертификат
    }

    /** Имя записи относительно зарегистрированного домена: shop.example.ru → shop, example.ru → @. */
    static String hostLabel(String domain) {
        try {
            InternetDomainName name = InternetDomainName.from(domain);
            if (!name.isUnderPublicSuffix()) {
                return "@";
            }
            String registrable = name.topPrivateDomain().toString();
            return domain.length() == registrable.length()
                    ? "@" : domain.substring(0, domain.length() - registrable.length() - 1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "@";
        }
    }

    private Boolean cnameOk(String domain) {
        Set<String> cname = dnsLookup.lookup(domain, "CNAME");
        if (cname != null && cname.contains(cnameTarget)) {
            return true;
        }
        // ALIAS/ANAME и CNAME-flattening видны как A-записи цели
        Set<String> domainA = dnsLookup.lookup(domain, "A");
        Set<String> targetA = dnsLookup.lookup(cnameTarget, "A");
        if (domainA == null || targetA == null) {
            return cname == null ? null : false;
        }
        return !domainA.isEmpty() && targetA.containsAll(domainA);
    }

    private Boolean aRecordOk(String domain) {
        Set<String> a = dnsLookup.lookup(domain, "A");
        return a == null ? null : a.contains(aRecordIp);
    }
}
