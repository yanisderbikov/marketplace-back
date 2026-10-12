package ru.marketplace.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;
import ru.marketplace.email.EmailService;
import ru.marketplace.model.Order;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.model.Shop;
import ru.marketplace.model.User;
import ru.marketplace.repository.OrderRepository;
import ru.marketplace.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static ru.marketplace.order.OrderTestData.SHOP_ID;
import static ru.marketplace.order.OrderTestData.addItem;
import static ru.marketplace.order.OrderTestData.order;

class OrderNotificationServiceImplTest {

    private static final String FRONT_URL = "https://market.example";
    private static final String OWNER = "owner@example.com";

    private final OrderRowLockRepository lockRepository = mock(OrderRowLockRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    /** TransactionTemplate с моком менеджера: getTransaction → null, commit — no-op; порядок вызовов проверяем */
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final Shop shop = OrderTestData.shop();
    private Order paid;
    private OrderNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        // Синхронный исполнитель: письма уходят прямо в вызове — проверки состава писем остаются простыми
        service = new OrderNotificationServiceImpl(lockRepository, orderRepository, userRepository, emailService,
                transactionManager, new SyncTaskExecutor(), FRONT_URL + "/");
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findByShopId(SHOP_ID)).thenReturn(Optional.of(User.builder().id(7L).email(OWNER).build()));

        shop.setSupportTelegram("candle_support");
        shop.setContactPhone("8 (912) 000-11-22");
        paid = order(42L, "AB12CD", PaymentStatus.PAID, shop);
        addItem(paid, 1L, "Свеча <Лаванда> & мёд", 50_000, 2);
        addItem(paid, 2L, "Подсвечник", 119_050, 1);
        paid.setComment("Позвоните\nперед отправкой");
        when(lockRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(paid));
    }

    @Test
    void sendsMerchantAndBuyerEmailsAndMarksNotified() {
        service.onOrderPaid(42L);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq(OWNER), eq("Новый заказ AB12CD"), html.capture());
        String merchantHtml = html.getValue();
        verify(emailService).sendEmail(eq("anna@example.com"), eq("Заказ AB12CD оплачен"), html.capture());
        String buyerHtml = html.getValue();
        verifyNoMoreInteractions(emailService);

        assertTrue(paid.getPaidNotified());
        verify(orderRepository).save(paid);

        // Мерчанту: позиции (экранированы), итог, контакты покупателя, ПВЗ с кодом, комментарий, ссылка в админку
        assertTrue(merchantHtml.contains("Свеча &lt;Лаванда&gt; &amp; мёд"));
        assertFalse(merchantHtml.contains("<Лаванда>"));
        assertTrue(merchantHtml.contains("×2"));
        assertTrue(merchantHtml.contains("1&nbsp;000&nbsp;₽"), "строка позиции — сумма за все штуки");
        assertTrue(merchantHtml.contains("2&nbsp;190,50&nbsp;₽"), "итого");
        assertTrue(merchantHtml.contains("Анна Петрова"));
        assertTrue(merchantHtml.contains("tel:+79991234567"));
        assertTrue(merchantHtml.contains("mailto:anna@example.com"));
        assertTrue(merchantHtml.contains("Москва, ул. Тверская, 1"));
        assertTrue(merchantHtml.contains("MSK123"));
        assertTrue(merchantHtml.contains("Позвоните<br>перед отправкой"));
        assertTrue(merchantHtml.contains("href=\"https://market.example/admin/orders\""), "слэш на конце FRONT_URL срезан");

        // Покупателю: позиции, итог, ПВЗ и контакты магазина
        assertTrue(buyerHtml.contains("Здравствуйте, Анна Петрова!"));
        assertTrue(buyerHtml.contains("Свечная лавка"));
        assertTrue(buyerHtml.contains("2&nbsp;190,50&nbsp;₽"));
        assertTrue(buyerHtml.contains("Москва, ул. Тверская, 1"));
        assertTrue(buyerHtml.contains("https://t.me/candle_support"));
        assertTrue(buyerHtml.contains("tel:+79120001122"));
        assertTrue(buyerHtml.contains("href=\"https://market.example/shop/candles\""));
        assertFalse(buyerHtml.contains("/admin/orders"), "ссылку в админку покупателю не шлём");
    }

    @Test
    void flagIsCommittedBeforeEmailsGoOut() {
        service.onOrderPaid(42L);

        // Блокировка строки и соединение с БД не держатся, пока NotiSend отвечает (до 20 с на письмо)
        InOrder inOrder = inOrder(lockRepository, orderRepository, transactionManager, emailService);
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(lockRepository).findByIdForUpdate(42L);
        inOrder.verify(orderRepository).save(paid);
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(emailService, times(2)).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void secondCallSendsNothing() {
        service.onOrderPaid(42L);
        clearInvocations(emailService, orderRepository);

        service.onOrderPaid(42L);

        verifyNoInteractions(emailService);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void alreadyNotifiedOrderIsSkipped() {
        paid.setPaidNotified(true);

        service.onOrderPaid(42L);

        verifyNoInteractions(emailService, userRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void contactEmailGetsCopyWhenDifferentFromOwner() {
        shop.setContactEmail("shop@example.com");

        service.onOrderPaid(42L);

        verify(emailService).sendEmail(eq(OWNER), eq("Новый заказ AB12CD"), anyString());
        verify(emailService).sendEmail(eq("shop@example.com"), eq("Новый заказ AB12CD"), anyString());
        verify(emailService).sendEmail(eq("anna@example.com"), eq("Заказ AB12CD оплачен"), anyString());
        verifyNoMoreInteractions(emailService);
    }

    @Test
    void contactEmailSameAsOwnerIgnoringCaseIsNotDuplicated() {
        shop.setContactEmail(" Owner@Example.com ");

        service.onOrderPaid(42L);

        verify(emailService, times(1)).sendEmail(anyString(), eq("Новый заказ AB12CD"), anyString());
    }

    @Test
    void sendFailureIsLoggedNotThrownAndOtherEmailsStillGo() {
        doThrow(new RuntimeException("NotiSend недоступен"))
                .when(emailService).sendEmail(eq(OWNER), anyString(), anyString());

        assertDoesNotThrow(() -> service.onOrderPaid(42L));

        verify(emailService).sendEmail(eq("anna@example.com"), eq("Заказ AB12CD оплачен"), anyString());
        assertTrue(paid.getPaidNotified(), "повторять некому — флаг ставим, чтобы не слать дубли");
        verify(orderRepository).save(paid);
    }

    @Test
    void allSendsFailingStillDoesNotThrow() {
        doThrow(new RuntimeException("boom")).when(emailService).sendEmail(anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> service.onOrderPaid(42L));

        assertTrue(paid.getPaidNotified());
    }

    @Test
    void notPaidOrderSendsNothingAndKeepsFlag() {
        Order awaiting = order(43L, "ZZ99ZZ", PaymentStatus.AWAITING_PAYMENT, shop);
        when(lockRepository.findByIdForUpdate(43L)).thenReturn(Optional.of(awaiting));

        service.onOrderPaid(43L);

        verifyNoInteractions(emailService);
        assertFalse(awaiting.getPaidNotified());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void unknownOrNullOrderIdDoesNotThrow() {
        when(lockRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.onOrderPaid(999L));
        assertDoesNotThrow(() -> service.onOrderPaid(null));

        verifyNoInteractions(emailService);
    }

    @Test
    void merchantEmailStillGoesToContactWhenOwnerMissing() {
        when(userRepository.findByShopId(SHOP_ID)).thenReturn(Optional.empty());
        shop.setContactEmail("shop@example.com");

        service.onOrderPaid(42L);

        verify(emailService).sendEmail(eq("shop@example.com"), eq("Новый заказ AB12CD"), anyString());
        verify(emailService).sendEmail(eq("anna@example.com"), eq("Заказ AB12CD оплачен"), anyString());
        verifyNoMoreInteractions(emailService);
    }

    @Test
    void blankFrontUrlFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new OrderNotificationServiceImpl(lockRepository, orderRepository, userRepository, emailService,
                        transactionManager, new SyncTaskExecutor(), " "));
    }

    @Test
    void emailsGoToExecutorAndCallerReturnsRightAfterClaim() {
        List<Runnable> queued = new ArrayList<>();
        TaskExecutor deferred = queued::add;
        service = new OrderNotificationServiceImpl(lockRepository, orderRepository, userRepository, emailService,
                transactionManager, deferred, FRONT_URL);

        service.onOrderPaid(42L);

        // Вебхук/страница успеха уже свободны: флаг закоммичен, а NotiSend ещё не звали
        assertTrue(paid.getPaidNotified());
        verify(transactionManager).commit(any());
        verifyNoInteractions(emailService);
        assertEquals(1, queued.size(), "одна задача на заказ");

        queued.get(0).run();
        InOrder inOrder = inOrder(emailService);
        inOrder.verify(emailService).sendEmail(eq(OWNER), eq("Новый заказ AB12CD"), anyString());
        inOrder.verify(emailService).sendEmail(eq("anna@example.com"), eq("Заказ AB12CD оплачен"), anyString());
    }

    @Test
    void rejectedExecutorIsLoggedNotThrown() {
        TaskExecutor full = task -> {
            throw new TaskRejectedException("очередь полна");
        };
        service = new OrderNotificationServiceImpl(lockRepository, orderRepository, userRepository, emailService,
                transactionManager, full, FRONT_URL);

        assertDoesNotThrow(() -> service.onOrderPaid(42L));

        assertTrue(paid.getPaidNotified());
        verifyNoInteractions(emailService);
    }

    @Test
    void nothingIsQueuedWhenThereIsNothingToSend() {
        paid.setPaidNotified(true);
        List<Runnable> queued = new ArrayList<>();
        service = new OrderNotificationServiceImpl(lockRepository, orderRepository, userRepository, emailService,
                transactionManager, queued::add, FRONT_URL);

        service.onOrderPaid(42L);

        assertTrue(queued.isEmpty());
    }
}
