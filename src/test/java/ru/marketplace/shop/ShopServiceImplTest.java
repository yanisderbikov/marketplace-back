package ru.marketplace.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
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
import ru.marketplace.storage.S3FileStorage;

import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopServiceImplTest {

    private static final UUID SHOP_ID = UUID.fromString("0b6f2c1e-1111-4a2b-9c3d-123456789abc");

    private final ShopRepository shopRepository = mock(ShopRepository.class);
    private final ShopOwnerRepository shopOwnerRepository = mock(ShopOwnerRepository.class);
    private final ShopCatalogStatsRepository statsRepository = mock(ShopCatalogStatsRepository.class);
    private final CurrentMerchant currentMerchant = mock(CurrentMerchant.class);
    private final CdekPvzLookup cdekPvzLookup = mock(CdekPvzLookup.class);
    private final CdekAuthService cdekAuthService = mock(CdekAuthService.class);
    private final YookassaClient yookassaClient = mock(YookassaClient.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final S3FileStorage s3FileStorage = mock(S3FileStorage.class);
    private final SecretCipher secretCipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private ShopServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShopServiceImpl(shopRepository, shopOwnerRepository, statsRepository, currentMerchant,
                new ShopLookup(shopRepository), secretCipher, cdekPvzLookup, cdekAuthService, yookassaClient, eventPublisher,
                new TransactionTemplate(transactionManager), new ShopMapper(s3FileStorage));
        when(shopRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(s3FileStorage.publicUrl(anyString())).thenAnswer(inv -> "https://s3.example/" + inv.getArgument(0));
    }

    private static User userWithoutShop() {
        User user = new User();
        user.setId(7L);
        user.setEmail("owner@example.com");
        return user;
    }

    private static Shop existingShop() {
        Shop shop = new Shop();
        shop.setId(SHOP_ID);
        shop.setSlug("candles");
        shop.setName("Свечи");
        shop.setActive(true);
        shop.setBrandColor("#7f95a7");
        shop.setTinkoffReceiptEnabled(false);
        return shop;
    }

    private Shop givenMerchantShop() {
        Shop shop = existingShop();
        when(currentMerchant.shop()).thenReturn(shop);
        return shop;
    }

    private static CreateShopRequestDTO createRequest(String name, String slug, String color) {
        CreateShopRequestDTO request = new CreateShopRequestDTO();
        request.setName(name);
        request.setSlug(slug);
        request.setBrandColor(color);
        return request;
    }

    private static HttpStatus statusOf(Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    private static String reasonOf(Runnable call) {
        return assertThrows(ResponseStatusException.class, call::run).getReason();
    }

    // ---------- онбординг ----------

    @Test
    void createMakesActiveShopWithExplicitValuesAndAttachesItToUser() {
        User user = userWithoutShop();
        when(currentMerchant.user()).thenReturn(user);
        when(shopRepository.existsBySlug("candle-shop")).thenReturn(false);
        when(shopRepository.saveAndFlush(any())).thenAnswer(inv -> {
            Shop shop = inv.getArgument(0);
            shop.setId(SHOP_ID);
            return shop;
        });
        when(shopOwnerRepository.attachShopIfNone(eq(7L), any())).thenReturn(1);

        MerchantShopDTO dto = service.create(createRequest("  Свечная лавка ", " Candle-Shop ", "#7F95A7"));

        ArgumentCaptor<Shop> saved = ArgumentCaptor.forClass(Shop.class);
        verify(shopRepository).saveAndFlush(saved.capture());
        assertEquals("candle-shop", saved.getValue().getSlug());
        assertEquals("Свечная лавка", saved.getValue().getName());
        assertEquals("#7f95a7", saved.getValue().getBrandColor());
        assertEquals(Boolean.TRUE, saved.getValue().getActive());
        assertEquals(Boolean.FALSE, saved.getValue().getTinkoffReceiptEnabled());
        verify(shopOwnerRepository).attachShopIfNone(7L, saved.getValue());

        assertEquals(SHOP_ID, dto.id());
        assertEquals("/shop/candle-shop", dto.storefrontPath());
        assertTrue(dto.active());
        assertFalse(dto.acceptsOrders());
        assertEquals(List.of(ShopReadiness.PAYMENT, ShopReadiness.DELIVERY, ShopReadiness.SELLER), dto.missingForOrders());
        assertFalse(dto.tinkoffPasswordSet());
        assertNull(dto.logoUrl());
    }

    @Test
    void createConflictsWhenUserAlreadyHasShop() {
        User user = userWithoutShop();
        user.setShop(existingShop());
        when(currentMerchant.user()).thenReturn(user);

        assertEquals(HttpStatus.CONFLICT, statusOf(() -> service.create(createRequest("Свечи", "candles2", "#111111"))));
        verify(shopRepository, never()).saveAndFlush(any());
    }

    @Test
    void createConflictsWhenSlugTaken() {
        when(currentMerchant.user()).thenReturn(userWithoutShop());
        when(shopRepository.existsBySlug("candles")).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.create(createRequest("Свечи", "candles", "#111111")));
        assertEquals(HttpStatus.CONFLICT.value(), e.getStatusCode().value());
        assertEquals("Адрес занят", e.getReason());
        verify(shopRepository, never()).saveAndFlush(any());
    }

    @Test
    void createConflictsWhenSlugTakenConcurrently() {
        when(currentMerchant.user()).thenReturn(userWithoutShop());
        when(shopRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.create(createRequest("Свечи", "candles", "#111111")));
        assertEquals(HttpStatus.CONFLICT.value(), e.getStatusCode().value());
        assertEquals("Адрес занят", e.getReason());
        verify(shopOwnerRepository, never()).attachShopIfNone(anyLong(), any());
    }

    @Test
    void createConflictsWhenParallelOnboardingAlreadyAttachedShop() {
        when(currentMerchant.user()).thenReturn(userWithoutShop());
        when(shopRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(shopOwnerRepository.attachShopIfNone(anyLong(), any())).thenReturn(0);

        assertEquals(HttpStatus.CONFLICT, statusOf(() -> service.create(createRequest("Свечи", "candles", "#111111"))));
    }

    @Test
    void createRejectsInvalidFields() {
        when(currentMerchant.user()).thenReturn(userWithoutShop());

        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.create(createRequest("Свечи", "ab", "#111111"))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.create(createRequest("Свечи", "admin", "#111111"))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.create(createRequest("Свечи", "candles", "red"))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.create(createRequest("Свечи", "candles", "#12345"))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.create(createRequest(" С ", "candles", "#111111"))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.create(createRequest(null, "candles", "#111111"))));
        assertEquals(HttpStatus.BAD_REQUEST,
                statusOf(() -> service.create(createRequest("x".repeat(121), "candles", "#111111"))));
        verify(shopRepository, never()).saveAndFlush(any());
    }

    @Test
    void checkSlugExplainsRulesReservedAndTaken() {
        when(shopRepository.existsBySlug("taken")).thenReturn(true);

        assertEquals(SlugAvailabilityDTO.ok(), service.checkSlug(" Free-Shop "));
        assertEquals(new SlugAvailabilityDTO(false, "Адрес занят"), service.checkSlug("taken"));
        assertEquals(new SlugAvailabilityDTO(false, "Этот адрес зарезервирован, выберите другой"),
                service.checkSlug("checkout"));
        SlugAvailabilityDTO invalid = service.checkSlug("a_b");
        assertFalse(invalid.available());
        assertNotNull(invalid.reason());
        assertFalse(service.checkSlug(null).available());
    }

    // ---------- дизайн ----------

    @Test
    void saveDesignTrimsLowercasesAndTurnsBlankIntoNull() {
        Shop shop = givenMerchantShop();
        shop.setTagline("старый заголовок");
        ShopDesignRequestDTO request = new ShopDesignRequestDTO();
        request.setName(" Лавка ");
        request.setBrandColor("#AABBCC");
        request.setTagline("   ");
        request.setDescription(" Свечи ручной работы ");
        request.setAbout("");

        MerchantShopDTO dto = service.saveDesign(request);

        assertEquals("Лавка", shop.getName());
        assertEquals("#aabbcc", shop.getBrandColor());
        assertNull(shop.getTagline());
        assertEquals("Свечи ручной работы", shop.getDescription());
        assertNull(shop.getAbout());
        assertEquals("#aabbcc", dto.brandColor());
        verify(shopRepository).save(shop);
    }

    @Test
    void saveDesignEnforcesLengthLimits() {
        givenMerchantShop();

        ShopDesignRequestDTO ok = new ShopDesignRequestDTO();
        ok.setName("Лавка");
        ok.setBrandColor("#111111");
        ok.setTagline("т".repeat(160));
        ok.setDescription("о".repeat(600));
        ok.setAbout("а".repeat(4000));
        service.saveDesign(ok);

        ShopDesignRequestDTO longTagline = copy(ok);
        longTagline.setTagline("т".repeat(161));
        assertEquals("Заголовок: не длиннее 160 символов", reasonOf(() -> service.saveDesign(longTagline)));

        ShopDesignRequestDTO longDescription = copy(ok);
        longDescription.setDescription("о".repeat(601));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveDesign(longDescription)));

        ShopDesignRequestDTO longAbout = copy(ok);
        longAbout.setAbout("а".repeat(4001));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveDesign(longAbout)));

        ShopDesignRequestDTO badColor = copy(ok);
        badColor.setBrandColor("#gggggg");
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveDesign(badColor)));
    }

    private static ShopDesignRequestDTO copy(ShopDesignRequestDTO source) {
        ShopDesignRequestDTO copy = new ShopDesignRequestDTO();
        copy.setName(source.getName());
        copy.setBrandColor(source.getBrandColor());
        copy.setTagline(source.getTagline());
        copy.setDescription(source.getDescription());
        copy.setAbout(source.getAbout());
        return copy;
    }

    // ---------- настройки ----------

    private static ShopSettingsRequestDTO settings() {
        ShopSettingsRequestDTO request = new ShopSettingsRequestDTO();
        request.setSupportTelegram("@candle_support");
        request.setContactEmail(" Shop@Example.com ");
        request.setContactPhone("+7 (999) 123-45-67");
        request.setSellerName("ИП Иванов Иван Иванович");
        request.setSellerInn("7707 0838 93");
        request.setSellerOgrn("304500116000157");
        request.setSellerAddress("г. Москва, ул. Пушкина, д. 1");
        request.setCdekFromPvzCode(" MSK123 ");
        return request;
    }

    private static CdekPvzDTO pvz(String code, String address, String fullAddress) {
        return CdekPvzDTO.builder()
                .code(code)
                .city("Москва")
                .postalCode("101000")
                .address(address)
                .fullAddress(fullAddress)
                .build();
    }

    @Test
    void saveSettingsNormalizesFieldsAndCopiesPvzFromCache() {
        Shop shop = givenMerchantShop();
        when(cdekPvzLookup.findByCode("MSK123"))
                .thenReturn(Optional.of(pvz("MSK123", "ул. Тверская, 1", "Россия, Москва, ул. Тверская, 1")));

        MerchantShopDTO dto = service.saveSettings(settings());

        assertEquals("candle_support", shop.getSupportTelegram());
        assertEquals("shop@example.com", shop.getContactEmail());
        assertEquals("+7 (999) 123-45-67", shop.getContactPhone());
        assertEquals("7707083893", shop.getSellerInn());
        assertEquals("304500116000157", shop.getSellerOgrn());
        assertEquals("MSK123", shop.getCdekFromPvzCode());
        assertEquals("Москва", shop.getCdekFromCity());
        assertEquals("101000", shop.getCdekFromPostalCode());
        assertEquals("ул. Тверская, 1", shop.getCdekFromAddress());
        // ПВЗ выбран, но свой аккаунт СДЭК не подключён — доставку всё ещё не посчитать
        assertEquals(List.of(ShopReadiness.PAYMENT, ShopReadiness.DELIVERY), dto.missingForOrders());
        assertEquals("ул. Тверская, 1", dto.cdekFromAddress());
    }

    @Test
    void saveSettingsFallsBackToFullAddress() {
        Shop shop = givenMerchantShop();
        when(cdekPvzLookup.findByCode("MSK123")).thenReturn(Optional.of(pvz("MSK123", " ", "Россия, Москва, ул. Тверская, 1")));

        service.saveSettings(settings());

        assertEquals("Россия, Москва, ул. Тверская, 1", shop.getCdekFromAddress());
    }

    @Test
    void saveSettingsRejectsUnknownNewPvz() {
        Shop shop = givenMerchantShop();
        when(cdekPvzLookup.findByCode(anyString())).thenReturn(Optional.empty());

        assertEquals("Пункт выдачи СДЭК не найден: MSK123", reasonOf(() -> service.saveSettings(settings())));
        assertNull(shop.getCdekFromPvzCode());
        verify(shopRepository, never()).save(any());
    }

    @Test
    void saveSettingsKeepsSavedPvzWhenItIsTemporarilyMissingFromCache() {
        Shop shop = givenMerchantShop();
        shop.setCdekFromPvzCode("MSK123");
        shop.setCdekFromCity("Москва");
        shop.setCdekFromAddress("ул. Тверская, 1");
        when(cdekPvzLookup.findByCode("MSK123")).thenReturn(Optional.empty());

        ShopSettingsRequestDTO request = settings();
        request.setSupportTelegram("new_support");
        service.saveSettings(request);

        assertEquals("new_support", shop.getSupportTelegram());
        assertEquals("MSK123", shop.getCdekFromPvzCode());
        assertEquals("ул. Тверская, 1", shop.getCdekFromAddress());
    }

    @Test
    void saveSettingsBlankPvzClearsDeliveryAndBlankFieldsBecomeNull() {
        Shop shop = givenMerchantShop();
        shop.setCdekFromPvzCode("MSK123");
        shop.setCdekFromCity("Москва");
        shop.setCdekFromPostalCode("101000");
        shop.setCdekFromAddress("ул. Тверская, 1");
        shop.setSupportTelegram("old");

        ShopSettingsRequestDTO request = new ShopSettingsRequestDTO();
        request.setSupportTelegram(" ");
        request.setCdekFromPvzCode("");
        MerchantShopDTO dto = service.saveSettings(request);

        assertNull(shop.getSupportTelegram());
        assertNull(shop.getCdekFromPvzCode());
        assertNull(shop.getCdekFromCity());
        assertNull(shop.getCdekFromPostalCode());
        assertNull(shop.getCdekFromAddress());
        assertTrue(dto.missingForOrders().contains(ShopReadiness.DELIVERY));
        verify(cdekPvzLookup, never()).findByCode(anyString());
    }

    @Test
    void saveSettingsAcceptsTelegramLinks() {
        assertEquals("candle_support", ShopServiceImpl.normalizeTelegram("https://t.me/candle_support"));
        assertEquals("candle_support", ShopServiceImpl.normalizeTelegram("t.me/candle_support/"));
        assertEquals("candle_support", ShopServiceImpl.normalizeTelegram(" @candle_support "));
        assertNull(ShopServiceImpl.normalizeTelegram("  "));
    }

    @Test
    void saveSettingsValidatesFormats() {
        givenMerchantShop();
        when(cdekPvzLookup.findByCode(anyString())).thenReturn(Optional.of(pvz("MSK123", "a", "b")));

        ShopSettingsRequestDTO shortTelegram = settings();
        shortTelegram.setSupportTelegram("abc");
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveSettings(shortTelegram)));

        ShopSettingsRequestDTO badTelegram = settings();
        badTelegram.setSupportTelegram("candle-support");
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveSettings(badTelegram)));

        ShopSettingsRequestDTO badInn = settings();
        badInn.setSellerInn("12345678901");
        assertEquals("ИНН: 10 или 12 цифр", reasonOf(() -> service.saveSettings(badInn)));

        ShopSettingsRequestDTO badOgrn = settings();
        badOgrn.setSellerOgrn("12345678901234");
        assertEquals("ОГРН/ОГРНИП: 13 или 15 цифр", reasonOf(() -> service.saveSettings(badOgrn)));

        ShopSettingsRequestDTO badEmail = settings();
        badEmail.setContactEmail("not-an-email");
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveSettings(badEmail)));

        ShopSettingsRequestDTO badPhone = settings();
        badPhone.setContactPhone("12-34");
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveSettings(badPhone)));

        ShopSettingsRequestDTO ogrn13 = settings();
        ogrn13.setSellerOgrn("1027700132195");
        service.saveSettings(ogrn13);
    }

    // ---------- оплата ----------

    private static ShopPaymentRequestDTO payment(String terminalKey, String password, Boolean receipt,
                                                 String taxation, String tax) {
        ShopPaymentRequestDTO request = new ShopPaymentRequestDTO();
        request.setTerminalKey(terminalKey);
        request.setPassword(password);
        request.setReceiptEnabled(receipt);
        request.setTaxation(taxation);
        request.setTax(tax);
        return request;
    }

    // ---------- ЮKassa и выбор провайдера ----------

    private static ShopYookassaRequestDTO yookassa(String shopId, String secretKey, boolean receipt,
                                                   String taxation, String tax) {
        ShopYookassaRequestDTO request = new ShopYookassaRequestDTO();
        request.setShopId(shopId);
        request.setSecretKey(secretKey);
        request.setReceiptEnabled(receipt);
        request.setTaxation(taxation);
        request.setTax(tax);
        return request;
    }

    private static ShopPaymentProviderRequestDTO provider(PaymentProvider provider) {
        ShopPaymentProviderRequestDTO request = new ShopPaymentProviderRequestDTO();
        request.setProvider(provider);
        return request;
    }

    @Test
    void saveYookassaChecksKeysEncryptsSecretAndBecomesActive() throws Exception {
        Shop shop = givenMerchantShop();
        when(yookassaClient.check(any())).thenReturn(YookassaClient.Check.OK);

        MerchantShopDTO dto = service.saveYookassa(yookassa(" 123456 ", " live_secret ", true, "usn_income", "vat22"));

        ArgumentCaptor<YookassaCredentials> checked = ArgumentCaptor.forClass(YookassaCredentials.class);
        verify(yookassaClient).check(checked.capture());
        assertEquals(new YookassaCredentials("123456", "live_secret"), checked.getValue());
        assertEquals("123456", shop.getYookassaShopId());
        assertEquals("live_secret", secretCipher.decrypt(shop.getYookassaSecretKeyEnc()));
        assertEquals(Boolean.TRUE, shop.getYookassaReceiptEnabled());
        assertEquals("usn_income", shop.getYookassaTaxation());
        assertEquals("vat22", shop.getYookassaTax());
        assertEquals(PaymentProvider.YOOKASSA, shop.getPaymentProvider());
        assertEquals(PaymentProvider.YOOKASSA, dto.paymentProvider());
        assertTrue(dto.yookassaSecretKeySet());
        assertFalse(dto.missingForOrders().contains(ShopReadiness.PAYMENT));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(dto);
        assertFalse(json.contains("live_secret"));
        assertFalse(json.contains(shop.getYookassaSecretKeyEnc()));
    }

    @Test
    void saveYookassaRejectedOrUnavailableKeysAreNotSaved() {
        Shop shop = givenMerchantShop();
        when(yookassaClient.check(any())).thenReturn(YookassaClient.Check.REJECTED);
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveYookassa(yookassa("123", "bad", false, null, null))));
        when(yookassaClient.check(any())).thenReturn(YookassaClient.Check.UNAVAILABLE);
        assertEquals(HttpStatus.BAD_GATEWAY, statusOf(() -> service.saveYookassa(yookassa("123", "key", false, null, null))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveYookassa(yookassa("shop-12", "key", false, null, null))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveYookassa(yookassa("123", "key", true, "osn", null))));
        assertNull(shop.getYookassaShopId());
        verify(shopRepository, never()).save(any());
    }

    @Test
    void saveYookassaBlankKeyKeepsSavedOnlyForSameShopId() {
        Shop shop = givenMerchantShop();
        shop.setYookassaShopId("123");
        String enc = secretCipher.encrypt("live_old");
        shop.setYookassaSecretKeyEnc(enc);

        service.saveYookassa(yookassa("123", null, false, null, null));
        assertEquals(enc, shop.getYookassaSecretKeyEnc());
        verify(yookassaClient, never()).check(any());

        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveYookassa(yookassa("456", null, false, null, null))));
        assertEquals("123", shop.getYookassaShopId());
    }

    @Test
    void connectingSecondProviderSwitchesToItAndDisconnectingFallsBack() {
        Shop shop = givenMerchantShop();
        service.savePayment(payment("TK", "pw", false, null, null));
        assertEquals(PaymentProvider.TINKOFF, shop.getPaymentProvider());

        when(yookassaClient.check(any())).thenReturn(YookassaClient.Check.OK);
        service.saveYookassa(yookassa("123", "live_key", false, null, null));
        assertEquals(PaymentProvider.YOOKASSA, shop.getPaymentProvider());

        // Пересохранение уже подключённого Т-Банка (например, включили чек) выбор не перехватывает
        service.savePayment(payment("TK", null, false, null, null));
        assertEquals(PaymentProvider.YOOKASSA, shop.getPaymentProvider());

        MerchantShopDTO dto = service.saveYookassa(yookassa("", null, false, null, null));
        assertNull(shop.getYookassaShopId());
        assertNull(shop.getYookassaSecretKeyEnc());
        assertEquals(PaymentProvider.TINKOFF, shop.getPaymentProvider());
        assertEquals(PaymentProvider.TINKOFF, dto.paymentProvider());

        service.savePayment(payment("", null, false, null, null));
        assertNull(shop.getPaymentProvider());
    }

    @Test
    void selectPaymentProviderNeedsConnectedProvider() {
        Shop shop = givenMerchantShop();
        shop.setTinkoffTerminalKey("TK");
        shop.setTinkoffPasswordEnc(secretCipher.encrypt("pw"));
        shop.setYookassaShopId("123");
        shop.setYookassaSecretKeyEnc(secretCipher.encrypt("live_key"));

        assertEquals(PaymentProvider.YOOKASSA,
                service.selectPaymentProvider(provider(PaymentProvider.YOOKASSA)).paymentProvider());
        assertEquals(PaymentProvider.TINKOFF,
                service.selectPaymentProvider(provider(PaymentProvider.TINKOFF)).paymentProvider());

        shop.setYookassaSecretKeyEnc(null);
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.selectPaymentProvider(provider(PaymentProvider.YOOKASSA))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.selectPaymentProvider(provider(null))));
        assertEquals(PaymentProvider.TINKOFF, shop.getPaymentProvider());
    }

    @Test
    void savePaymentEncryptsPasswordAndNeverReturnsIt() throws Exception {
        Shop shop = givenMerchantShop();

        MerchantShopDTO dto = service.savePayment(payment(" 1700000000000DEMO ", " s3cret ", true, "USN_INCOME", "none"));

        assertEquals("1700000000000DEMO", shop.getTinkoffTerminalKey());
        assertNotNull(shop.getTinkoffPasswordEnc());
        assertNotEquals("s3cret", shop.getTinkoffPasswordEnc());
        assertEquals("s3cret", secretCipher.decrypt(shop.getTinkoffPasswordEnc()));
        assertEquals(Boolean.TRUE, shop.getTinkoffReceiptEnabled());
        assertEquals("usn_income", shop.getTinkoffTaxation());
        assertEquals("none", shop.getTinkoffTax());
        assertTrue(dto.tinkoffPasswordSet());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(dto);
        assertFalse(json.contains("s3cret"));
        assertFalse(json.contains(shop.getTinkoffPasswordEnc()));
        assertFalse(json.toLowerCase().contains("passwordenc"));
    }

    @Test
    void savePaymentBlankPasswordKeepsSavedOneForSameTerminal() {
        Shop shop = givenMerchantShop();
        shop.setTinkoffTerminalKey("1700000000000DEMO");
        String enc = secretCipher.encrypt("old");
        shop.setTinkoffPasswordEnc(enc);

        service.savePayment(payment("1700000000000DEMO", "", false, null, null));

        assertEquals(enc, shop.getTinkoffPasswordEnc());
        assertEquals(Boolean.FALSE, shop.getTinkoffReceiptEnabled());
    }

    @Test
    void savePaymentChangedTerminalWithoutPasswordDropsOldPassword() {
        Shop shop = givenMerchantShop();
        shop.setTinkoffTerminalKey("1700000000000DEMO");
        shop.setTinkoffPasswordEnc(secretCipher.encrypt("old"));

        MerchantShopDTO dto = service.savePayment(payment("1700000000000", null, false, null, null));

        assertEquals("1700000000000", shop.getTinkoffTerminalKey());
        assertNull(shop.getTinkoffPasswordEnc());
        assertFalse(dto.tinkoffPasswordSet());
        assertTrue(dto.missingForOrders().contains(ShopReadiness.PAYMENT));
    }

    @Test
    void savePaymentBlankTerminalResetsKeyAndPassword() {
        Shop shop = givenMerchantShop();
        shop.setTinkoffTerminalKey("1700000000000DEMO");
        shop.setTinkoffPasswordEnc(secretCipher.encrypt("old"));

        service.savePayment(payment("  ", "ignored", false, null, null));

        assertNull(shop.getTinkoffTerminalKey());
        assertNull(shop.getTinkoffPasswordEnc());
    }

    @Test
    void savePaymentReceiptNeedsTaxationAndTax() {
        Shop shop = givenMerchantShop();

        assertEquals(HttpStatus.BAD_REQUEST,
                statusOf(() -> service.savePayment(payment("TK", "pw", true, null, "vat22"))));
        assertEquals(HttpStatus.BAD_REQUEST,
                statusOf(() -> service.savePayment(payment("TK", "pw", true, "osn", null))));
        assertEquals(HttpStatus.BAD_REQUEST,
                statusOf(() -> service.savePayment(payment("TK", "pw", true, "osn", "vat18"))));
        // НДС 20% с 2026 года нет в API Т-Банка — Init с ним не прошёл бы
        assertEquals(HttpStatus.BAD_REQUEST,
                statusOf(() -> service.savePayment(payment("TK", "pw", true, "osn", "vat20"))));
        assertEquals(HttpStatus.BAD_REQUEST,
                statusOf(() -> service.savePayment(payment("TK", "pw", false, "simplified", null))));
        assertEquals(HttpStatus.BAD_REQUEST,
                statusOf(() -> service.savePayment(payment("TK with space", "pw", false, null, null))));
        assertNull(shop.getTinkoffTerminalKey());
        verify(shopRepository, never()).save(any());

        // Чек выключен, но выбранные значения сохраняются — включит позже без повторного выбора
        service.savePayment(payment("TK", "pw", false, "patent", "vat22"));
        assertEquals("patent", shop.getTinkoffTaxation());
        assertEquals("vat22", shop.getTinkoffTax());
        assertEquals(Boolean.FALSE, shop.getTinkoffReceiptEnabled());
    }

    // ---------- СДЭК ----------

    private static ShopCdekRequestDTO cdek(String clientId, String clientSecret) {
        ShopCdekRequestDTO request = new ShopCdekRequestDTO();
        request.setClientId(clientId);
        request.setClientSecret(clientSecret);
        return request;
    }

    @Test
    void saveCdekChecksKeysEncryptsSecretAndNeverReturnsIt() throws Exception {
        Shop shop = givenMerchantShop();
        when(cdekAuthService.check(any())).thenReturn(CdekAuthService.Check.OK);

        MerchantShopDTO dto = service.saveCdek(cdek(" acc-123 ", " s3cret+= "));

        verify(cdekAuthService).check(new CdekCredentials("acc-123", "s3cret+="));
        assertEquals("acc-123", shop.getCdekClientId());
        assertEquals("s3cret+=", secretCipher.decrypt(shop.getCdekClientSecretEnc()));
        assertEquals("acc-123", dto.cdekClientId());
        assertTrue(dto.cdekSecretSet());
        verify(eventPublisher).publishEvent(new CdekConnectedEvent(shop.getSlug()));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(dto);
        assertFalse(json.contains("s3cret"));
        assertFalse(json.contains(shop.getCdekClientSecretEnc()));
    }

    @Test
    void saveCdekRejectedKeysAre400AndNothingSaved() {
        Shop shop = givenMerchantShop();
        when(cdekAuthService.check(any())).thenReturn(CdekAuthService.Check.REJECTED);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.saveCdek(cdek("acc", "wrong")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals("СДЭК не принял ключи: проверьте Account и Secure password", e.getReason());
        assertNull(shop.getCdekClientId());
        verify(shopRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void saveCdekWhenCdekDownIs502AndNothingSaved() {
        givenMerchantShop();
        when(cdekAuthService.check(any())).thenReturn(CdekAuthService.Check.UNAVAILABLE);

        assertEquals(HttpStatus.BAD_GATEWAY, statusOf(() -> service.saveCdek(cdek("acc", "secret"))));
        verify(shopRepository, never()).save(any());
    }

    @Test
    void saveCdekBlankSecretKeepsSavedOneForSameAccountWithoutCheck() {
        Shop shop = givenMerchantShop();
        shop.setCdekClientId("acc");
        String enc = secretCipher.encrypt("old");
        shop.setCdekClientSecretEnc(enc);

        service.saveCdek(cdek("acc", " "));

        assertEquals(enc, shop.getCdekClientSecretEnc());
        verify(cdekAuthService, never()).check(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void saveCdekNewAccountNeedsSecret() {
        Shop shop = givenMerchantShop();
        shop.setCdekClientId("acc");
        shop.setCdekClientSecretEnc(secretCipher.encrypt("old"));

        assertEquals("Введите Secure password для этого Account СДЭК",
                reasonOf(() -> service.saveCdek(cdek("other-acc", null))));
        assertEquals("acc", shop.getCdekClientId());

        Shop fresh = givenMerchantShop();
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveCdek(cdek("acc", null))));
        assertNull(fresh.getCdekClientId());
        verify(cdekAuthService, never()).check(any());
    }

    @Test
    void saveCdekBlankAccountDisconnects() {
        Shop shop = givenMerchantShop();
        shop.setCdekClientId("acc");
        shop.setCdekClientSecretEnc(secretCipher.encrypt("old"));

        MerchantShopDTO dto = service.saveCdek(cdek("  ", "ignored"));

        assertNull(shop.getCdekClientId());
        assertNull(shop.getCdekClientSecretEnc());
        assertFalse(dto.cdekSecretSet());
        assertTrue(dto.missingForOrders().contains(ShopReadiness.DELIVERY));
        verify(cdekAuthService, never()).check(any());
    }

    @Test
    void saveCdekValidatesFormat() {
        givenMerchantShop();

        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveCdek(cdek("acc with space", "secret"))));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.saveCdek(cdek("acc", "x".repeat(257)))));
        verify(cdekAuthService, never()).check(any());
    }

    // ---------- публичное ----------

    @Test
    void publicShopsListsOnlyActiveShopsWithActiveProducts() {
        Shop withProducts = existingShop();
        withProducts.setLogoKey("shops/x/branding/logo/a.png");
        Shop empty = existingShop();
        empty.setId(UUID.randomUUID());
        empty.setSlug("empty");
        when(shopRepository.findAllByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of(empty, withProducts));
        when(statsRepository.countActiveProductsOfActiveShops()).thenReturn(List.of(count(SHOP_ID, 3L)));

        List<PublicShopCardDTO> cards = service.getPublicShops();

        assertEquals(1, cards.size());
        assertEquals("candles", cards.get(0).slug());
        assertEquals(3L, cards.get(0).productsCount());
        assertEquals("https://s3.example/shops/x/branding/logo/a.png", cards.get(0).logoUrl());
        assertNull(cards.get(0).heroImageUrl());
    }

    private static ShopCatalogStatsRepository.ShopActiveProducts count(UUID shopId, long count) {
        return new ShopCatalogStatsRepository.ShopActiveProducts() {
            @Override
            public UUID getShopId() {
                return shopId;
            }

            @Override
            public Long getProductsCount() {
                return count;
            }
        };
    }

    @Test
    void publicShopIsFoundBySlugAndHiddenWhenInactive() {
        Shop shop = existingShop();
        shop.setSupportTelegram("candle_support");
        shop.setSellerName("ИП Иванов");
        when(shopRepository.findBySlug("candles")).thenReturn(Optional.of(shop));

        PublicShopDTO dto = service.getPublicShop("Candles");
        assertEquals("candles", dto.slug());
        assertEquals("candle_support", dto.supportTelegram());
        assertFalse(dto.acceptsOrders());

        shop.setActive(false);
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> service.getPublicShop("candles")));
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> service.getPublicShop("missing")));
    }

    // ---------- контракт JSON ----------

    @Test
    void merchantShopJsonHasExactlyTheContractFields() throws Exception {
        Shop shop = givenMerchantShop();
        MerchantShopDTO dto = new ShopMapper(s3FileStorage).toMerchant(shop);

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(dto);
        Set<String> fields = new TreeSet<>();
        for (Iterator<String> it = json.fieldNames(); it.hasNext(); ) {
            fields.add(it.next());
        }
        assertEquals(new TreeSet<>(Set.of(
                "id", "slug", "name", "active", "storefrontPath", "customDomain",
                "brandColor", "logoUrl", "heroImageUrl", "tagline", "description", "about",
                "supportTelegram", "contactEmail", "contactPhone",
                "sellerName", "sellerInn", "sellerOgrn", "sellerAddress",
                "cdekFromPvzCode", "cdekFromCity", "cdekFromAddress", "cdekClientId", "cdekSecretSet",
                "paymentProvider",
                "tinkoffTerminalKey", "tinkoffPasswordSet", "tinkoffReceiptEnabled", "tinkoffTaxation", "tinkoffTax",
                "yookassaShopId", "yookassaSecretKeySet", "yookassaReceiptEnabled", "yookassaTaxation", "yookassaTax",
                "acceptsOrders", "missingForOrders")), fields);
        assertTrue(json.get("active").isBoolean());
        assertTrue(json.get("missingForOrders").isArray());
    }

    @Test
    void publicShopJsonHasExactlyTheContractFields() {
        Shop shop = existingShop();
        JsonNode json = new ObjectMapper().valueToTree(new ShopMapper(s3FileStorage).toPublic(shop));
        Set<String> fields = new TreeSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertEquals(new TreeSet<>(Set.of(
                "slug", "name", "brandColor", "logoUrl", "heroImageUrl", "tagline", "description", "about",
                "supportTelegram", "contactEmail", "contactPhone", "sellerName", "sellerInn", "sellerOgrn",
                "sellerAddress", "acceptsOrders")), fields);

        JsonNode card = new ObjectMapper().valueToTree(new ShopMapper(s3FileStorage).toCard(shop, 2));
        Set<String> cardFields = new TreeSet<>();
        card.fieldNames().forEachRemaining(cardFields::add);
        assertEquals(new TreeSet<>(Set.of("slug", "name", "brandColor", "logoUrl", "heroImageUrl", "tagline",
                "productsCount")), cardFields);

        JsonNode availability = new ObjectMapper().valueToTree(SlugAvailabilityDTO.rejected("Адрес занят"));
        assertEquals(false, availability.get("available").asBoolean());
        assertEquals("Адрес занят", availability.get("reason").asText());
    }
}
