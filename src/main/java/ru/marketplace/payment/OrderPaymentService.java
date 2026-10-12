package ru.marketplace.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.marketplace.common.SecretCipher;
import ru.marketplace.model.Order;
import ru.marketplace.model.PaymentProvider;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.model.Shop;
import ru.marketplace.order.OrderNotificationService;
import ru.marketplace.payment.dto.tinkoff.TinkoffGetStateResponse;
import ru.marketplace.payment.dto.tinkoff.TinkoffNotification;
import ru.marketplace.payment.dto.yookassa.YookassaPayment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Статус оплаты заказа по данным провайдера (Т-Банк или ЮKassa) — одна точка для вебхуков, поллера
 * и страницы успеха (в anyforms это делали PaymentConfirmServiceImpl.applyStatus и PaymentStatusConverter).
 * <p>
 * Переходы идемпотентны: повторный CONFIRMED ничего не меняет и писем второй раз не шлёт,
 * PAID не откатывается в FAILED запоздавшим REJECTED/DEADLINE_EXPIRED, CANCELED (отмена/возврат) —
 * конечный. FAILED → PAID разрешён: деньги списаны — значит, заказ оплачен, даже если мы
 * успели счесть его брошенным.
 */
@Service
@Slf4j
public class OrderPaymentService {

    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    /**
     * Статусы возврата: в их нотификациях Amount может быть уже не исходной суммой заказа
     * (частичный/полный возврат), поэтому сверку суммы для них не делаем. Подпись, терминал и
     * PaymentId всё равно проверены, а возврат только снимает оплату — выгоды от подделки нет.
     */
    private static final Set<String> REFUND_STATUSES = Set.of("REVERSED", "REFUNDED", "PARTIAL_REFUNDED");

    private final PaymentOrderRepository paymentOrderRepository;
    private final OrderNotificationService orderNotificationService;
    private final TinkoffClient tinkoffClient;
    private final TinkoffTokenService tinkoffTokenService;
    private final YookassaClient yookassaClient;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate inNewTransaction;

    public OrderPaymentService(PaymentOrderRepository paymentOrderRepository,
                               OrderNotificationService orderNotificationService,
                               TinkoffClient tinkoffClient,
                               TinkoffTokenService tinkoffTokenService,
                               YookassaClient yookassaClient,
                               SecretCipher secretCipher,
                               ObjectMapper objectMapper,
                               Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.orderNotificationService = orderNotificationService;
        this.tinkoffClient = tinkoffClient;
        this.tinkoffTokenService = tinkoffTokenService;
        this.yookassaClient = yookassaClient;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.clock = clock;
        // Своя транзакция, которая коммитится до возврата из execute(): письма об оплате уходят строго
        // после коммита PAID. afterCommit-синхронизация не подошла бы — @Transactional-код, вызванный
        // из afterCommit, участвует в уже закрытой транзакции и молча не коммитится (paidNotified потерялся бы).
        this.inNewTransaction = new TransactionTemplate(transactionManager);
        this.inNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Статус Т-Банка → статус оплаты заказа; null — промежуточный (NEW, FORM_SHOWED, AUTHORIZED…)
     * или неизвестный статус: заказ не трогаем.
     */
    public static PaymentStatus toPaymentStatus(String tinkoffStatus) {
        if (tinkoffStatus == null) {
            return null;
        }
        return switch (tinkoffStatus.trim().toUpperCase(Locale.ROOT)) {
            case "CONFIRMED" -> PaymentStatus.PAID;
            // Неуспешная оплата: деньги не списывались. ATTEMPTS_EXPIRED — конечный статус Т-Банка (покупатель
            // исчерпал попытки открыть форму): без него заказ сутки висел бы AWAITING_PAYMENT под поллером
            case "REJECTED", "DEADLINE_EXPIRED", "AUTH_FAIL", "ATTEMPTS_EXPIRED" -> PaymentStatus.FAILED;
            // Отмена и возвраты: деньги вернулись покупателю или не списывались по решению мерчанта
            case "CANCELED", "REVERSED", "REFUNDED", "PARTIAL_REFUNDED" -> PaymentStatus.CANCELED;
            default -> null;
        };
    }

    /** Можно ли перевести заказ из from в to (to == from — нечего делать). */
    static boolean canMove(PaymentStatus from, PaymentStatus to) {
        if (from == to) {
            return false;
        }
        return switch (from) {
            case AWAITING_PAYMENT -> true;
            // Запоздавший CONFIRMED после REJECTED/24 часов ожидания: деньги у мерчанта — заказ оплачен
            case FAILED -> to == PaymentStatus.PAID || to == PaymentStatus.CANCELED;
            // Оплаченный заказ только отменяется/возвращается, но не «проваливается»
            case PAID -> to == PaymentStatus.CANCELED;
            // Отмена и возврат — конечные: запоздавшие CONFIRMED/REJECTED их не перебивают
            case CANCELED -> false;
        };
    }

    /**
     * Применить статус Т-Банка к заказу (по его id, под блокировкой строки, в своей транзакции).
     * Если заказ этим вызовом стал PAID — после коммита зовём {@link OrderNotificationService#onOrderPaid}.
     *
     * @return статус оплаты заказа после применения
     */
    public PaymentStatus applyTinkoffStatus(Order order, String tinkoffStatus) {
        return apply(order.getId(), toPaymentStatus(tinkoffStatus), "Т-Банк " + tinkoffStatus, null);
    }

    /**
     * Застолбить проверку заказа у банка (см. {@link PaymentOrderRepository#claimPaymentCheck}): true — можно
     * звать {@link #checkWithBank}, false — банк по этому заказу спрашивали меньше minInterval назад
     * (или спрашивают прямо сейчас), заказ уже не ждёт оплаты или платёж не создан.
     */
    public boolean claimBankCheck(Long orderId, Instant now, Duration minInterval) {
        return paymentOrderRepository.claimPaymentCheck(orderId, now, now.minus(minInterval)) == 1;
    }

    /**
     * Спросить у провайдера, через которого создан платёж заказа, его статус (ключами магазина заказа)
     * и применить его, отметив paymentCheckedAt. У order должен быть загружен shop.
     * Ошибки провайдера не бросаются: логируем, отмечаем проверку (чтобы его не долбить) и отдаём null.
     *
     * @return статус оплаты после проверки или null, если провайдер ответить не смог
     */
    public PaymentStatus checkWithBank(Order order, Instant now) {
        PaymentProvider provider = providerOf(order);
        PaymentStatus target;
        String source;
        try {
            if (provider == PaymentProvider.YOOKASSA) {
                YookassaPayment payment = yookassaClient.getPayment(order.getPaymentId(),
                        YookassaCredentials.of(order.getShop(), secretCipher));
                target = YookassaClient.toPaymentStatus(payment);
                source = "ЮKassa " + payment.getStatus();
            } else {
                TinkoffGetStateResponse state = tinkoffClient.getState(order.getPaymentId(),
                        TinkoffCredentials.of(order.getShop(), secretCipher));
                target = toPaymentStatus(state.getStatus());
                source = "Т-Банк " + state.getStatus();
            }
        } catch (RuntimeException e) {
            log.warn("Статус оплаты заказа {} у {} (платёж {}) не получен: {}",
                    order.getPublicId(), provider, order.getPaymentId(), e.getMessage());
            paymentOrderRepository.markPaymentChecked(order.getId(), now);
            return null;
        }
        return apply(order.getId(), target, source, now);
    }

    /** Заказы до ЮKassa провайдера не хранили — их платежи в Т-Банке. */
    static PaymentProvider providerOf(Order order) {
        return order.getPaymentProvider() == null ? PaymentProvider.TINKOFF : order.getPaymentProvider();
    }

    /**
     * Нотификация Т-Банка (сырое тело запроса). true — принята (банку отвечаем OK), false — отклонена
     * (400 fail, банк повторит). Проверки: заказ по OrderId, TerminalKey == терминал магазина заказа,
     * Token паролем магазина, PaymentId == платёж заказа, Amount == сумма заказа (кроме возвратов).
     */
    public boolean acceptTinkoffNotification(String rawBody) {
        try {
            Map<String, Object> root = objectMapper.readValue(rawBody, JSON_OBJECT);
            TinkoffNotification notification = objectMapper.convertValue(root, TinkoffNotification.class);

            String publicId = notification.getOrderId();
            if (publicId == null || publicId.isBlank()) {
                log.warn("Нотификация Т-Банка без OrderId (платёж {}, статус {})",
                        notification.getPaymentId(), notification.getStatus());
                return false;
            }
            Optional<Order> found = paymentOrderRepository.findWithShopByPublicId(publicId.trim());
            if (found.isEmpty()) {
                log.warn("Нотификация Т-Банка по неизвестному заказу {} (платёж {}, статус {})",
                        publicId, notification.getPaymentId(), notification.getStatus());
                return false;
            }
            Order order = found.get();
            Shop shop = order.getShop();

            if (shop.getTinkoffTerminalKey() == null || !shop.getTinkoffTerminalKey().equals(notification.getTerminalKey())) {
                log.warn("Нотификация Т-Банка по заказу {}: TerminalKey {} не совпадает с терминалом магазина {}",
                        publicId, notification.getTerminalKey(), shop.getSlug());
                return false;
            }
            if (!tinkoffTokenService.verify(root, secretCipher.decrypt(shop.getTinkoffPasswordEnc()))) {
                log.warn("Нотификация Т-Банка по заказу {} с невалидным Token (платёж {}, статус {})",
                        publicId, notification.getPaymentId(), notification.getStatus());
                return false;
            }
            if (order.getPaymentId() != null && !order.getPaymentId().equals(notification.getPaymentId())) {
                log.warn("Нотификация Т-Банка по заказу {}: платёж {}, а у заказа {}",
                        publicId, notification.getPaymentId(), order.getPaymentId());
                return false;
            }
            if (!isRefund(notification.getStatus())
                    && !Objects.equals(notification.getAmount(), order.getItemsTotalKopecks())) {
                log.warn("Нотификация Т-Банка по заказу {}: сумма {} не совпадает с суммой заказа {}",
                        publicId, notification.getAmount(), order.getItemsTotalKopecks());
                return false;
            }

            PaymentStatus result = applyTinkoffStatus(order, notification.getStatus());
            log.info("Нотификация Т-Банка по заказу {}: статус банка {}, оплата заказа {}",
                    publicId, notification.getStatus(), result);
            return true;
        } catch (Exception e) {
            log.error("Не удалось обработать нотификацию Т-Банка", e);
            return false;
        }
    }

    /**
     * HTTP-уведомление ЮKassa (сырое тело). У ЮKassa уведомления не подписаны, поэтому телу не верим:
     * берём из него только id платежа, находим заказ, спрашиваем платёж у ЮKassa ключами магазина
     * заказа и сверяем order_id и сумму уже в ответе API — подделать его нельзя.
     * <p>
     * URL уведомлений мерчант задаёт в кабинете ЮKassa на весь свой магазин — сюда могут прийти и платежи
     * с других его сайтов. Такие (платёж не наш) принимаем молча: иначе ЮKassa сутки повторяла бы их.
     *
     * @return true — обработано или не наше (отвечаем 200); false — не смогли проверить, пусть ЮKassa повторит
     */
    public boolean acceptYookassaNotification(String rawBody) {
        String paymentId;
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String event = root.path("event").asText("");
            JsonNode object = root.path("object");
            // refund.* присылает объект возврата — платёж в его payment_id
            paymentId = event.startsWith("refund.") ? object.path("payment_id").asText(null) : object.path("id").asText(null);
            if (paymentId == null || paymentId.isBlank()) {
                log.warn("Уведомление ЮKassa {} без id платежа", event);
                return true;
            }
            log.info("Уведомление ЮKassa {} по платежу {}", event, paymentId);
        } catch (Exception e) {
            log.warn("Уведомление ЮKassa не разобрано: {}", e.getMessage());
            return true;
        }

        Optional<Order> found = paymentOrderRepository.findWithShopByPaymentId(paymentId.trim());
        if (found.isEmpty() || providerOf(found.get()) != PaymentProvider.YOOKASSA) {
            log.info("Уведомление ЮKassa по чужому платежу {} — пропускаем", paymentId);
            return true;
        }
        Order order = found.get();

        YookassaPayment payment;
        try {
            payment = yookassaClient.getPayment(paymentId, YookassaCredentials.of(order.getShop(), secretCipher));
        } catch (RuntimeException e) {
            log.warn("Уведомление ЮKassa по заказу {}: платёж {} не получен: {}",
                    order.getPublicId(), paymentId, e.getMessage());
            return false;
        }
        String orderIdInPayment = payment.getMetadata() == null ? null : payment.getMetadata().get(METADATA_ORDER_ID);
        if (!order.getPublicId().equals(orderIdInPayment) || !paymentId.equals(payment.getId())) {
            log.warn("Платёж ЮKassa {} не от заказа {} (metadata.order_id {})",
                    paymentId, order.getPublicId(), orderIdInPayment);
            return true;
        }
        Long amount = payment.getAmount() == null ? null : payment.getAmount().toKopecks();
        if (!Objects.equals(amount, order.getItemsTotalKopecks())) {
            log.warn("Платёж ЮKassa {} заказа {}: сумма {} не совпадает с суммой заказа {}",
                    paymentId, order.getPublicId(), amount, order.getItemsTotalKopecks());
            return true;
        }
        PaymentStatus result = apply(order.getId(), YookassaClient.toPaymentStatus(payment),
                "ЮKassa " + payment.getStatus(), null);
        log.info("Уведомление ЮKassa по заказу {}: статус {}, оплата заказа {}",
                order.getPublicId(), payment.getStatus(), result);
        return true;
    }

    /** Платёж не создался (Init отклонён/недоступен): заказ в FAILED, если он всё ещё ждёт оплаты. */
    public void markInitFailed(Long orderId) {
        paymentOrderRepository.failIfAwaiting(orderId, clock.instant());
    }

    /**
     * Заказы, которые ждут оплаты дольше createdBefore, — FAILED. Ссылка на оплату живёт 15 минут,
     * так что через сутки оплатить по ней уже нельзя, а поллер к этому времени спросил банк не раз.
     *
     * @return сколько заказов переведено
     */
    public int failStaleAwaiting(Instant createdBefore) {
        return paymentOrderRepository.failAwaitingCreatedBefore(createdBefore, clock.instant());
    }

    /** Ключ metadata платежа ЮKassa с номером заказа (у ЮKassa нет своего OrderId). */
    static final String METADATA_ORDER_ID = "order_id";

    /** source — статус провайдера для логов («Т-Банк CONFIRMED», «ЮKassa succeeded»). */
    private PaymentStatus apply(Long orderId, PaymentStatus target, String source, Instant checkedAt) {
        Transition transition = inNewTransaction.execute(tx -> {
            Order order = paymentOrderRepository.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new IllegalStateException("Заказ не найден: " + orderId));
            PaymentStatus before = order.getPaymentStatus();
            if (checkedAt != null) {
                order.setPaymentCheckedAt(checkedAt);
            }
            if (target != null && canMove(before, target)) {
                order.setPaymentStatus(target);
                if (target == PaymentStatus.PAID) {
                    order.setPaidAt(clock.instant());
                }
                log.info("Заказ {}: оплата {} -> {} ({})", order.getPublicId(), before, target, source);
            } else if (target != null && target != before) {
                log.warn("Заказ {}: игнорируем переход оплаты {} -> {} ({})",
                        order.getPublicId(), before, target, source);
            }
            // Управляемая сущность под блокировкой: изменения сбросит коммит, save не нужен
            return new Transition(before, order.getPaymentStatus());
        });
        Objects.requireNonNull(transition, "Транзакция смены статуса не вернула результат");

        // Транзакция уже закоммичена — письма не уйдут по заказу, PAID которого откатился
        if (transition.before() != PaymentStatus.PAID && transition.after() == PaymentStatus.PAID) {
            notifyPaid(orderId);
        }
        return transition.after();
    }

    private void notifyPaid(Long orderId) {
        try {
            orderNotificationService.onOrderPaid(orderId);
        } catch (RuntimeException e) {
            // Оплата уже зафиксирована; банк не должен получить fail и слать нотификацию заново из-за писем
            log.error("Письма об оплате заказа {} не отправлены", orderId, e);
        }
    }

    private static boolean isRefund(String tinkoffStatus) {
        return tinkoffStatus != null && REFUND_STATUSES.contains(tinkoffStatus.trim().toUpperCase(Locale.ROOT));
    }

    private record Transition(PaymentStatus before, PaymentStatus after) {
    }
}
