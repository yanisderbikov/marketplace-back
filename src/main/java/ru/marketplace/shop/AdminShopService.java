package ru.marketplace.shop;

import ru.marketplace.shop.dto.AdminShopDTO;

import java.util.List;
import java.util.UUID;

public interface AdminShopService {
    /** Все магазины платформы, новые сверху. */
    List<AdminShopDTO> list();

    /** Включить/выключить магазин; 404 — нет такого. */
    AdminShopDTO setActive(UUID id, boolean active);
}
