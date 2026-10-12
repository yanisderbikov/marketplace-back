package ru.marketplace.order;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.marketplace.config.OrderEmailExecutorConfig;
import ru.marketplace.email.EmailService;
import ru.marketplace.model.Order;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.model.Shop;
import ru.marketplace.model.User;
import ru.marketplace.repository.OrderRepository;
import ru.marketplace.repository.UserRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Письма об оплаченном заказе: мерчанту — «новый заказ», покупателю — «заказ оплачен».
 * Идемпотентно по paidNotified: повторный CONFIRMED от Т-Банка, поллер и страница успеха
 * не шлют писем второй раз. Ошибка отправки только логируется — оплата уже прошла,
 * и сбой почтового сервиса не должен ронять вебхук (Т-Банк начал бы слать его заново).
 */
@Slf4j
@Service
class OrderNotificationServiceImpl implements OrderNotificationService {

    private final OrderRowLockRepository orderRowLockRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final TransactionTemplate inNewTransaction;
    /** Пул отправки писем: вызывающий (вебхук, страница успеха) не ждёт NotiSend */
    private final TaskExecutor emailExecutor;
    /** Origin фронта без слэша на конце — ссылки в письмах */
    private final String frontUrl;

    OrderNotificationServiceImpl(OrderRowLockRepository orderRowLockRepository,
                                 OrderRepository orderRepository,
                                 UserRepository userRepository,
                                 EmailService emailService,
                                 PlatformTransactionManager transactionManager,
                                 @Qualifier(OrderEmailExecutorConfig.ORDER_EMAIL_EXECUTOR) TaskExecutor emailExecutor,
                                 @Value("${front.url}") String frontUrl) {
        if (frontUrl == null || frontUrl.isBlank()) {
            throw new IllegalStateException("FRONT_URL не задан: без него в письмах о заказе битые ссылки");
        }
        this.orderRowLockRepository = orderRowLockRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        // REQUIRES_NEW, а не обычная транзакция: модуль оплаты может позвать метод из afterCommit-колбэка —
        // там REQUIRED присоединился бы к уже завершённой транзакции, и paidNotified=true молча не записался бы
        this.inNewTransaction = new TransactionTemplate(transactionManager);
        this.inNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.emailExecutor = emailExecutor;
        this.frontUrl = frontUrl.trim().replaceAll("/+$", "");
    }

    /** Готовое письмо: собрано в транзакции (нужны ленивые позиции и магазин), отправляется уже после коммита. */
    private record PendingEmail(String whom, String publicId, String to, String subject, String html) {
    }

    /**
     * Сначала «застолбить» письма, потом слать: в короткой транзакции под FOR UPDATE проверяем флаг,
     * собираем письма и ставим paidNotified=true; NotiSend зовём уже после коммита — и в отдельном пуле:
     * метод возвращается сразу после коммита флага, и вебхук Т-Банка отвечает «OK» в его 10 секунд,
     * а страница успеха не висит, пока уходят письма (до трёх по 20 с таймаута NotiSend).
     * Параллельный вызов (вебхук + поллер) ждёт только эту короткую транзакцию и видит флаг —
     * писем ровно один комплект. Раньше отправка шла под блокировкой: при зависшем NotiSend
     * (20 с на письмо, до трёх писем) строка заказа и соединение с БД держались до минуты,
     * и десяток оплат подряд выбирал весь пул соединений.
     * Цена: если процесс упадёт между коммитом и отправкой, письма не уйдут — так же, как при ошибке
     * NotiSend (флаг ставится и тогда: повторять некому, а дубли при каждом CONFIRMED хуже потери письма).
     */
    @Override
    public void onOrderPaid(Long orderId) {
        if (orderId == null) {
            return;
        }
        // Застолбить — синхронно: это гарантия «писем ровно один комплект», она должна закоммититься до возврата
        List<PendingEmail> emails = inNewTransaction.execute(tx -> claim(orderId));
        if (emails == null || emails.isEmpty()) {
            return;
        }
        try {
            // Одной задачей на заказ: письма уходят по очереди, мерчанту — первым
            emailExecutor.execute(() -> emails.forEach(this::send));
        } catch (TaskRejectedException e) {
            // Очередь переполнена или приложение останавливается: как и при ошибке NotiSend — только лог,
            // флаг уже стоит (дубли при каждом CONFIRMED хуже потери письма)
            log.error("Заказ {}: очередь писем недоступна — письма об оплате не отправлены: {}",
                    emails.get(0).publicId(), e.getMessage());
        }
    }

    /** Под блокировкой строки: пусто — слать нечего (нет заказа, не PAID или письма уже ушли). */
    private List<PendingEmail> claim(Long orderId) {
        Optional<Order> found = orderRowLockRepository.findByIdForUpdate(orderId);
        if (found.isEmpty()) {
            log.warn("Письма об оплате: заказ id={} не найден", orderId);
            return Collections.emptyList();
        }
        Order order = found.get();
        if (Boolean.TRUE.equals(order.getPaidNotified())) {
            return Collections.emptyList();
        }
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            // Вызов до коммита PAID или по ошибке — «заказ оплачен» неоплаченному не шлём, флаг не трогаем
            log.warn("Письма об оплате: заказ {} в статусе {}, а не PAID — пропускаем",
                    order.getPublicId(), order.getPaymentStatus());
            return Collections.emptyList();
        }
        Shop shop = order.getShop();
        List<PendingEmail> emails = new ArrayList<>();

        List<String> merchantRecipients = merchantRecipients(shop);
        if (merchantRecipients.isEmpty()) {
            log.warn("Заказ {} магазина {}: некому отправить письмо о новом заказе — нет почты владельца и контактной",
                    order.getPublicId(), shop.getSlug());
        }
        for (String to : merchantRecipients) {
            prepare("мерчанту", order, to,
                    () -> OrderEmailTemplates.merchantSubject(order),
                    () -> OrderEmailTemplates.merchantNewOrder(order, shop, frontUrl))
                    .ifPresent(emails::add);
        }

        if (order.getCustomerEmail() == null || order.getCustomerEmail().isBlank()) {
            log.warn("Заказ {}: у покупателя нет почты — подтверждение оплаты не отправлено", order.getPublicId());
        } else {
            prepare("покупателю", order, order.getCustomerEmail().trim(),
                    () -> OrderEmailTemplates.buyerSubject(order),
                    () -> OrderEmailTemplates.buyerPaid(order, shop, frontUrl))
                    .ifPresent(emails::add);
        }

        // Флаг ставим до отправки и независимо от её исхода: повторять некому, а дубли при каждом CONFIRMED хуже потери письма
        order.setPaidNotified(true);
        orderRepository.save(order);
        return emails;
    }

    /** Владелец магазина и контактная почта из «Настроек», если она другая (без учёта регистра). */
    private List<String> merchantRecipients(Shop shop) {
        List<String> recipients = new ArrayList<>();
        userRepository.findByShopId(shop.getId())
                .map(User::getEmail)
                .filter(email -> !email.isBlank())
                .map(String::trim)
                .ifPresent(recipients::add);
        String contactEmail = shop.getContactEmail() == null ? null : shop.getContactEmail().trim();
        if (contactEmail != null && !contactEmail.isEmpty()
                && recipients.stream().noneMatch(r -> r.toLowerCase(Locale.ROOT).equals(contactEmail.toLowerCase(Locale.ROOT)))) {
            recipients.add(contactEmail);
        }
        return recipients;
    }

    /** Сборка письма; ошибка шаблона — в лог, остальные письма заказа всё равно уйдут. */
    private Optional<PendingEmail> prepare(String whom, Order order, String to,
                                           Supplier<String> subject, Supplier<String> html) {
        try {
            return Optional.of(new PendingEmail(whom, order.getPublicId(), to, subject.get(), html.get()));
        } catch (RuntimeException e) {
            log.error("Заказ {}: не удалось собрать письмо {}: {}", order.getPublicId(), whom, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Одно письмо; ошибка NotiSend — в лог, наружу не летит. */
    private void send(PendingEmail email) {
        try {
            emailService.sendEmail(email.to(), email.subject(), email.html());
            log.info("Заказ {}: письмо {} отправлено", email.publicId(), email.whom());
        } catch (RuntimeException e) {
            log.error("Заказ {}: не удалось отправить письмо {}: {}", email.publicId(), email.whom(), e.getMessage(), e);
        }
    }
}
