package ru.marketplace.shop;

import ru.marketplace.shop.dto.CreateShopRequestDTO;
import ru.marketplace.shop.dto.MerchantShopDTO;
import ru.marketplace.shop.dto.PublicShopCardDTO;
import ru.marketplace.shop.dto.PublicShopDTO;
import ru.marketplace.shop.dto.ShopCdekRequestDTO;
import ru.marketplace.shop.dto.ShopDesignRequestDTO;
import ru.marketplace.shop.dto.ShopPaymentProviderRequestDTO;
import ru.marketplace.shop.dto.ShopPaymentRequestDTO;
import ru.marketplace.shop.dto.ShopSettingsRequestDTO;
import ru.marketplace.shop.dto.ShopYookassaRequestDTO;
import ru.marketplace.shop.dto.SlugAvailabilityDTO;

import java.util.List;

public interface ShopService {

    /**
     * Онбординг: магазин текущего мерчанта. 409 — магазин уже есть или адрес занят,
     * 400 — невалидные название, адрес или цвет.
     */
    MerchantShopDTO create(CreateShopRequestDTO request);

    /** Живая проверка адреса на онбординге: правила SlugPolicy + занятость. */
    SlugAvailabilityDTO checkSlug(String slug);

    /** Магазин текущего мерчанта; 409, пока онбординг не пройден. */
    MerchantShopDTO getMine();

    MerchantShopDTO saveDesign(ShopDesignRequestDTO request);

    MerchantShopDTO saveSettings(ShopSettingsRequestDTO request);

    MerchantShopDTO savePayment(ShopPaymentRequestDTO request);

    /**
     * Свой магазин в ЮKassa. Новые ключи проверяются в ЮKassa до сохранения: 400 — не приняты,
     * 502 — ЮKassa не ответила. Пустой shopId — отключить ЮKassa.
     */
    MerchantShopDTO saveYookassa(ShopYookassaRequestDTO request);

    /** Через кого витрина принимает оплату; провайдер должен быть подключён, иначе 400. */
    MerchantShopDTO selectPaymentProvider(ShopPaymentProviderRequestDTO request);

    /**
     * Свой аккаунт СДЭК магазина. Новые ключи проверяются в СДЭК до сохранения: 400 — не приняты,
     * 502 — СДЭК не ответил. Пустой Account — отключить СДЭК.
     */
    MerchantShopDTO saveCdek(ShopCdekRequestDTO request);

    /** Включённые магазины, у которых есть хотя бы один продающийся товар, новые сверху. */
    List<PublicShopCardDTO> getPublicShops();

    /** Включённый магазин для витрины; нет или выключен — 404. */
    PublicShopDTO getPublicShop(String slug);
}
