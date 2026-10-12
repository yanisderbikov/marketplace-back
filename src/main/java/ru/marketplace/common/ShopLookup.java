package ru.marketplace.common;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.model.Shop;
import ru.marketplace.repository.ShopRepository;

import java.util.Locale;
import java.util.UUID;

/** Поиск магазина для публичных ручек и админки с единым 404. */
@Component
@RequiredArgsConstructor
public class ShopLookup {

    private static final String NOT_FOUND = "Магазин не найден";

    private final ShopRepository shopRepository;

    /**
     * Включённый магазин по адресу витрины. Выключенный супер-админом отвечает так же, как несуществующий:
     * покупателю незачем знать, что магазин был.
     */
    public Shop activeBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw notFound();
        }
        return shopRepository.findBySlug(slug.trim().toLowerCase(Locale.ROOT))
                .filter(shop -> Boolean.TRUE.equals(shop.getActive()))
                .orElseThrow(ShopLookup::notFound);
    }

    /** Магазин по id независимо от активности (для супер-админа). */
    public Shop byId(UUID id) {
        if (id == null) {
            throw notFound();
        }
        return shopRepository.findById(id).orElseThrow(ShopLookup::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }
}
