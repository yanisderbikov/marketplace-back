package ru.marketplace.shop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.cdek.CdekAuthService;
import ru.marketplace.cdek.CdekConnectedEvent;
import ru.marketplace.cdek.CdekCredentials;
import ru.marketplace.cdek.CdekPvzLookup;
import ru.marketplace.cdek.dto.CdekPvzDTO;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.common.SecretCipher;
import ru.marketplace.common.ShopLookup;
import ru.marketplace.model.PaymentProvider;
import ru.marketplace.model.Shop;
import ru.marketplace.model.User;
import ru.marketplace.payment.YookassaClient;
import ru.marketplace.payment.YookassaCredentials;
import ru.marketplace.repository.ShopRepository;
import ru.marketplace.shop.dto.CreateShopRequestDTO;
import ru.marketplace.shop.dto.DomainResolveDTO;
import ru.marketplace.shop.dto.MerchantShopDTO;
import ru.marketplace.shop.dto.PublicShopCardDTO;
import ru.marketplace.shop.dto.PublicShopDTO;
import ru.marketplace.shop.dto.ShopCdekRequestDTO;
import ru.marketplace.shop.dto.ShopDesignRequestDTO;
import ru.marketplace.shop.dto.ShopDomainRequestDTO;
import ru.marketplace.shop.dto.ShopPaymentProviderRequestDTO;
import ru.marketplace.shop.dto.ShopPaymentRequestDTO;
import ru.marketplace.shop.dto.ShopSettingsRequestDTO;
import ru.marketplace.shop.dto.ShopYookassaRequestDTO;
import ru.marketplace.shop.dto.SlugAvailabilityDTO;
import ru.marketplace.util.PhoneUtil;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
class ShopServiceImpl implements ShopService {

    static final String SLUG_TAKEN = "Адрес занят";
    static final String ALREADY_HAS_SHOP = "У вас уже есть магазин";
    static final String DOMAIN_TAKEN = "Этот домен уже привязан к другому магазину";

    static final int NAME_MIN = 2;
    static final int NAME_MAX = 120;
    static final int TAGLINE_MAX = 160;
    static final int DESCRIPTION_MAX = 600;
    static final int ABOUT_MAX = 4000;
    // Лимиты ниже — размеры колонок shop: длиннее в БД не влезет, лучше 400 с понятным текстом, чем 500
    private static final int EMAIL_MAX = 255;
    private static final int PHONE_MAX = 32;
    private static final int SELLER_NAME_MAX = 255;
    private static final int SELLER_ADDRESS_MAX = 512;
    private static final int PVZ_CODE_MAX = 64;
    private static final int CITY_MAX = 255;
    private static final int POSTAL_CODE_MAX = 16;
    private static final int ADDRESS_MAX = 512;
    /** Пароль терминала хранится шифртекстом в TEXT, лимит — просто защита от мусора */
    private static final int TERMINAL_PASSWORD_MAX = 128;
    /** Secure password СДЭК тоже лежит шифртекстом в TEXT — лимит от мусора */
    private static final int CDEK_SECRET_MAX = 256;
    /** Секретный ключ ЮKassa тоже лежит шифртекстом в TEXT — лимит от мусора */
    private static final int YOOKASSA_SECRET_MAX = 256;

    private static final Pattern BRAND_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern TELEGRAM = Pattern.compile("^[A-Za-z0-9_]{4,64}$");
    /** Ссылка вместо username: https://t.me/name, t.me/name, telegram.me/name */
    private static final Pattern TELEGRAM_LINK_PREFIX = Pattern.compile("^(?:https?://)?(?:t|telegram)\\.me/",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern INN = Pattern.compile("^(?:\\d{10}|\\d{12})$");
    private static final Pattern OGRN = Pattern.compile("^(?:\\d{13}|\\d{15})$");
    private static final Pattern TERMINAL_KEY = Pattern.compile("^\\S{1,64}$");
    private static final Pattern CDEK_CLIENT_ID = Pattern.compile("^\\S{1,128}$");
    /** shopId ЮKassa — число из кабинета (колонка на 32 символа) */
    private static final Pattern YOOKASSA_SHOP_ID = Pattern.compile("^\\d{1,32}$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Значения Taxation и Tax из API чеков Т-Банка. vat20 нет: с 01.01.2026 основная ставка НДС — 22%,
     * и в перечне Receipt.Items[].Tax актуального API Т-Банка (ФФД 1.05 и 1.2) vat20 больше нет —
     * магазин с ним получал бы отказ Init на каждом заказе или чеки с несуществующей ставкой.
     * Расчётные ставки (vat105/vat107/vat110/vat122) не нужны: чек всегда «полная оплата».
     */
    static final Set<String> TAXATIONS = Set.of("osn", "usn_income", "usn_income_outcome", "esn", "patent");
    static final Set<String> TAXES = Set.of("none", "vat0", "vat5", "vat7", "vat10", "vat22");

    private final ShopRepository shopRepository;
    private final ShopOwnerRepository shopOwnerRepository;
    private final ShopCatalogStatsRepository shopCatalogStatsRepository;
    private final CurrentMerchant currentMerchant;
    private final ShopLookup shopLookup;
    private final SecretCipher secretCipher;
    private final CdekPvzLookup cdekPvzLookup;
    private final CdekAuthService cdekAuthService;
    private final YookassaClient yookassaClient;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ShopMapper shopMapper;
    private final ShopDomains shopDomains;

    @Override
    @Transactional
    public MerchantShopDTO create(CreateShopRequestDTO request) {
        User user = currentMerchant.user();
        if (user.getShop() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ALREADY_HAS_SHOP);
        }
        String name = requireName(request.getName());
        String slug = SlugPolicy.normalize(request.getSlug());
        String slugProblem = SlugPolicy.problem(slug);
        if (slugProblem != null) {
            throw badRequest(slugProblem);
        }
        String brandColor = requireBrandColor(request.getBrandColor());
        if (shopRepository.existsBySlug(slug)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SLUG_TAKEN);
        }

        // Всё, чего нет в форме онбординга, явно пустое: магазин включён, но заказы примет только после
        // настройки оплаты, доставки и продавца (ShopReadiness)
        Shop shop = Shop.builder()
                .slug(slug)
                .name(name)
                .active(true)
                .brandColor(brandColor)
                .tinkoffReceiptEnabled(false)
                .yookassaReceiptEnabled(false)
                .build();
        try {
            shop = shopRepository.saveAndFlush(shop);
        } catch (DataIntegrityViolationException e) {
            // Адрес успели занять между проверкой и вставкой (slug UNIQUE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, SLUG_TAKEN);
        }
        if (shopOwnerRepository.attachShopIfNone(user.getId(), shop) == 0) {
            // Параллельный онбординг того же мерчанта уже привязал магазин — этот откатится вместе с транзакцией
            throw new ResponseStatusException(HttpStatus.CONFLICT, ALREADY_HAS_SHOP);
        }
        log.info("Создан магазин {} ({}) пользователем {}", shop.getSlug(), shop.getId(), user.getId());
        return shopMapper.toMerchant(shop);
    }

    @Override
    @Transactional(readOnly = true)
    public SlugAvailabilityDTO checkSlug(String slug) {
        String normalized = SlugPolicy.normalize(slug);
        String problem = SlugPolicy.problem(normalized);
        if (problem != null) {
            return SlugAvailabilityDTO.rejected(problem);
        }
        if (shopRepository.existsBySlug(normalized)) {
            return SlugAvailabilityDTO.rejected(SLUG_TAKEN);
        }
        return SlugAvailabilityDTO.ok();
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantShopDTO getMine() {
        return shopMapper.toMerchant(currentMerchant.shop());
    }

    @Override
    @Transactional
    public MerchantShopDTO saveDesign(ShopDesignRequestDTO request) {
        Shop shop = currentMerchant.shop();
        String name = requireName(request.getName());
        String brandColor = requireBrandColor(request.getBrandColor());
        String tagline = optionalText(request.getTagline(), TAGLINE_MAX, "Заголовок");
        String description = optionalText(request.getDescription(), DESCRIPTION_MAX, "Описание");
        String about = optionalText(request.getAbout(), ABOUT_MAX, "О магазине");

        shop.setName(name);
        shop.setBrandColor(brandColor);
        shop.setTagline(tagline);
        shop.setDescription(description);
        shop.setAbout(about);
        return shopMapper.toMerchant(shopRepository.save(shop));
    }

    @Override
    @Transactional
    public MerchantShopDTO saveSettings(ShopSettingsRequestDTO request) {
        Shop shop = currentMerchant.shop();
        String supportTelegram = normalizeTelegram(request.getSupportTelegram());
        String contactEmail = normalizeEmail(request.getContactEmail());
        String contactPhone = normalizePhone(request.getContactPhone());
        String sellerName = optionalText(request.getSellerName(), SELLER_NAME_MAX, "Продавец");
        String sellerInn = optionalDigits(request.getSellerInn(), INN, "ИНН: 10 или 12 цифр");
        String sellerOgrn = optionalDigits(request.getSellerOgrn(), OGRN, "ОГРН/ОГРНИП: 13 или 15 цифр");
        String sellerAddress = optionalText(request.getSellerAddress(), SELLER_ADDRESS_MAX, "Адрес продавца");
        String pvzCode = trimToNull(request.getCdekFromPvzCode());
        Optional<CdekPvzDTO> pvz = resolvePvz(shop, pvzCode);

        shop.setSupportTelegram(supportTelegram);
        shop.setContactEmail(contactEmail);
        shop.setContactPhone(contactPhone);
        shop.setSellerName(sellerName);
        shop.setSellerInn(sellerInn);
        shop.setSellerOgrn(sellerOgrn);
        shop.setSellerAddress(sellerAddress);
        if (pvzCode == null) {
            shop.setCdekFromPvzCode(null);
            shop.setCdekFromCity(null);
            shop.setCdekFromPostalCode(null);
            shop.setCdekFromAddress(null);
        } else if (pvz.isPresent()) {
            // Город/индекс/адрес — копия из справочника СДЭК, а не из формы: по ним считается доставка
            CdekPvzDTO point = pvz.get();
            shop.setCdekFromPvzCode(pvzCode);
            shop.setCdekFromCity(truncate(point.getCity(), CITY_MAX));
            shop.setCdekFromPostalCode(truncate(point.getPostalCode(), POSTAL_CODE_MAX));
            shop.setCdekFromAddress(truncate(isBlank(point.getAddress()) ? point.getFullAddress() : point.getAddress(),
                    ADDRESS_MAX));
        }
        return shopMapper.toMerchant(shopRepository.save(shop));
    }

    @Override
    @Transactional
    public MerchantShopDTO savePayment(ShopPaymentRequestDTO request) {
        Shop shop = currentMerchant.shop();
        boolean wasConnected = ShopPayment.tinkoffConnected(shop);
        String terminalKey = trimToNull(request.getTerminalKey());
        String password = trimToNull(request.getPassword());
        boolean receiptEnabled = Boolean.TRUE.equals(request.getReceiptEnabled());
        String taxation = optionalChoice(request.getTaxation(), TAXATIONS, "Система налогообложения: выберите из списка");
        String tax = optionalChoice(request.getTax(), TAXES, "НДС: выберите из списка");
        if (receiptEnabled && taxation == null) {
            throw badRequest("Для чека укажите систему налогообложения");
        }
        if (receiptEnabled && tax == null) {
            throw badRequest("Для чека укажите ставку НДС");
        }
        if (terminalKey != null && !TERMINAL_KEY.matcher(terminalKey).matches()) {
            throw badRequest("TerminalKey: без пробелов, не длиннее 64 символов");
        }
        if (terminalKey != null && password != null && password.length() > TERMINAL_PASSWORD_MAX) {
            throw badRequest("Пароль терминала: не длиннее " + TERMINAL_PASSWORD_MAX + " символов");
        }

        if (terminalKey == null) {
            // Без терминала пароль бессмысленен — сбрасываем оба, оплата на витрине выключается
            shop.setTinkoffTerminalKey(null);
            shop.setTinkoffPasswordEnc(null);
        } else {
            boolean terminalChanged = !terminalKey.equals(shop.getTinkoffTerminalKey());
            shop.setTinkoffTerminalKey(terminalKey);
            if (password != null) {
                shop.setTinkoffPasswordEnc(secretCipher.encrypt(password));
            } else if (terminalChanged) {
                // Пароль выдаётся под конкретный терминал: со старым паролем новый терминал всё равно
                // отклонит Init — пусть лучше витрина честно покажет «оплата не настроена»
                shop.setTinkoffPasswordEnc(null);
            }
        }
        shop.setTinkoffReceiptEnabled(receiptEnabled);
        shop.setTinkoffTaxation(taxation);
        shop.setTinkoffTax(tax);
        syncPaymentProvider(shop, PaymentProvider.TINKOFF, wasConnected);
        Shop saved = shopRepository.save(shop);
        log.info("Магазин {}: обновлены настройки оплаты (терминал {}, пароль {}, чек {})",
                saved.getSlug(), saved.getTinkoffTerminalKey() == null ? "не задан" : "задан",
                saved.getTinkoffPasswordEnc() == null ? "не задан" : "задан", receiptEnabled);
        return shopMapper.toMerchant(saved);
    }

    /**
     * Без @Transactional: новые ключи проверяем в ЮKassa до записи и вне транзакции (как ключи СДЭК) —
     * ответ ждём до 10 секунд, соединение с БД на это время не держим.
     */
    @Override
    public MerchantShopDTO saveYookassa(ShopYookassaRequestDTO request) {
        String shopId = trimToNull(request.getShopId());
        String secretKey = trimToNull(request.getSecretKey());
        boolean receiptEnabled = Boolean.TRUE.equals(request.getReceiptEnabled());
        String taxation = optionalChoice(request.getTaxation(), TAXATIONS, "Система налогообложения: выберите из списка");
        String tax = optionalChoice(request.getTax(), TAXES, "НДС: выберите из списка");
        if (receiptEnabled && taxation == null) {
            throw badRequest("Для чека укажите систему налогообложения");
        }
        if (receiptEnabled && tax == null) {
            throw badRequest("Для чека укажите ставку НДС");
        }
        if (shopId != null && !YOOKASSA_SHOP_ID.matcher(shopId).matches()) {
            throw badRequest("shopId ЮKassa — только цифры, как в кабинете ЮKassa");
        }
        if (shopId != null && secretKey != null
                && (secretKey.length() > YOOKASSA_SECRET_MAX || WHITESPACE.matcher(secretKey).find())) {
            throw badRequest("Секретный ключ ЮKassa: без пробелов, не длиннее " + YOOKASSA_SECRET_MAX + " символов");
        }
        if (shopId != null && secretKey != null) {
            switch (yookassaClient.check(new YookassaCredentials(shopId, secretKey))) {
                case REJECTED -> throw badRequest("ЮKassa не приняла ключи: проверьте shopId и секретный ключ");
                case UNAVAILABLE -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "ЮKassa не ответила — не удалось проверить ключи, попробуйте ещё раз через пару минут");
                case OK -> {
                }
            }
        }
        Shop saved = transactionTemplate.execute(tx -> writeYookassa(shopId, secretKey, receiptEnabled, taxation, tax));
        log.info("Магазин {}: обновлены настройки ЮKassa (shopId {}, ключ {}, чек {})",
                saved.getSlug(), saved.getYookassaShopId() == null ? "не задан" : "задан",
                saved.getYookassaSecretKeyEnc() == null ? "не задан" : "задан", receiptEnabled);
        return shopMapper.toMerchant(saved);
    }

    private Shop writeYookassa(String shopId, String secretKey, boolean receiptEnabled, String taxation, String tax) {
        Shop shop = currentMerchant.shop();
        boolean wasConnected = ShopPayment.yookassaConnected(shop);
        if (shopId == null) {
            // Без shopId ключ бессмысленен — сбрасываем оба
            shop.setYookassaShopId(null);
            shop.setYookassaSecretKeyEnc(null);
        } else if (secretKey != null) {
            shop.setYookassaShopId(shopId);
            shop.setYookassaSecretKeyEnc(secretCipher.encrypt(secretKey));
        } else if (!shopId.equals(shop.getYookassaShopId()) || isBlank(shop.getYookassaSecretKeyEnc())) {
            // Секретный ключ выпускается под конкретный магазин ЮKassa: от другого shopId он не подойдёт
            throw badRequest("Введите секретный ключ для этого shopId ЮKassa");
        }
        shop.setYookassaReceiptEnabled(receiptEnabled);
        shop.setYookassaTaxation(taxation);
        shop.setYookassaTax(tax);
        syncPaymentProvider(shop, PaymentProvider.YOOKASSA, wasConnected);
        return shopRepository.save(shop);
    }

    @Override
    @Transactional
    public MerchantShopDTO selectPaymentProvider(ShopPaymentProviderRequestDTO request) {
        PaymentProvider provider = request.getProvider();
        if (provider == null) {
            throw badRequest("Выберите, через кого принимать оплату");
        }
        Shop shop = currentMerchant.shop();
        if (!ShopPayment.connected(shop, provider)) {
            throw badRequest(provider == PaymentProvider.YOOKASSA
                    ? "Сначала подключите ЮKassa" : "Сначала подключите Т-Банк");
        }
        shop.setPaymentProvider(provider);
        Shop saved = shopRepository.save(shop);
        log.info("Магазин {}: оплата принимается через {}", saved.getSlug(), provider);
        return shopMapper.toMerchant(saved);
    }

    /**
     * Только что подключили провайдера — дальше витрина принимает оплату через него (мерчант для этого
     * и подключал). Отключили выбранного — переключаемся на другого подключённого или ни на кого.
     */
    private static void syncPaymentProvider(Shop shop, PaymentProvider provider, boolean wasConnected) {
        if (!wasConnected && ShopPayment.connected(shop, provider)) {
            shop.setPaymentProvider(provider);
        }
        shop.setPaymentProvider(ShopPayment.activeProvider(shop));
    }

    /**
     * Без @Transactional: новые ключи проверяем запросом токена в СДЭК до записи и вне транзакции —
     * ответ СДЭК ждём до 10 секунд, соединение с БД на это время не держим (как расчёт доставки).
     */
    @Override
    public MerchantShopDTO saveCdek(ShopCdekRequestDTO request) {
        String clientId = trimToNull(request.getClientId());
        String clientSecret = trimToNull(request.getClientSecret());
        if (clientId != null && !CDEK_CLIENT_ID.matcher(clientId).matches()) {
            throw badRequest("Account СДЭК: без пробелов, не длиннее 128 символов");
        }
        if (clientId != null && clientSecret != null && clientSecret.length() > CDEK_SECRET_MAX) {
            throw badRequest("Secure password СДЭК: не длиннее " + CDEK_SECRET_MAX + " символов");
        }
        if (clientId != null && clientSecret != null) {
            switch (cdekAuthService.check(new CdekCredentials(clientId, clientSecret))) {
                case REJECTED -> throw badRequest("СДЭК не принял ключи: проверьте Account и Secure password");
                case UNAVAILABLE -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "СДЭК не ответил — не удалось проверить ключи, попробуйте ещё раз через пару минут");
                case OK -> {
                }
            }
        }
        Shop saved = transactionTemplate.execute(tx -> writeCdek(clientId, clientSecret));
        log.info("Магазин {}: обновлены ключи СДЭК (Account {}, Secure password {})",
                saved.getSlug(), saved.getCdekClientId() == null ? "не задан" : "задан",
                saved.getCdekClientSecretEnc() == null ? "не задан" : "задан");
        if (clientId != null && clientSecret != null) {
            eventPublisher.publishEvent(new CdekConnectedEvent(saved.getSlug()));
        }
        return shopMapper.toMerchant(saved);
    }

    private Shop writeCdek(String clientId, String clientSecret) {
        Shop shop = currentMerchant.shop();
        if (clientId == null) {
            // Без Account секрет бессмысленен — сбрасываем оба, доставка на витрине не считается
            shop.setCdekClientId(null);
            shop.setCdekClientSecretEnc(null);
        } else if (clientSecret != null) {
            shop.setCdekClientId(clientId);
            shop.setCdekClientSecretEnc(secretCipher.encrypt(clientSecret));
        } else if (!clientId.equals(shop.getCdekClientId()) || isBlank(shop.getCdekClientSecretEnc())) {
            // Secure password выдаётся под конкретный Account: без него новые ключи не проверить и не использовать
            throw badRequest("Введите Secure password для этого Account СДЭК");
        }
        return shopRepository.save(shop);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublicShopCardDTO> getPublicShops() {
        Map<UUID, Long> productsByShop = shopCatalogStatsRepository.countActiveProductsOfActiveShops().stream()
                .collect(Collectors.toMap(ShopCatalogStatsRepository.ShopActiveProducts::getShopId,
                        ShopCatalogStatsRepository.ShopActiveProducts::getProductsCount));
        return shopRepository.findAllByActiveTrueOrderByCreatedAtDesc().stream()
                .filter(shop -> productsByShop.getOrDefault(shop.getId(), 0L) > 0)
                .map(shop -> shopMapper.toCard(shop, productsByShop.get(shop.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PublicShopDTO getPublicShop(String slug) {
        return shopMapper.toPublic(shopLookup.activeBySlug(slug));
    }

    @Override
    public MerchantShopDTO saveDomain(ShopDomainRequestDTO request) {
        String domain;
        try {
            domain = shopDomains.normalize(request.getDomain());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
        Shop saved;
        try {
            // Без @Transactional на методе: нарушение UNIQUE всплывает на коммите, его надо поймать здесь
            saved = transactionTemplate.execute(tx -> {
                Shop shop = currentMerchant.shop();
                if (domain != null && shopRepository.findByCustomDomain(domain)
                        .filter(other -> !other.getId().equals(shop.getId())).isPresent()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, DOMAIN_TAKEN);
                }
                shop.setCustomDomain(domain);
                return shopRepository.saveAndFlush(shop);
            });
        } catch (DataIntegrityViolationException e) {
            // Домен успели занять между проверкой и записью (custom_domain UNIQUE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, DOMAIN_TAKEN);
        }
        log.info("Магазин {}: свой домен {}", saved.getSlug(), domain == null ? "отвязан" : domain);
        return shopMapper.toMerchant(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DomainResolveDTO resolveDomain(String host) {
        String key = ShopDomains.lookupKey(host);
        return Optional.ofNullable(key)
                .flatMap(shopRepository::findByCustomDomain)
                .filter(shop -> Boolean.TRUE.equals(shop.getActive()))
                .map(shop -> new DomainResolveDTO(shop.getSlug(), shop.getCustomDomain()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Магазин не найден"));
    }

    /**
     * ПВЗ отправки из кэша СДЭК. Неизвестный новый код — 400. Если код не менялся, а в кэше его сейчас нет
     * (кэш пересобирается или пункт закрылся), не мешаем сохранить остальные настройки: остаются прежние
     * город и адрес. Пустой код — ПВЗ сбрасывается.
     */
    private Optional<CdekPvzDTO> resolvePvz(Shop shop, String pvzCode) {
        if (pvzCode == null) {
            return Optional.empty();
        }
        if (pvzCode.length() > PVZ_CODE_MAX) {
            throw badRequest("Пункт выдачи СДЭК не найден");
        }
        Optional<CdekPvzDTO> pvz = cdekPvzLookup.findByCode(pvzCode);
        if (pvz.isEmpty() && !pvzCode.equals(shop.getCdekFromPvzCode())) {
            throw badRequest("Пункт выдачи СДЭК не найден: " + pvzCode);
        }
        return pvz;
    }

    private static String requireName(String raw) {
        String name = trimToNull(raw);
        if (name == null || name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw badRequest("Название магазина: от " + NAME_MIN + " до " + NAME_MAX + " символов");
        }
        return name;
    }

    /** #RRGGBB, хранится в нижнем регистре — фронт строит из него тему витрины */
    private static String requireBrandColor(String raw) {
        String color = trimToNull(raw);
        if (color == null || !BRAND_COLOR.matcher(color).matches()) {
            throw badRequest("Цвет: укажите в формате #RRGGBB");
        }
        return color.toLowerCase(Locale.ROOT);
    }

    /** Необязательный текст: пустая строка → null, длиннее лимита — 400 */
    private static String optionalText(String raw, int max, String label) {
        String value = trimToNull(raw);
        if (value != null && value.length() > max) {
            throw badRequest(label + ": не длиннее " + max + " символов");
        }
        return value;
    }

    /** ИНН/ОГРН: пробелы внутри (так копируют из выписки) убираем, дальше только цифры нужной длины */
    private static String optionalDigits(String raw, Pattern pattern, String message) {
        String value = raw == null ? null : WHITESPACE.matcher(raw).replaceAll("");
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (!pattern.matcher(value).matches()) {
            throw badRequest(message);
        }
        return value;
    }

    private static String optionalChoice(String raw, Set<String> allowed, String message) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        value = value.toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw badRequest(message);
        }
        return value;
    }

    /** Username без @; мерчанты часто вставляют «@name» или ссылку t.me/name — принимаем и их */
    static String normalizeTelegram(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        value = TELEGRAM_LINK_PREFIX.matcher(value).replaceFirst("");
        if (value.startsWith("@")) {
            value = value.substring(1);
        }
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!TELEGRAM.matcher(value).matches()) {
            throw badRequest("Telegram: username без @ — латиница, цифры и _, от 4 до 64 символов");
        }
        return value;
    }

    private static String normalizeEmail(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        value = value.toLowerCase(Locale.ROOT);
        if (value.length() > EMAIL_MAX || !EMAIL.matcher(value).matches()) {
            throw badRequest("Почта: некорректный адрес");
        }
        return value;
    }

    /** Телефон хранится как ввёл мерчант (так и показывается в подвале), но должен быть похож на номер */
    private static String normalizePhone(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        if (value.length() > PHONE_MAX || PhoneUtil.toE164(value) == null) {
            throw badRequest("Телефон: некорректный номер");
        }
        return value;
    }

    private static String truncate(String value, int max) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
