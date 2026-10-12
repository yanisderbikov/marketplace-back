package ru.marketplace.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import ru.marketplace.payment.dto.tinkoff.TinkoffReceipt;
import ru.marketplace.payment.dto.tinkoff.TinkoffReceiptItem;
import ru.marketplace.payment.dto.yookassa.YookassaAmount;
import ru.marketplace.payment.dto.yookassa.YookassaCreatePaymentRequest;
import ru.marketplace.payment.dto.yookassa.YookassaPayment;
import ru.marketplace.payment.dto.yookassa.YookassaReceipt;
import ru.marketplace.repository.OrderRepository;
import ru.marketplace.repository.ProductRepository;
import ru.marketplace.repository.ShopRepository;
import ru.marketplace.shop.ShopPayment;
import ru.marketplace.shop.ShopReadiness;
import ru.marketplace.util.MoneyUtil;
import ru.marketplace.util.PhoneUtil;
import ru.marketplace.util.PublicIdGenerator;

import java.net.URI;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Оформление заказа на витрине (order-first, как CartPurchaseServiceImpl в anyforms): заказ создаётся
 * сразу со статусом AWAITING_PAYMENT вместе с позициями, затем создаётся платёж у провайдера, которого
 * выбрал магазин (Т-Банк или ЮKassa), — ключами самого магазина, деньги идут мерчанту напрямую.
 * Вебхук/поллер переводят заказ в PAID.
 * <p>
 * Из anyforms убраны промокоды. Доставка СДЭК в платёж не входит — оплачивается при получении.
 * HTTP-вызов банка идёт вне транзакции: заказ уже закоммичен, и при отказе банка мы честно
 * переводим его в FAILED, а не откатываем (в anyforms весь purchase был одной транзакцией вместе с HTTP).
 */
@Service
@Slf4j
class CheckoutServiceImpl implements CheckoutService {

    static final int MAX_QUANTITY = 99;
    /** O — одностадийный платёж (списание сразу). */
    static final String PAY_TYPE_SINGLE_STAGE = "O";
    /** Признак способа расчёта в чеке: полная оплата. */
    static final String PAYMENT_METHOD = "full_payment";
    /** Предмет расчёта в чеке: товар. */
    static final String PAYMENT_OBJECT = "commodity";
    /** Лимит Т-Банка на длину названия позиции чека. */
    static final int ITEM_NAME_MAX_LENGTH = 128;
    /** Лимит Т-Банка на Receipt.Email (ФФД 1.05 и 1.2): длиннее — Init отклоняется. */
    static final int RECEIPT_EMAIL_MAX_LENGTH = 64;
    /** Лимит Т-Банка на длину Description в Init. */
    static final int DESCRIPTION_MAX_LENGTH = 250;
    /**
     * Срок жизни ссылки на оплату. После него банк переводит платёж в DEADLINE_EXPIRED, и заказ
     * становится FAILED (вебхуком или поллером). 15 минут — с запасом на 3DS и подтверждение
     * в приложении банка при оплате через СБП.
     */
    static final Duration PAYMENT_LINK_TTL = Duration.ofMinutes(15);
    /** Метод подтверждения ЮKassa: редирект покупателя на её страницу оплаты. */
    static final String YOOKASSA_CONFIRMATION_REDIRECT = "redirect";
    /** Лимит ЮKassa на description позиции чека. */
    static final int YOOKASSA_ITEM_NAME_MAX_LENGTH = 128;
    /** Система налогообложения (наши коды — как у Т-Банка) → tax_system_code ЮKassa. */
    static final Map<String, Integer> YOOKASSA_TAX_SYSTEM_CODES = Map.of(
            "osn", 1, "usn_income", 2, "usn_income_outcome", 3, "esn", 5, "patent", 6);
    /** Ставка НДС (наши коды) → vat_code ЮKassa: 1 без НДС, 2 — 0%, 3 — 10%, 7 — 5%, 8 — 7%, 11 — 22%. */
    static final Map<String, Integer> YOOKASSA_VAT_CODES = Map.of(
            "none", 1, "vat0", 2, "vat10", 3, "vat5", 7, "vat7", 8, "vat22", 11);

    /** Страница успеха опрашивает статус каждые пару секунд — банк спрашиваем не чаще раза в 5 секунд. */
    static final Duration STATUS_RECHECK_AFTER = Duration.ofSeconds(5);

    static final String NOT_ACCEPTING_ORDERS = "Магазин пока не принимает заказы";
    static final String BAD_RETURN_URL = "Некорректный адрес возврата после оплаты";

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter REDIRECT_DUE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final Pattern PUBLIC_ID_PATTERN = Pattern.compile("[A-Z0-9]{" + PublicIdGenerator.LENGTH + "}");

    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final TinkoffClient tinkoffClient;
    private final YookassaClient yookassaClient;
    private final OrderPaymentService orderPaymentService;
    private final SecretCipher secretCipher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final String notificationUrl;
    private final Set<String> allowedReturnHosts;

    CheckoutServiceImpl(ShopRepository shopRepository,
                        ProductRepository productRepository,
                        OrderRepository orderRepository,
                        PaymentOrderRepository paymentOrderRepository,
                        TinkoffClient tinkoffClient,
                        YookassaClient yookassaClient,
                        OrderPaymentService orderPaymentService,
                        SecretCipher secretCipher,
                        Clock clock,
                        PlatformTransactionManager transactionManager,
                        @Value("${payment.tinkoff.notification-url}") String notificationUrl,
                        @Value("${payment.allowed-return-hosts}") String allowedReturnHosts) {
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.tinkoffClient = tinkoffClient;
        this.yookassaClient = yookassaClient;
        this.orderPaymentService = orderPaymentService;
        this.secretCipher = secretCipher;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Без своего URL нотификаций банк слал бы их туда, куда настроен терминал мерчанта, — мимо нас
        if (notificationUrl == null || notificationUrl.isBlank()) {
            throw new IllegalStateException("PAYMENT_TINKOFF_NOTIFICATION_URL не задан");
        }
        this.notificationUrl = notificationUrl.trim();
        this.allowedReturnHosts = parseHosts(allowedReturnHosts);
        if (this.allowedReturnHosts.isEmpty()) {
            throw new IllegalStateException("PAYMENT_ALLOWED_RETURN_HOSTS не задан: некуда возвращать покупателя после оплаты");
        }
    }

    /** Позиция корзины после серверной валидации и прайсинга. */
    record PricedItem(UUID productId, UUID variantId, String name, int quantity, long unitKopecks) {
        long totalKopecks() {
            return Math.multiplyExact(unitKopecks, (long) quantity);
        }
    }

    /**
     * Заказ закоммичен, осталось создать платёж: всё, что для этого нужно, без ленивых сущностей.
     * Чек и ключи — того провайдера, через которого создаётся платёж (ключи другого — null).
     */
    record PendingPayment(Long orderId, String publicId, long totalKopecks, String shopSlug, String shopName,
                          PaymentProvider provider,
                          boolean receiptEnabled, String taxation, String tax,
                          String email, String phone, String returnUrl,
                          List<PricedItem> items, TinkoffCredentials tinkoffCredentials,
                          YookassaCredentials yookassaCredentials) {
    }

    /** Платёж создан: его id у провайдера и куда отправить покупателя. */
    private record CreatedPayment(String paymentId, String paymentUrl) {
    }

    @Override
    public CheckoutResponseDTO checkout(String slug, CheckoutRequestDTO request) {
        PendingPayment pending = Objects.requireNonNull(
                transactionTemplate.execute(tx -> createAwaitingOrder(slug, request)),
                "Заказ не создан");

        CreatedPayment payment;
        try {
            payment = pending.provider() == PaymentProvider.YOOKASSA ? createYookassaPayment(pending)
                    : createTinkoffPayment(pending);
        } catch (RuntimeException e) {
            boolean providerRejected = e instanceof TinkoffClient.TinkoffException
                    || e instanceof YookassaClient.YookassaException;
            if (providerRejected) {
                log.warn("Платёж заказа {} магазина {} не создан: {}", pending.publicId(), pending.shopSlug(), e.getMessage());
            } else {
                log.error("Платёж заказа {} магазина {} не создан", pending.publicId(), pending.shopSlug(), e);
            }
            orderPaymentService.markInitFailed(pending.orderId());
            String reason = providerRejected ? e.getMessage()
                    : (pending.provider() == PaymentProvider.YOOKASSA ? "ЮKassa недоступна" : "Т-Банк недоступен");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось создать платёж: " + reason);
        }

        paymentOrderRepository.savePayment(pending.orderId(), payment.paymentId(), payment.paymentUrl(),
                clock.instant());
        return new CheckoutResponseDTO(pending.publicId(), payment.paymentUrl());
    }

    private CreatedPayment createTinkoffPayment(PendingPayment pending) {
        TinkoffInitResponse response = tinkoffClient.init(buildInitRequest(pending), pending.tinkoffCredentials());
        return new CreatedPayment(response.getPaymentId(), response.getPaymentURL());
    }

    private CreatedPayment createYookassaPayment(PendingPayment pending) {
        YookassaPayment payment = yookassaClient.createPayment(buildYookassaRequest(pending),
                idempotenceKey(pending.publicId()), pending.yookassaCredentials());
        return new CreatedPayment(payment.getId(), payment.getConfirmation().getConfirmationUrl());
    }

    @Override
    public OrderStatusDTO orderStatus(String rawPublicId) {
        String publicId = rawPublicId == null ? "" : rawPublicId.trim().toUpperCase(Locale.ROOT);
        if (!PUBLIC_ID_PATTERN.matcher(publicId).matches()) {
            throw orderNotFound();
        }
        Order order = loadForStatus(publicId);
        Instant now = clock.instant();
        if (shouldAskBank(order, now)) {
            try {
                // shouldAskBank — дешёвый фильтр по снимку, claimBankCheck — атомарная отметка в БД: из пачки
                // одновременных запросов в банк идёт только один, остальные отдают текущий статус
                if (orderPaymentService.claimBankCheck(order.getId(), now, STATUS_RECHECK_AFTER)) {
                    orderPaymentService.checkWithBank(order, now);
                    order = loadForStatus(publicId);
                }
            } catch (RuntimeException e) {
                // Страница успеха опрашивает дальше — отдаём текущий статус, а не 500
                log.warn("Не удалось сверить оплату заказа {} с банком: {}", publicId, e.getMessage());
            }
        }
        return toStatusDTO(order);
    }

    /** Ждёт оплаты, платёж создан и банк не спрашивали последние 5 секунд. */
    static boolean shouldAskBank(Order order, Instant now) {
        return order.getPaymentStatus() == PaymentStatus.AWAITING_PAYMENT
                && order.getPaymentId() != null
                && (order.getPaymentCheckedAt() == null
                || order.getPaymentCheckedAt().isBefore(now.minus(STATUS_RECHECK_AFTER)));
    }

    private Order loadForStatus(String publicId) {
        return paymentOrderRepository.findWithShopAndItemsByPublicId(publicId)
                .orElseThrow(CheckoutServiceImpl::orderNotFound);
    }

    private PendingPayment createAwaitingOrder(String slug, CheckoutRequestDTO request) {
        Shop shop = findShop(slug);
        if (!ShopReadiness.acceptsOrders(shop)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, NOT_ACCEPTING_ORDERS);
        }
        if (!Boolean.TRUE.equals(request.getAcceptTerms())) {
            throw badRequest("Нужно согласиться с офертой и политикой конфиденциальности");
        }
        String returnUrl = validateReturnUrl(request.getReturnUrl(), allowedReturnHosts);
        String fullName = requireText(request.getFullName(), "Укажите ФИО");
        String email = requireText(request.getEmail(), "Укажите почту");
        // acceptsOrders выше гарантирует, что провайдер есть и подключён
        PaymentProvider provider = Objects.requireNonNull(ShopPayment.activeProvider(shop), "Нет провайдера оплаты");
        boolean receiptEnabled = Boolean.TRUE.equals(provider == PaymentProvider.YOOKASSA
                ? shop.getYookassaReceiptEnabled() : shop.getTinkoffReceiptEnabled());
        if (provider == PaymentProvider.TINKOFF && receiptEnabled && email.length() > RECEIPT_EMAIL_MAX_LENGTH) {
            // Почта уходит в Receipt.Email; длиннее лимита Т-Банк отклонил бы Init уже после создания заказа
            throw badRequest("Почта для чека — не длиннее " + RECEIPT_EMAIL_MAX_LENGTH + " символов");
        }
        String pvzCode = requireText(request.getPvzCode(), "Выберите пункт выдачи СДЭК");
        String phone = normalizePhone(request.getPhone());

        List<PricedItem> priced = priceItems(shop, request.getItems());
        long totalKopecks;
        try {
            totalKopecks = priced.stream().mapToLong(PricedItem::totalKopecks).reduce(0L, Math::addExact);
        } catch (ArithmeticException e) {
            throw badRequest("Слишком большая сумма заказа");
        }
        if (totalKopecks < MoneyUtil.MIN_PAYABLE_KOPECKS) {
            throw badRequest("Сумма заказа меньше минимальной для оплаты");
        }
        TinkoffCredentials tinkoffCredentials = provider == PaymentProvider.TINKOFF ? credentials(shop,
                () -> TinkoffCredentials.of(shop, secretCipher)) : null;
        YookassaCredentials yookassaCredentials = provider == PaymentProvider.YOOKASSA ? credentials(shop,
                () -> YookassaCredentials.of(shop, secretCipher)) : null;

        Instant now = clock.instant();
        Order order = new Order();
        order.setShop(shop);
        order.setPublicId(PublicIdGenerator.generateUnique(orderRepository::existsByPublicId));
        order.setPaymentStatus(PaymentStatus.AWAITING_PAYMENT);
        order.setPaymentProvider(provider);
        order.setFulfillmentStatus(FulfillmentStatus.NEW);
        order.setCustomerName(fullName);
        order.setCustomerPhone(phone);
        order.setCustomerEmail(email);
        order.setPvzCode(pvzCode);
        order.setPvzCity(blankToNull(request.getPvzCity()));
        order.setPvzAddress(blankToNull(request.getPvzAddress()));
        order.setComment(blankToNull(request.getComment()));
        order.setMarketingConsent(Boolean.TRUE.equals(request.getMarketingConsent()));
        order.setItemsTotalKopecks(totalKopecks);
        order.setPaidNotified(false);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        for (PricedItem item : priced) {
            order.getItems().add(OrderItem.builder()
                    .order(order)
                    .productId(item.productId())
                    .variantId(item.variantId())
                    .productName(item.name())
                    .unitPriceKopecks(item.unitKopecks())
                    .quantity(item.quantity())
                    .build());
        }
        Order saved = orderRepository.save(order);

        boolean yookassa = provider == PaymentProvider.YOOKASSA;
        return new PendingPayment(saved.getId(), saved.getPublicId(), totalKopecks, shop.getSlug(), shop.getName(),
                provider, receiptEnabled,
                yookassa ? shop.getYookassaTaxation() : shop.getTinkoffTaxation(),
                yookassa ? shop.getYookassaTax() : shop.getTinkoffTax(),
                email, phone, returnUrl, priced, tinkoffCredentials, yookassaCredentials);
    }

    /** Магазин по адресу витрины вне зависимости от активности: выключенный отвечает 409, а не 404. */
    private Shop findShop(String slug) {
        if (slug == null || slug.isBlank()) {
            throw shopNotFound();
        }
        return shopRepository.findBySlug(slug.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(CheckoutServiceImpl::shopNotFound);
    }

    private List<PricedItem> priceItems(Shop shop, List<CheckoutItemDTO> items) {
        if (items == null || items.isEmpty()) {
            throw badRequest("Корзина пуста");
        }
        if (items.size() > CheckoutRequestDTO.MAX_ITEMS) {
            throw badRequest("Слишком много позиций в корзине");
        }
        List<PricedItem> priced = new ArrayList<>(items.size());
        for (CheckoutItemDTO cartItem : items) {
            if (cartItem == null || cartItem.getProductId() == null) {
                throw badRequest("Не указан товар");
            }
            // Только товары этого магазина: чужой id выглядит как «нет такого товара»
            Product product = productRepository.findByIdAndShopId(cartItem.getProductId(), shop.getId())
                    .orElseThrow(() -> badRequest("Товар не найден: " + cartItem.getProductId()));
            if (!Boolean.TRUE.equals(product.getActive())) {
                throw badRequest("Товар недоступен для покупки: " + product.getName());
            }
            int quantity = cartItem.getQuantity() == null ? 0 : cartItem.getQuantity();
            if (quantity < 1 || quantity > MAX_QUANTITY) {
                throw badRequest("Некорректное количество для товара " + product.getName()
                        + ": от 1 до " + MAX_QUANTITY);
            }
            ProductVariant variant = resolveVariant(product, cartItem.getVariantId());
            long unitKopecks = variant != null ? variant.getPriceKopecks() : product.getPriceKopecks();
            priced.add(new PricedItem(product.getId(), variant != null ? variant.getId() : null,
                    displayName(product, variant), quantity, unitKopecks));
        }
        return priced;
    }

    private ProductVariant resolveVariant(Product product, UUID variantId) {
        boolean hasVariants = product.getVariants() != null && !product.getVariants().isEmpty();
        if (variantId == null) {
            if (hasVariants) {
                throw badRequest("Для товара нужно выбрать вариант: " + product.getName());
            }
            return null;
        }
        if (!hasVariants) {
            throw badRequest("Вариант товара не найден: " + product.getName());
        }
        return product.getVariants().stream()
                .filter(v -> variantId.equals(v.getId()))
                .findFirst()
                .orElseThrow(() -> badRequest("Вариант товара не найден: " + product.getName()));
    }

    /** Имя позиции в заказе, чеке и аналитике: вариант — часть названия («Футболка M»). */
    private static String displayName(Product product, ProductVariant variant) {
        return variant == null ? product.getName() : product.getName() + " " + variant.getLabel();
    }

    private static <T> T credentials(Shop shop, Supplier<T> decrypt) {
        try {
            return decrypt.get();
        } catch (IllegalStateException e) {
            // Шифртекст не расшифровался (сменили PAYMENT_SECRETS_KEY?) — заказ не создаём, чинить нам, а не покупателю
            log.error("Не удалось получить ключи оплаты магазина {}", shop.getSlug(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Не удалось создать платёж: ошибка настроек оплаты магазина");
        }
    }

    private TinkoffInitRequest buildInitRequest(PendingPayment pending) {
        String returnUrl = appendParam(pending.returnUrl(), "order", pending.publicId());
        return TinkoffInitRequest.builder()
                .amount(pending.totalKopecks())
                .orderId(pending.publicId())
                .description(truncate("Заказ " + pending.publicId() + " в магазине " + pending.shopName(),
                        DESCRIPTION_MAX_LENGTH))
                .payType(PAY_TYPE_SINGLE_STAGE)
                .notificationURL(notificationUrl)
                .successURL(appendParam(returnUrl, "status", "success"))
                .failURL(appendParam(returnUrl, "status", "fail"))
                .redirectDueDate(redirectDueDate(clock.instant().plus(PAYMENT_LINK_TTL)))
                .receipt(pending.receiptEnabled() ? buildReceipt(pending) : null)
                .build();
    }

    /** Чек 54-ФЗ — только если мерчант включил его в настройках оплаты; налоги — из настроек магазина. */
    private static TinkoffReceipt buildReceipt(PendingPayment pending) {
        List<TinkoffReceiptItem> items = pending.items().stream()
                .map(i -> TinkoffReceiptItem.builder()
                        .name(truncate(i.name(), ITEM_NAME_MAX_LENGTH))
                        .price(i.unitKopecks())
                        .quantity(i.quantity())
                        .amount(i.totalKopecks())
                        .tax(pending.tax())
                        .paymentMethod(PAYMENT_METHOD)
                        .paymentObject(PAYMENT_OBJECT)
                        .build())
                .collect(Collectors.toList());
        return TinkoffReceipt.builder()
                .email(pending.email())
                .phone(pending.phone())
                .taxation(pending.taxation())
                .items(items)
                .build();
    }

    /**
     * Платёж ЮKassa: одностадийный, редирект на её страницу и обратно на returnUrl. Успех/неуспех ЮKassa
     * в адрес не дописывает — страница успеха узнаёт статус у нас по номеру заказа.
     */
    private YookassaCreatePaymentRequest buildYookassaRequest(PendingPayment pending) {
        return YookassaCreatePaymentRequest.builder()
                .amount(YookassaAmount.rub(pending.totalKopecks()))
                .capture(true)
                .confirmation(new YookassaCreatePaymentRequest.Confirmation(YOOKASSA_CONFIRMATION_REDIRECT,
                        appendParam(pending.returnUrl(), "order", pending.publicId())))
                .description(truncate("Заказ " + pending.publicId() + " в магазине " + pending.shopName(),
                        YookassaClient.DESCRIPTION_MAX_LENGTH))
                .metadata(Map.of(OrderPaymentService.METADATA_ORDER_ID, pending.publicId()))
                .receipt(pending.receiptEnabled() ? buildYookassaReceipt(pending) : null)
                .build();
    }

    private static YookassaReceipt buildYookassaReceipt(PendingPayment pending) {
        Integer vatCode = YOOKASSA_VAT_CODES.get(pending.tax());
        List<YookassaReceipt.Item> items = pending.items().stream()
                .map(i -> YookassaReceipt.Item.builder()
                        .description(truncate(i.name(), YOOKASSA_ITEM_NAME_MAX_LENGTH))
                        .quantity(BigDecimal.valueOf(i.quantity()))
                        .amount(YookassaAmount.rub(i.unitKopecks()))
                        .vatCode(vatCode)
                        .paymentMode(PAYMENT_METHOD)
                        .paymentSubject(PAYMENT_OBJECT)
                        .build())
                .collect(Collectors.toList());
        // ЮKassa ждёт телефон в E.164 без «+»
        String phone = pending.phone() == null ? null : pending.phone().replaceFirst("^\\+", "");
        return YookassaReceipt.builder()
                .customer(new YookassaReceipt.Customer(pending.email(), phone))
                .items(items)
                .taxSystemCode(YOOKASSA_TAX_SYSTEM_CODES.get(pending.taxation()))
                .build();
    }

    /** Один ключ на заказ: повтор создания того же платежа ЮKassa не задвоит, а вернёт уже созданный. */
    static String idempotenceKey(String publicId) {
        return UUID.nameUUIDFromBytes(("marketplace-order-" + publicId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static OrderStatusDTO toStatusDTO(Order order) {
        List<OrderStatusItemDTO> items = order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(i -> new OrderStatusItemDTO(i.getProductName(), i.getQuantity(), i.getUnitPriceKopecks()))
                .toList();
        return new OrderStatusDTO(order.getPublicId(), order.getShop().getSlug(), order.getShop().getName(),
                order.getPaymentStatus(), order.getItemsTotalKopecks(), items);
    }

    /**
     * returnUrl — только http(s) на хост из PAYMENT_ALLOWED_RETURN_HOSTS: иначе витрину можно было бы
     * использовать как открытый редирект после оплаты. Без userinfo и #fragment — к адресу дописываются параметры.
     */
    static String validateReturnUrl(String raw, Set<String> allowedHosts) {
        if (raw == null || raw.isBlank()) {
            throw badRequest(BAD_RETURN_URL);
        }
        String url = raw.trim();
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw badRequest(BAD_RETURN_URL);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw badRequest(BAD_RETURN_URL);
        }
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw badRequest(BAD_RETURN_URL);
        }
        return url;
    }

    static Set<String> parseHosts(String hosts) {
        if (hosts == null) {
            return Set.of();
        }
        return Arrays.stream(hosts.split(","))
                .map(String::trim)
                .filter(h -> !h.isEmpty())
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Телефон в виде +7…: так его ждёт чек Т-Банка и так его удобно набрать мерчанту. */
    private static String normalizePhone(String phone) {
        String e164 = PhoneUtil.toE164(phone);
        if (e164 == null) {
            throw badRequest("Некорректный номер телефона");
        }
        return "+" + e164;
    }

    /** RedirectDueDate в формате Т-Банка, например {@code 2026-09-03T12:28:00+03:00}. */
    static String redirectDueDate(Instant dueAt) {
        return REDIRECT_DUE_DATE.format(dueAt.atZone(MSK));
    }

    static String appendParam(String url, String name, String value) {
        return url + (url.contains("?") ? "&" : "?") + name + "=" + value;
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        // Не разрезаем эмодзи (суррогатную пару) пополам — банк отверг бы битую строку
        int end = Character.isHighSurrogate(value.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return value.substring(0, end);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw badRequest(message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException shopNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Магазин не найден");
    }

    private static ResponseStatusException orderNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
    }
}
