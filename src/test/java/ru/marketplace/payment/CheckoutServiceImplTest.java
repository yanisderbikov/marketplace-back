package ru.marketplace.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.SecretCipher;
import ru.marketplace.model.FulfillmentStatus;
import ru.marketplace.model.Order;
import ru.marketplace.model.OrderItem;
import ru.marketplace.model.PaymentProvider;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.model.Product;
import ru.marketplace.model.ProductVariant;
import ru.marketplace.model.Shop;
import ru.marketplace.payment.dto.CheckoutItemDTO;
import ru.marketplace.payment.dto.CheckoutRequestDTO;
import ru.marketplace.payment.dto.CheckoutResponseDTO;
import ru.marketplace.payment.dto.OrderStatusDTO;
import ru.marketplace.payment.dto.OrderStatusItemDTO;
import ru.marketplace.payment.dto.tinkoff.TinkoffInitRequest;
import ru.marketplace.payment.dto.tinkoff.TinkoffInitResponse;
import ru.marketplace.payment.dto.tinkoff.TinkoffReceiptItem;
import ru.marketplace.payment.dto.yookassa.YookassaCreatePaymentRequest;
import ru.marketplace.payment.dto.yookassa.YookassaPayment;
import ru.marketplace.payment.dto.yookassa.YookassaReceipt;
import ru.marketplace.repository.OrderRepository;
import ru.marketplace.repository.ProductRepository;
import ru.marketplace.repository.ShopRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Оформление заказа: серверные проверки корзины, заказ до Init, поля Init и чека, отказ банка. */
class CheckoutServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");
    private static final String SECRETS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String NOTIFICATION_URL = "https://api.market.example/api/public/payment/tinkoff-webhook";
    private static final String RETURN_URL = "https://market.example/shop/candles/success";

    private final ShopRepository shopRepository = mock(ShopRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentOrderRepository paymentOrderRepository = mock(PaymentOrderRepository.class);
    private final TinkoffClient tinkoffClient = mock(TinkoffClient.class);
    private final YookassaClient yookassaClient = mock(YookassaClient.class);
    private final OrderPaymentService orderPaymentService = mock(OrderPaymentService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final SecretCipher cipher = new SecretCipher(SECRETS_KEY);

    private CheckoutServiceImpl service;
    private Shop shop;
    private Product candle;
    private Product shirt;
    private ProductVariant shirtM;
    private final List<Order> savedOrders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new CheckoutServiceImpl(shopRepository, productRepository, orderRepository, paymentOrderRepository,
                tinkoffClient, yookassaClient, orderPaymentService, cipher, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager,
                NOTIFICATION_URL, " market.example , WWW.Market.Example ,");

        shop = Shop.builder()
                .id(UUID.randomUUID())
                .slug("candles")
                .name("Свечи Луны")
                .active(true)
                .brandColor("#7f95a7")
                .sellerName("ИП Иванов")
                .sellerInn("123456789012")
                .cdekFromPvzCode("MSK123")
                .cdekClientId("shop-cdek-account")
                .cdekClientSecretEnc(cipher.encrypt("shop-cdek-secret"))
                .tinkoffTerminalKey("ShopTerminal")
                .tinkoffPasswordEnc(cipher.encrypt("shop-password"))
                .tinkoffReceiptEnabled(false)
                .build();
        when(shopRepository.findBySlug("candles")).thenReturn(Optional.of(shop));

        candle = product("Свеча «Лаванда»", 89000L);
        shirt = product("Футболка", 150000L);
        shirtM = ProductVariant.builder().id(UUID.randomUUID()).product(shirt).label("M").priceKopecks(160000L)
                .orderNumber(0).build();
        shirt.getVariants().add(shirtM);
        shirt.getVariants().add(ProductVariant.builder().id(UUID.randomUUID()).product(shirt).label("L")
                .priceKopecks(170000L).orderNumber(1).build());

        when(orderRepository.existsByPublicId(anyString())).thenReturn(false);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            order.setId(42L);
            savedOrders.add(order);
            return order;
        });
    }

    private Product product(String name, long priceKopecks) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setShop(shop);
        product.setName(name);
        product.setDescription("");
        product.setPriceKopecks(priceKopecks);
        product.setActive(true);
        product.setPreorder(false);
        product.setOrderNumber(0);
        when(productRepository.findByIdAndShopId(product.getId(), shop.getId())).thenReturn(Optional.of(product));
        return product;
    }

    private static CheckoutItemDTO item(UUID productId, UUID variantId, Integer quantity) {
        CheckoutItemDTO item = new CheckoutItemDTO();
        item.setProductId(productId);
        item.setVariantId(variantId);
        item.setQuantity(quantity);
        return item;
    }

    private CheckoutRequestDTO request(CheckoutItemDTO... items) {
        CheckoutRequestDTO request = new CheckoutRequestDTO();
        request.setItems(new ArrayList<>(List.of(items)));
        request.setFullName("  Анна Петрова ");
        request.setPhone("8 (999) 123-45-67");
        request.setEmail(" anna@example.com ");
        request.setPvzCode("SPB42");
        request.setPvzCity("Санкт-Петербург");
        request.setPvzAddress("Невский пр., 1");
        request.setComment("  ");
        request.setMarketingConsent(true);
        request.setAcceptTerms(true);
        request.setReturnUrl(RETURN_URL);
        return request;
    }

    private CheckoutRequestDTO validRequest() {
        return request(item(candle.getId(), null, 2), item(shirt.getId(), shirtM.getId(), 1));
    }

    private void bankCreatesPayment() {
        TinkoffInitResponse response = new TinkoffInitResponse();
        response.setSuccess(true);
        response.setPaymentId("13660");
        response.setPaymentURL("https://securepay.tinkoff.ru/new/abc");
        when(tinkoffClient.init(any(TinkoffInitRequest.class), any(TinkoffCredentials.class))).thenReturn(response);
    }

    private ResponseStatusException rejected(CheckoutRequestDTO request) {
        return rejected("candles", request);
    }

    private ResponseStatusException rejected(String slug, CheckoutRequestDTO request) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.checkout(slug, request));
        assertTrue(savedOrders.isEmpty(), "заказ не должен создаваться");
        verify(tinkoffClient, never()).init(any(), any());
        return e;
    }

    // ── ЮKassa ──

    private void yookassaConnectedAndChosen(boolean receipt) {
        shop.setYookassaShopId("123456");
        shop.setYookassaSecretKeyEnc(cipher.encrypt("live_key"));
        shop.setYookassaReceiptEnabled(receipt);
        shop.setYookassaTaxation("usn_income");
        shop.setYookassaTax("vat22");
        shop.setPaymentProvider(PaymentProvider.YOOKASSA);
        YookassaPayment payment = new YookassaPayment();
        payment.setId("2d1b-yk");
        payment.setStatus("pending");
        payment.setConfirmation(new YookassaPayment.Confirmation("redirect", "https://yoomoney.ru/checkout/abc"));
        when(yookassaClient.createPayment(any(), anyString(), any())).thenReturn(payment);
    }

    @Test
    void chosenYookassaCreatesPaymentThereWithOrderInMetadataAndReceipt() {
        yookassaConnectedAndChosen(true);

        CheckoutResponseDTO response = service.checkout("candles", validRequest());

        Order order = savedOrders.get(0);
        assertEquals(PaymentProvider.YOOKASSA, order.getPaymentProvider());
        assertEquals("https://yoomoney.ru/checkout/abc", response.paymentUrl());
        verify(tinkoffClient, never()).init(any(), any());
        verify(paymentOrderRepository).savePayment(42L, "2d1b-yk", "https://yoomoney.ru/checkout/abc", NOW);

        ArgumentCaptor<YookassaCreatePaymentRequest> sent = ArgumentCaptor.forClass(YookassaCreatePaymentRequest.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<YookassaCredentials> credentials = ArgumentCaptor.forClass(YookassaCredentials.class);
        verify(yookassaClient).createPayment(sent.capture(), key.capture(), credentials.capture());
        YookassaCreatePaymentRequest request = sent.getValue();
        assertEquals(new YookassaCredentials("123456", "live_key"), credentials.getValue());
        assertEquals(CheckoutServiceImpl.idempotenceKey(order.getPublicId()), key.getValue());
        assertEquals("3380.00", request.getAmount().getValue());
        assertEquals("RUB", request.getAmount().getCurrency());
        assertTrue(request.getCapture());
        assertEquals("redirect", request.getConfirmation().getType());
        assertEquals(RETURN_URL + "?order=" + order.getPublicId(), request.getConfirmation().getReturnUrl());
        assertEquals(order.getPublicId(), request.getMetadata().get("order_id"));

        YookassaReceipt receipt = request.getReceipt();
        assertEquals(2, receipt.getTaxSystemCode());
        assertEquals("anna@example.com", receipt.getCustomer().getEmail());
        assertEquals("79991234567", receipt.getCustomer().getPhone());
        YookassaReceipt.Item candleLine = receipt.getItems().get(0);
        assertEquals("Свеча «Лаванда»", candleLine.getDescription());
        assertEquals(BigDecimal.valueOf(2), candleLine.getQuantity());
        assertEquals("890.00", candleLine.getAmount().getValue());
        assertEquals(11, candleLine.getVatCode());
        assertEquals("full_payment", candleLine.getPaymentMode());
        assertEquals("commodity", candleLine.getPaymentSubject());
        assertEquals("Футболка M", receipt.getItems().get(1).getDescription());
    }

    @Test
    void yookassaWithoutReceiptSendsNoReceipt() {
        yookassaConnectedAndChosen(false);

        service.checkout("candles", validRequest());

        ArgumentCaptor<YookassaCreatePaymentRequest> sent = ArgumentCaptor.forClass(YookassaCreatePaymentRequest.class);
        verify(yookassaClient).createPayment(sent.capture(), anyString(), any());
        assertNull(sent.getValue().getReceipt());
    }

    @Test
    void yookassaRejectionFailsOrderWithItsReason() {
        yookassaConnectedAndChosen(false);
        when(yookassaClient.createPayment(any(), anyString(), any()))
                .thenThrow(new YookassaClient.YookassaException("ЮKassa не приняла ключи магазина"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.checkout("candles", validRequest()));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
        assertTrue(e.getReason().contains("ЮKassa не приняла ключи магазина"));
        verify(orderPaymentService).markInitFailed(42L);
    }

    @Test
    void tinkoffStaysDefaultForShopsWithoutExplicitChoice() {
        bankCreatesPayment();

        service.checkout("candles", validRequest());

        assertEquals(PaymentProvider.TINKOFF, savedOrders.get(0).getPaymentProvider());
        verify(yookassaClient, never()).createPayment(any(), anyString(), any());
    }

    @Test
    void idempotenceKeyIsStablePerOrder() {
        assertEquals(CheckoutServiceImpl.idempotenceKey("A1B2C3"), CheckoutServiceImpl.idempotenceKey("A1B2C3"));
        assertFalse(CheckoutServiceImpl.idempotenceKey("A1B2C3").equals(CheckoutServiceImpl.idempotenceKey("A1B2C4")));
    }

    // ── Успешное оформление ──

    @Test
    void createsAwaitingOrderBeforeInitWithServerPrices() {
        bankCreatesPayment();

        CheckoutResponseDTO response = service.checkout(" Candles ", validRequest());

        assertEquals(1, savedOrders.size());
        Order order = savedOrders.get(0);
        assertEquals(response.orderPublicId(), order.getPublicId());
        assertTrue(order.getPublicId().matches("[A-Z0-9]{6}"));
        assertEquals("https://securepay.tinkoff.ru/new/abc", response.paymentUrl());
        assertEquals(PaymentStatus.AWAITING_PAYMENT, order.getPaymentStatus());
        assertEquals(FulfillmentStatus.NEW, order.getFulfillmentStatus());
        assertFalse(order.getPaidNotified());
        assertEquals(shop, order.getShop());
        assertEquals("Анна Петрова", order.getCustomerName());
        assertEquals("+79991234567", order.getCustomerPhone());
        assertEquals("anna@example.com", order.getCustomerEmail());
        assertEquals("SPB42", order.getPvzCode());
        assertEquals("Санкт-Петербург", order.getPvzCity());
        assertEquals("Невский пр., 1", order.getPvzAddress());
        assertNull(order.getComment());
        assertTrue(order.getMarketingConsent());
        assertEquals(NOW, order.getCreatedAt());
        // 2 × 890 ₽ + 1 × 1600 ₽ (цена варианта, а не товара)
        assertEquals(89000L * 2 + 160000L, order.getItemsTotalKopecks());

        List<OrderItem> items = order.getItems();
        assertEquals(2, items.size());
        assertEquals("Свеча «Лаванда»", items.get(0).getProductName());
        assertEquals(89000L, items.get(0).getUnitPriceKopecks());
        assertEquals(2, items.get(0).getQuantity());
        assertEquals(candle.getId(), items.get(0).getProductId());
        assertNull(items.get(0).getVariantId());
        assertEquals("Футболка M", items.get(1).getProductName());
        assertEquals(shirtM.getId(), items.get(1).getVariantId());
        assertTrue(items.stream().allMatch(i -> i.getOrder() == order));

        verify(paymentOrderRepository).savePayment(42L, "13660", "https://securepay.tinkoff.ru/new/abc", NOW);
        verify(orderPaymentService, never()).markInitFailed(anyLong());
        verify(transactionManager).commit(any(TransactionStatus.class));
    }

    @Test
    void initUsesShopTerminalAndSpecFields() {
        bankCreatesPayment();

        CheckoutResponseDTO response = service.checkout("candles", validRequest());
        String publicId = response.orderPublicId();

        ArgumentCaptor<TinkoffInitRequest> init = ArgumentCaptor.forClass(TinkoffInitRequest.class);
        ArgumentCaptor<TinkoffCredentials> credentials = ArgumentCaptor.forClass(TinkoffCredentials.class);
        verify(tinkoffClient).init(init.capture(), credentials.capture());

        assertEquals("ShopTerminal", credentials.getValue().terminalKey());
        assertEquals("shop-password", credentials.getValue().password());

        TinkoffInitRequest request = init.getValue();
        assertEquals(89000L * 2 + 160000L, request.getAmount());
        assertEquals(publicId, request.getOrderId());
        assertEquals("Заказ " + publicId + " в магазине Свечи Луны", request.getDescription());
        assertEquals("O", request.getPayType());
        assertEquals(NOTIFICATION_URL, request.getNotificationURL());
        assertEquals(RETURN_URL + "?order=" + publicId + "&status=success", request.getSuccessURL());
        assertEquals(RETURN_URL + "?order=" + publicId + "&status=fail", request.getFailURL());
        // 09:00 UTC + 15 минут = 12:15 МСК
        assertEquals("2026-10-01T12:15:00+03:00", request.getRedirectDueDate());
        assertNull(request.getReceipt(), "чек выключен в настройках магазина");
    }

    @Test
    void receiptIsSentWhenShopEnabledIt() {
        shop.setTinkoffReceiptEnabled(true);
        shop.setTinkoffTaxation("usn_income");
        shop.setTinkoffTax("none");
        candle.setName("С".repeat(200));
        bankCreatesPayment();

        service.checkout("candles", validRequest());

        ArgumentCaptor<TinkoffInitRequest> init = ArgumentCaptor.forClass(TinkoffInitRequest.class);
        verify(tinkoffClient).init(init.capture(), any());
        var receipt = init.getValue().getReceipt();
        assertEquals("anna@example.com", receipt.getEmail());
        assertEquals("+79991234567", receipt.getPhone());
        assertEquals("usn_income", receipt.getTaxation());
        assertEquals(2, receipt.getItems().size());

        TinkoffReceiptItem first = receipt.getItems().get(0);
        assertEquals(128, first.getName().length());
        assertEquals(89000L, first.getPrice());
        assertEquals(2, first.getQuantity());
        assertEquals(178000L, first.getAmount());
        assertEquals("none", first.getTax());
        assertEquals("full_payment", first.getPaymentMethod());
        assertEquals("commodity", first.getPaymentObject());
        assertEquals(init.getValue().getAmount(),
                receipt.getItems().stream().mapToLong(TinkoffReceiptItem::getAmount).sum());
    }

    @Test
    void receiptEmailLongerThan64IsRejectedBeforeOrder() {
        shop.setTinkoffReceiptEnabled(true);
        shop.setTinkoffTaxation("usn_income");
        shop.setTinkoffTax("none");
        CheckoutRequestDTO request = validRequest();
        request.setEmail("a".repeat(53) + "@example.com");

        ResponseStatusException e = rejected(request);

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals("Почта для чека — не длиннее 64 символов", e.getReason());
    }

    @Test
    void receiptEmailOf64CharsIsAccepted() {
        shop.setTinkoffReceiptEnabled(true);
        shop.setTinkoffTaxation("usn_income");
        shop.setTinkoffTax("none");
        bankCreatesPayment();
        CheckoutRequestDTO request = validRequest();
        String email = "a".repeat(52) + "@example.com";
        request.setEmail(email);

        service.checkout("candles", request);

        ArgumentCaptor<TinkoffInitRequest> init = ArgumentCaptor.forClass(TinkoffInitRequest.class);
        verify(tinkoffClient).init(init.capture(), any());
        assertEquals(64, init.getValue().getReceipt().getEmail().length());
    }

    @Test
    void longEmailIsFineWhenShopSendsNoReceipt() {
        bankCreatesPayment();
        CheckoutRequestDTO request = validRequest();
        request.setEmail("a".repeat(100) + "@example.com");

        service.checkout("candles", request);

        assertEquals(1, savedOrders.size());
        assertEquals("a".repeat(100) + "@example.com", savedOrders.get(0).getCustomerEmail());
    }

    @Test
    void returnUrlWithQueryGetsParamsAppended() {
        bankCreatesPayment();
        CheckoutRequestDTO request = validRequest();
        request.setReturnUrl("http://www.market.example:8080/shop/candles/success?from=cart");

        String publicId = service.checkout("candles", request).orderPublicId();

        ArgumentCaptor<TinkoffInitRequest> init = ArgumentCaptor.forClass(TinkoffInitRequest.class);
        verify(tinkoffClient).init(init.capture(), any());
        assertEquals("http://www.market.example:8080/shop/candles/success?from=cart&order=" + publicId + "&status=fail",
                init.getValue().getFailURL());
    }

    // ── Отказ банка ──

    @Test
    void initFailureMarksOrderFailedAndAnswers502() {
        when(tinkoffClient.init(any(), any())).thenThrow(new TinkoffClient.TinkoffException("Неверный токен."));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.checkout("candles", validRequest()));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
        assertEquals("Не удалось создать платёж: Неверный токен.", e.getReason());
        assertEquals(1, savedOrders.size(), "заказ создан до Init");
        verify(orderPaymentService).markInitFailed(42L);
        verify(paymentOrderRepository, never()).savePayment(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void unexpectedInitErrorIsAlso502() {
        when(tinkoffClient.init(any(), any())).thenThrow(new IllegalStateException("NPE где-то"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.checkout("candles", validRequest()));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
        assertEquals("Не удалось создать платёж: Т-Банк недоступен", e.getReason());
        verify(orderPaymentService).markInitFailed(42L);
    }

    @Test
    void undecryptablePasswordIs502WithoutOrder() {
        shop.setTinkoffPasswordEnc("bm90LWEtcmVhbC1jaXBoZXJ0ZXh0LXZhbHVl");

        ResponseStatusException e = rejected(validRequest());

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
    }

    // ── Магазин ──

    @Test
    void unknownShopIs404() {
        when(shopRepository.findBySlug("nope")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, rejected("nope", validRequest()).getStatusCode());
    }

    @Test
    void inactiveShopIs409() {
        shop.setActive(false);

        ResponseStatusException e = rejected(validRequest());

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertEquals("Магазин пока не принимает заказы", e.getReason());
    }

    @Test
    void shopWithoutTerminalOrSellerOrPvzIs409() {
        shop.setTinkoffPasswordEnc(null);
        assertEquals(HttpStatus.CONFLICT, rejected(validRequest()).getStatusCode());

        shop.setTinkoffPasswordEnc(cipher.encrypt("shop-password"));
        shop.setCdekFromPvzCode(null);
        assertEquals(HttpStatus.CONFLICT, rejected(validRequest()).getStatusCode());

        shop.setCdekFromPvzCode("MSK123");
        shop.setCdekClientSecretEnc(null);
        assertEquals(HttpStatus.CONFLICT, rejected(validRequest()).getStatusCode());

        shop.setCdekClientSecretEnc(cipher.encrypt("shop-cdek-secret"));
        shop.setSellerInn(" ");
        assertEquals(HttpStatus.CONFLICT, rejected(validRequest()).getStatusCode());
    }

    // ── Покупатель и корзина ──

    @Test
    void termsMustBeAccepted() {
        CheckoutRequestDTO request = validRequest();
        request.setAcceptTerms(false);
        assertEquals(HttpStatus.BAD_REQUEST, rejected(request).getStatusCode());

        request.setAcceptTerms(null);
        assertEquals(HttpStatus.BAD_REQUEST, rejected(request).getStatusCode());
    }

    @Test
    void emptyCartIsRejected() {
        CheckoutRequestDTO request = request();
        assertEquals("Корзина пуста", rejected(request).getReason());

        request.setItems(null);
        assertEquals("Корзина пуста", rejected(request).getReason());
    }

    @Test
    void productOfAnotherShopIsRejected() {
        UUID foreign = UUID.randomUUID();
        when(productRepository.findByIdAndShopId(foreign, shop.getId())).thenReturn(Optional.empty());

        ResponseStatusException e = rejected(request(item(foreign, null, 1)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals("Товар не найден: " + foreign, e.getReason());
    }

    @Test
    void inactiveProductIsRejected() {
        candle.setActive(false);

        ResponseStatusException e = rejected(request(item(candle.getId(), null, 1)));

        assertEquals("Товар недоступен для покупки: Свеча «Лаванда»", e.getReason());
    }

    @Test
    void variantIsRequiredWhenProductHasVariants() {
        ResponseStatusException e = rejected(request(item(shirt.getId(), null, 1)));

        assertEquals("Для товара нужно выбрать вариант: Футболка", e.getReason());
    }

    @Test
    void foreignVariantIsRejected() {
        ResponseStatusException e = rejected(request(item(shirt.getId(), UUID.randomUUID(), 1)));
        assertEquals("Вариант товара не найден: Футболка", e.getReason());

        // Вариант чужого товара тоже не подходит
        e = rejected(request(item(candle.getId(), shirtM.getId(), 1)));
        assertEquals("Вариант товара не найден: Свеча «Лаванда»", e.getReason());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 100, 1000})
    void quantityOutsideOneToNinetyNineIsRejected(int quantity) {
        ResponseStatusException e = rejected(request(item(candle.getId(), null, quantity)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().startsWith("Некорректное количество"));
    }

    @Test
    void missingQuantityOrProductIsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST, rejected(request(item(candle.getId(), null, null))).getStatusCode());
        assertEquals("Не указан товар", rejected(request(item(null, null, 1))).getReason());
    }

    @Test
    void boundaryQuantitiesAreAccepted() {
        bankCreatesPayment();

        service.checkout("candles", request(item(candle.getId(), null, 1), item(candle.getId(), null, 99)));

        assertEquals(89000L * 100, savedOrders.get(0).getItemsTotalKopecks());
    }

    @Test
    void badPhoneIsRejected() {
        CheckoutRequestDTO request = validRequest();
        request.setPhone("12-34");

        assertEquals("Некорректный номер телефона", rejected(request).getReason());
    }

    @Test
    void blankRequiredCustomerFieldsAreRejected() {
        CheckoutRequestDTO request = validRequest();
        request.setPvzCode(" ");
        assertEquals(HttpStatus.BAD_REQUEST, rejected(request).getStatusCode());

        request = validRequest();
        request.setFullName(null);
        assertEquals(HttpStatus.BAD_REQUEST, rejected(request).getStatusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example/shop/candles/success",
            "https://market.example.evil.example/success",
            "https://evil.example@market.example/success",
            "javascript:alert(1)",
            "ftp://market.example/success",
            "//market.example/success",
            "/shop/candles/success",
            "https://market.example/success#top",
            "https://market .example/success",
            " "})
    void badReturnUrlIsRejected(String returnUrl) {
        CheckoutRequestDTO request = validRequest();
        request.setReturnUrl(returnUrl);

        ResponseStatusException e = rejected(request);

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals(CheckoutServiceImpl.BAD_RETURN_URL, e.getReason());
    }

    @Test
    void allowedHostsAreParsedCaseInsensitively() {
        assertEquals(Set.of("market.example", "www.market.example"),
                CheckoutServiceImpl.parseHosts(" market.example , WWW.Market.Example ,"));
        assertEquals("HTTPS://Market.Example/s",
                CheckoutServiceImpl.validateReturnUrl(" HTTPS://Market.Example/s ", Set.of("market.example")));
    }

    @Test
    void emptyAllowedHostsFailAtStartup() {
        assertThrows(IllegalStateException.class, () -> new CheckoutServiceImpl(shopRepository, productRepository,
                orderRepository, paymentOrderRepository, tinkoffClient, yookassaClient, orderPaymentService, cipher,
                Clock.systemUTC(), transactionManager, NOTIFICATION_URL, " , "));
        assertThrows(IllegalStateException.class, () -> new CheckoutServiceImpl(shopRepository, productRepository,
                orderRepository, paymentOrderRepository, tinkoffClient, yookassaClient, orderPaymentService, cipher,
                Clock.systemUTC(), transactionManager, " ", "market.example"));
    }

    @Test
    void truncateKeepsSurrogatePairsWhole() {
        String name = "a".repeat(127) + "😀";
        assertEquals("a".repeat(127), CheckoutServiceImpl.truncate(name, 128));
        assertEquals("abc", CheckoutServiceImpl.truncate("abc", 128));
    }

    // ── Статус заказа ──

    private Order statusOrder(PaymentStatus status, Instant checkedAt) {
        Order order = new Order();
        order.setId(42L);
        order.setPublicId("A1B2C3");
        order.setShop(shop);
        order.setPaymentStatus(status);
        order.setItemsTotalKopecks(178000L);
        order.setPaymentId("13660");
        order.setPaymentCheckedAt(checkedAt);
        OrderItem second = OrderItem.builder().id(2L).order(order).productName("Свеча").quantity(1)
                .unitPriceKopecks(89000L).build();
        OrderItem first = OrderItem.builder().id(1L).order(order).productName("Футболка M").quantity(1)
                .unitPriceKopecks(89000L).build();
        order.getItems().add(second);
        order.getItems().add(first);
        return order;
    }

    @Test
    void statusOfPaidOrderDoesNotAskBank() {
        Order order = statusOrder(PaymentStatus.PAID, null);
        when(paymentOrderRepository.findWithShopAndItemsByPublicId("A1B2C3")).thenReturn(Optional.of(order));

        OrderStatusDTO status = service.orderStatus(" a1b2c3 ");

        assertEquals("A1B2C3", status.publicId());
        assertEquals("candles", status.shopSlug());
        assertEquals("Свечи Луны", status.shopName());
        assertEquals(PaymentStatus.PAID, status.paymentStatus());
        assertEquals(178000L, status.itemsTotalKopecks());
        assertEquals(List.of(new OrderStatusItemDTO("Футболка M", 1, 89000L), new OrderStatusItemDTO("Свеча", 1, 89000L)),
                status.items());
        verify(orderPaymentService, never()).checkWithBank(any(), any());
    }

    @Test
    void awaitingOrderAsksBankWhenNotCheckedRecently() {
        Order awaiting = statusOrder(PaymentStatus.AWAITING_PAYMENT, NOW.minus(Duration.ofSeconds(6)));
        Order paid = statusOrder(PaymentStatus.PAID, NOW);
        when(paymentOrderRepository.findWithShopAndItemsByPublicId("A1B2C3"))
                .thenReturn(Optional.of(awaiting), Optional.of(paid));
        when(orderPaymentService.claimBankCheck(42L, NOW, Duration.ofSeconds(5))).thenReturn(true);

        assertEquals(PaymentStatus.PAID, service.orderStatus("A1B2C3").paymentStatus());

        var inOrder = org.mockito.Mockito.inOrder(orderPaymentService);
        inOrder.verify(orderPaymentService).claimBankCheck(42L, NOW, Duration.ofSeconds(5));
        inOrder.verify(orderPaymentService).checkWithBank(awaiting, NOW);
    }

    @Test
    void concurrentRequestThatLostTheClaimDoesNotAskBank() {
        // Снимок говорит «пора спросить», но другой запрос уже застолбил проверку (атомарный UPDATE вернул 0)
        Order awaiting = statusOrder(PaymentStatus.AWAITING_PAYMENT, NOW.minus(Duration.ofSeconds(6)));
        when(paymentOrderRepository.findWithShopAndItemsByPublicId("A1B2C3")).thenReturn(Optional.of(awaiting));
        when(orderPaymentService.claimBankCheck(42L, NOW, Duration.ofSeconds(5))).thenReturn(false);

        assertEquals(PaymentStatus.AWAITING_PAYMENT, service.orderStatus("A1B2C3").paymentStatus());

        verify(orderPaymentService, never()).checkWithBank(any(), any());
    }

    @Test
    void claimErrorStillReturnsCurrentStatus() {
        Order awaiting = statusOrder(PaymentStatus.AWAITING_PAYMENT, null);
        when(paymentOrderRepository.findWithShopAndItemsByPublicId("A1B2C3")).thenReturn(Optional.of(awaiting));
        when(orderPaymentService.claimBankCheck(any(), any(), any())).thenThrow(new RuntimeException("БД моргнула"));

        assertEquals(PaymentStatus.AWAITING_PAYMENT, service.orderStatus("A1B2C3").paymentStatus());
        verify(orderPaymentService, never()).checkWithBank(any(), any());
    }

    @Test
    void awaitingOrderCheckedSecondsAgoDoesNotAskBank() {
        Order awaiting = statusOrder(PaymentStatus.AWAITING_PAYMENT, NOW.minus(Duration.ofSeconds(3)));
        when(paymentOrderRepository.findWithShopAndItemsByPublicId("A1B2C3")).thenReturn(Optional.of(awaiting));

        assertEquals(PaymentStatus.AWAITING_PAYMENT, service.orderStatus("A1B2C3").paymentStatus());

        verify(orderPaymentService, never()).checkWithBank(any(), any());
        verify(orderPaymentService, never()).claimBankCheck(any(), any(), any());
    }

    @Test
    void awaitingOrderWithoutPaymentIdDoesNotAskBank() {
        Order awaiting = statusOrder(PaymentStatus.AWAITING_PAYMENT, null);
        awaiting.setPaymentId(null);
        when(paymentOrderRepository.findWithShopAndItemsByPublicId("A1B2C3")).thenReturn(Optional.of(awaiting));

        service.orderStatus("A1B2C3");

        verify(orderPaymentService, never()).checkWithBank(any(), any());
    }

    @Test
    void bankCheckErrorStillReturnsCurrentStatus() {
        Order awaiting = statusOrder(PaymentStatus.AWAITING_PAYMENT, null);
        when(paymentOrderRepository.findWithShopAndItemsByPublicId("A1B2C3")).thenReturn(Optional.of(awaiting));
        when(orderPaymentService.claimBankCheck(any(), any(), any())).thenReturn(true);
        when(orderPaymentService.checkWithBank(any(), any())).thenThrow(new RuntimeException("БД моргнула"));

        assertEquals(PaymentStatus.AWAITING_PAYMENT, service.orderStatus("A1B2C3").paymentStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ABC", "A1B2C3D", "A1B2C!", "../../x"})
    void malformedOrUnknownPublicIdIs404(String publicId) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.orderStatus(publicId));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void unknownOrderIs404() {
        when(paymentOrderRepository.findWithShopAndItemsByPublicId(eq("ZZZZZZ"))).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.orderStatus("zzzzzz"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertEquals("Заказ не найден", e.getReason());
    }
}
