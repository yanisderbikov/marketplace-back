package ru.marketplace.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import ru.marketplace.common.SecretCipher;
import ru.marketplace.model.FulfillmentStatus;
import ru.marketplace.model.Order;
import ru.marketplace.model.PaymentProvider;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.model.Shop;
import ru.marketplace.order.OrderNotificationService;
import ru.marketplace.payment.dto.tinkoff.TinkoffGetStateResponse;
import ru.marketplace.payment.dto.yookassa.YookassaAmount;
import ru.marketplace.payment.dto.yookassa.YookassaPayment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Переходы статуса оплаты, письма после коммита и проверки нотификаций Т-Банка. */
class OrderPaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-10-01T08:00:00Z");
    private static final String SECRETS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final long ORDER_ID = 7L;

    private final PaymentOrderRepository repository = mock(PaymentOrderRepository.class);
    private final OrderNotificationService notifications = mock(OrderNotificationService.class);
    private final TinkoffClient tinkoffClient = mock(TinkoffClient.class);
    private final YookassaClient yookassaClient = mock(YookassaClient.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final SecretCipher cipher = new SecretCipher(SECRETS_KEY);
    private final TinkoffTokenService tokenService = new TinkoffTokenService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrderPaymentService service;
    private Order order;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OrderPaymentService(repository, notifications, tinkoffClient, tokenService, yookassaClient, cipher,
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);

        Shop shop = Shop.builder()
                .id(UUID.randomUUID())
                .slug("candles")
                .name("Свечи")
                .active(true)
                .tinkoffTerminalKey(TinkoffTokenServiceTest.DOC_TERMINAL)
                .tinkoffPasswordEnc(cipher.encrypt(TinkoffTokenServiceTest.DOC_PASSWORD))
                .tinkoffReceiptEnabled(false)
                .build();
        order = new Order();
        order.setId(ORDER_ID);
        order.setPublicId("A1B2C3");
        order.setShop(shop);
        order.setPaymentStatus(PaymentStatus.AWAITING_PAYMENT);
        order.setFulfillmentStatus(FulfillmentStatus.NEW);
        order.setItemsTotalKopecks(19200L);
        order.setPaymentId("13660");
        order.setPaidNotified(false);
        when(repository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(repository.findWithShopByPublicId("A1B2C3")).thenReturn(Optional.of(order));
    }

    // ── Маппинг статусов и таблица переходов ──

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "CONFIRMED, PAID", "confirmed, PAID",
            "REJECTED, FAILED", "DEADLINE_EXPIRED, FAILED", "AUTH_FAIL, FAILED", "ATTEMPTS_EXPIRED, FAILED",
            "CANCELED, CANCELED", "REVERSED, CANCELED", "REFUNDED, CANCELED", "PARTIAL_REFUNDED, CANCELED",
            "NEW, NULL", "FORM_SHOWED, NULL", "AUTHORIZED, NULL", "3DS_CHECKING, NULL", "SOMETHING_NEW, NULL"})
    void mapsBankStatuses(String bankStatus, PaymentStatus expected) {
        assertEquals(expected, OrderPaymentService.toPaymentStatus(bankStatus));
    }

    @Test
    void nullBankStatusChangesNothing() {
        assertNull(OrderPaymentService.toPaymentStatus(null));
    }

    @ParameterizedTest
    @CsvSource({
            "AWAITING_PAYMENT, PAID, true", "AWAITING_PAYMENT, FAILED, true", "AWAITING_PAYMENT, CANCELED, true",
            "FAILED, PAID, true", "FAILED, CANCELED, true", "FAILED, FAILED, false",
            "PAID, CANCELED, true", "PAID, FAILED, false", "PAID, PAID, false", "PAID, AWAITING_PAYMENT, false",
            "CANCELED, PAID, false", "CANCELED, FAILED, false", "CANCELED, CANCELED, false"})
    void transitionTable(PaymentStatus from, PaymentStatus to, boolean allowed) {
        assertEquals(allowed, OrderPaymentService.canMove(from, to));
    }

    // ── applyTinkoffStatus ──

    @Test
    void awaitingBecomesPaidAndNotifiesAfterCommit() {
        assertEquals(PaymentStatus.PAID, service.applyTinkoffStatus(order, "CONFIRMED"));

        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertEquals(NOW, order.getPaidAt());
        InOrder inOrder = inOrder(transactionManager, notifications);
        inOrder.verify(transactionManager).commit(any(TransactionStatus.class));
        inOrder.verify(notifications).onOrderPaid(ORDER_ID);
    }

    @Test
    void repeatedConfirmedIsIdempotent() {
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaidAt(EARLIER);

        assertEquals(PaymentStatus.PAID, service.applyTinkoffStatus(order, "CONFIRMED"));

        assertEquals(EARLIER, order.getPaidAt());
        verify(notifications, never()).onOrderPaid(anyLong());
    }

    @Test
    void paidIsNeverDowngradedToFailed() {
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaidAt(EARLIER);

        assertEquals(PaymentStatus.PAID, service.applyTinkoffStatus(order, "REJECTED"));
        assertEquals(PaymentStatus.PAID, service.applyTinkoffStatus(order, "DEADLINE_EXPIRED"));
        assertEquals(PaymentStatus.PAID, service.applyTinkoffStatus(order, "AUTH_FAIL"));

        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertEquals(EARLIER, order.getPaidAt());
    }

    @Test
    void paidOrderCanBeRefunded() {
        order.setPaymentStatus(PaymentStatus.PAID);

        assertEquals(PaymentStatus.CANCELED, service.applyTinkoffStatus(order, "REFUNDED"));
        verify(notifications, never()).onOrderPaid(anyLong());
    }

    @Test
    void canceledIsFinal() {
        order.setPaymentStatus(PaymentStatus.CANCELED);

        assertEquals(PaymentStatus.CANCELED, service.applyTinkoffStatus(order, "CONFIRMED"));
        assertEquals(PaymentStatus.CANCELED, service.applyTinkoffStatus(order, "REJECTED"));
        verify(notifications, never()).onOrderPaid(anyLong());
    }

    @Test
    void lateConfirmedRevivesFailedOrder() {
        order.setPaymentStatus(PaymentStatus.FAILED);

        assertEquals(PaymentStatus.PAID, service.applyTinkoffStatus(order, "CONFIRMED"));
        assertEquals(NOW, order.getPaidAt());
        verify(notifications).onOrderPaid(ORDER_ID);
    }

    @Test
    void intermediateStatusChangesNothing() {
        assertEquals(PaymentStatus.AWAITING_PAYMENT, service.applyTinkoffStatus(order, "FORM_SHOWED"));
        assertNull(order.getPaidAt());
        verify(notifications, never()).onOrderPaid(anyLong());
    }

    @Test
    void notificationFailureDoesNotUndoPayment() {
        doThrow(new RuntimeException("NotiSend недоступен")).when(notifications).onOrderPaid(ORDER_ID);

        assertEquals(PaymentStatus.PAID, service.applyTinkoffStatus(order, "CONFIRMED"));
    }

    @Test
    void failedTransactionRollsBackAndDoesNotNotify() {
        when(repository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.applyTinkoffStatus(order, "CONFIRMED"));

        verify(transactionManager).rollback(any(TransactionStatus.class));
        verify(notifications, never()).onOrderPaid(anyLong());
    }

    // ── ЮKassa ──

    private void orderPaidViaYookassa() {
        order.setPaymentProvider(PaymentProvider.YOOKASSA);
        order.setPaymentId("2d1b-yk");
        order.getShop().setYookassaShopId("123456");
        order.getShop().setYookassaSecretKeyEnc(cipher.encrypt("live_key"));
        when(repository.findWithShopByPaymentId("2d1b-yk")).thenReturn(Optional.of(order));
    }

    private static YookassaPayment yookassaPayment(String status, String orderId, long amount, Long refunded) {
        YookassaPayment payment = new YookassaPayment();
        payment.setId("2d1b-yk");
        payment.setStatus(status);
        payment.setAmount(YookassaAmount.rub(amount));
        payment.setRefundedAmount(refunded == null ? null : YookassaAmount.rub(refunded));
        payment.setMetadata(orderId == null ? null : Map.of("order_id", orderId));
        return payment;
    }

    private static String yookassaEvent(String event, String objectJson) {
        return "{\"type\":\"notification\",\"event\":\"" + event + "\",\"object\":" + objectJson + "}";
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "succeeded, NULL, PAID", "succeeded, 0, PAID", "succeeded, 19200, CANCELED", "succeeded, 100, CANCELED",
            "canceled, NULL, FAILED", "pending, NULL, NULL", "waiting_for_capture, NULL, NULL", "NULL, NULL, NULL"})
    void mapsYookassaStatuses(String status, Long refunded, PaymentStatus expected) {
        assertEquals(expected, YookassaClient.toPaymentStatus(yookassaPayment(status, "A1B2C3", 19200, refunded)));
    }

    @Test
    void yookassaNotificationIsVerifiedThroughApiAndMarksPaid() {
        orderPaidViaYookassa();
        when(yookassaClient.getPayment(eq("2d1b-yk"), any())).thenReturn(yookassaPayment("succeeded", "A1B2C3", 19200, null));

        // Тело говорит canceled, но верим только API
        assertTrue(service.acceptYookassaNotification(
                yookassaEvent("payment.canceled", "{\"id\":\"2d1b-yk\",\"status\":\"canceled\"}")));

        ArgumentCaptor<YookassaCredentials> credentials = ArgumentCaptor.forClass(YookassaCredentials.class);
        verify(yookassaClient).getPayment(eq("2d1b-yk"), credentials.capture());
        assertEquals(new YookassaCredentials("123456", "live_key"), credentials.getValue());
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        verify(notifications).onOrderPaid(ORDER_ID);
    }

    @Test
    void yookassaRefundNotificationLooksUpPaymentIdAndCancelsOrder() {
        orderPaidViaYookassa();
        order.setPaymentStatus(PaymentStatus.PAID);
        when(yookassaClient.getPayment(eq("2d1b-yk"), any()))
                .thenReturn(yookassaPayment("succeeded", "A1B2C3", 19200, 19200L));

        assertTrue(service.acceptYookassaNotification(
                yookassaEvent("refund.succeeded", "{\"id\":\"refund-1\",\"payment_id\":\"2d1b-yk\"}")));

        assertEquals(PaymentStatus.CANCELED, order.getPaymentStatus());
    }

    @Test
    void yookassaNotificationForForeignPaymentIsAcknowledgedAndIgnored() {
        when(repository.findWithShopByPaymentId("other")).thenReturn(Optional.empty());

        assertTrue(service.acceptYookassaNotification(yookassaEvent("payment.succeeded", "{\"id\":\"other\"}")));
        assertTrue(service.acceptYookassaNotification("not json"));

        verify(yookassaClient, never()).getPayment(anyString(), any());
        assertEquals(PaymentStatus.AWAITING_PAYMENT, order.getPaymentStatus());
    }

    @Test
    void yookassaNotificationWithMismatchedOrderOrAmountChangesNothing() {
        orderPaidViaYookassa();
        when(yookassaClient.getPayment(eq("2d1b-yk"), any())).thenReturn(yookassaPayment("succeeded", "ZZZZZZ", 19200, null));
        assertTrue(service.acceptYookassaNotification(yookassaEvent("payment.succeeded", "{\"id\":\"2d1b-yk\"}")));

        when(yookassaClient.getPayment(eq("2d1b-yk"), any())).thenReturn(yookassaPayment("succeeded", "A1B2C3", 100, null));
        assertTrue(service.acceptYookassaNotification(yookassaEvent("payment.succeeded", "{\"id\":\"2d1b-yk\"}")));

        assertEquals(PaymentStatus.AWAITING_PAYMENT, order.getPaymentStatus());
        verify(notifications, never()).onOrderPaid(anyLong());
    }

    @Test
    void yookassaUnavailableAsksForRetry() {
        orderPaidViaYookassa();
        when(yookassaClient.getPayment(anyString(), any())).thenThrow(new YookassaClient.YookassaException("ЮKassa не отвечает"));

        assertFalse(service.acceptYookassaNotification(yookassaEvent("payment.succeeded", "{\"id\":\"2d1b-yk\"}")));
        assertEquals(PaymentStatus.AWAITING_PAYMENT, order.getPaymentStatus());
    }

    @Test
    void checkWithBankAsksProviderWhereOrderWasCreated() {
        orderPaidViaYookassa();
        // Магазин с тех пор переключился на Т-Банк — платёж всё равно в ЮKassa
        order.getShop().setPaymentProvider(PaymentProvider.TINKOFF);
        when(yookassaClient.getPayment(eq("2d1b-yk"), any())).thenReturn(yookassaPayment("canceled", "A1B2C3", 19200, null));

        assertEquals(PaymentStatus.FAILED, service.checkWithBank(order, NOW));

        verify(tinkoffClient, never()).getState(anyString(), any());
        assertEquals(NOW, order.getPaymentCheckedAt());
    }

    // ── Нотификации (вебхук) ──

    private String signed(Map<String, Object> fields) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(fields);
        body.put("Token", tokenService.sign(TinkoffTokenService.scalarRootParams(body),
                TinkoffTokenServiceTest.DOC_PASSWORD));
        return objectMapper.writeValueAsString(body);
    }

    private static Map<String, Object> notification(String status, long amount) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("TerminalKey", TinkoffTokenServiceTest.DOC_TERMINAL);
        fields.put("OrderId", "A1B2C3");
        fields.put("Success", true);
        fields.put("Status", status);
        fields.put("PaymentId", 13660);
        fields.put("ErrorCode", "0");
        fields.put("Amount", amount);
        fields.put("Receipt", Map.of("Email", "buyer@example.com"));
        return fields;
    }

    @Test
    void validNotificationMarksOrderPaid() {
        // Тело с известной подписью (посчитана независимо), с Receipt/DATA и null-полем
        String body = TinkoffTokenServiceTest.notificationJson(TinkoffTokenServiceTest.NOTIFICATION_TOKEN);

        assertTrue(service.acceptTinkoffNotification(body));

        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        verify(notifications).onOrderPaid(ORDER_ID);
    }

    @Test
    void wrongTokenIsRejected() {
        String body = TinkoffTokenServiceTest.notificationJson("0".repeat(64));

        assertFalse(service.acceptTinkoffNotification(body));

        assertEquals(PaymentStatus.AWAITING_PAYMENT, order.getPaymentStatus());
        verify(repository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void notificationSignedWithAnotherPasswordIsRejected() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(notification("CONFIRMED", 19200));
        body.put("Token", tokenService.sign(TinkoffTokenService.scalarRootParams(body), "чужой-пароль"));

        assertFalse(service.acceptTinkoffNotification(objectMapper.writeValueAsString(body)));
        assertEquals(PaymentStatus.AWAITING_PAYMENT, order.getPaymentStatus());
    }

    @Test
    void wrongTerminalIsRejected() throws Exception {
        Map<String, Object> fields = notification("CONFIRMED", 19200);
        fields.put("TerminalKey", "OtherShopTerminal");

        assertFalse(service.acceptTinkoffNotification(signed(fields)));

        assertEquals(PaymentStatus.AWAITING_PAYMENT, order.getPaymentStatus());
        verify(notifications, never()).onOrderPaid(anyLong());
    }

    @Test
    void amountMismatchIsRejected() throws Exception {
        assertFalse(service.acceptTinkoffNotification(signed(notification("CONFIRMED", 100))));

        assertEquals(PaymentStatus.AWAITING_PAYMENT, order.getPaymentStatus());
        verify(repository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void foreignPaymentIdIsRejected() throws Exception {
        Map<String, Object> fields = notification("CONFIRMED", 19200);
        fields.put("PaymentId", 99999);

        assertFalse(service.acceptTinkoffNotification(signed(fields)));
        assertEquals(PaymentStatus.AWAITING_PAYMENT, order.getPaymentStatus());
    }

    @Test
    void unknownOrderIsRejected() throws Exception {
        Map<String, Object> fields = notification("CONFIRMED", 19200);
        fields.put("OrderId", "ZZZZZZ");
        when(repository.findWithShopByPublicId("ZZZZZZ")).thenReturn(Optional.empty());

        assertFalse(service.acceptTinkoffNotification(signed(fields)));
    }

    @Test
    void malformedBodiesAreRejected() {
        assertFalse(service.acceptTinkoffNotification("not json"));
        assertFalse(service.acceptTinkoffNotification("[1,2]"));
        assertFalse(service.acceptTinkoffNotification("{}"));
        assertFalse(service.acceptTinkoffNotification("null"));
        verify(repository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void refundWithChangedAmountIsAccepted() throws Exception {
        order.setPaymentStatus(PaymentStatus.PAID);

        assertTrue(service.acceptTinkoffNotification(signed(notification("REFUNDED", 0))));

        assertEquals(PaymentStatus.CANCELED, order.getPaymentStatus());
    }

    @Test
    void rejectedNotificationMarksFailed() throws Exception {
        Map<String, Object> fields = notification("REJECTED", 19200);
        fields.put("Success", false);
        fields.put("ErrorCode", "1051");

        assertTrue(service.acceptTinkoffNotification(signed(fields)));
        assertEquals(PaymentStatus.FAILED, order.getPaymentStatus());
    }

    @Test
    void intermediateNotificationIsAcceptedWithoutChange() throws Exception {
        assertTrue(service.acceptTinkoffNotification(signed(notification("AUTHORIZED", 19200))));

        assertEquals(PaymentStatus.AWAITING_PAYMENT, order.getPaymentStatus());
        verify(notifications, never()).onOrderPaid(anyLong());
    }

    @Test
    void duplicateConfirmedNotificationSendsLettersOnce() throws Exception {
        String body = signed(notification("CONFIRMED", 19200));

        assertTrue(service.acceptTinkoffNotification(body));
        assertTrue(service.acceptTinkoffNotification(body));

        verify(notifications).onOrderPaid(ORDER_ID);
    }

    // ── GetState ──

    @Test
    void checkWithBankUsesShopTerminalAndAppliesState() {
        TinkoffGetStateResponse state = new TinkoffGetStateResponse();
        state.setSuccess(true);
        state.setStatus("CONFIRMED");
        when(tinkoffClient.getState(eq("13660"), any(TinkoffCredentials.class))).thenReturn(state);

        assertEquals(PaymentStatus.PAID, service.checkWithBank(order, NOW));

        ArgumentCaptor<TinkoffCredentials> credentials = ArgumentCaptor.forClass(TinkoffCredentials.class);
        verify(tinkoffClient).getState(eq("13660"), credentials.capture());
        assertEquals(TinkoffTokenServiceTest.DOC_TERMINAL, credentials.getValue().terminalKey());
        assertEquals(TinkoffTokenServiceTest.DOC_PASSWORD, credentials.getValue().password());
        assertEquals(NOW, order.getPaymentCheckedAt());
        verify(notifications).onOrderPaid(ORDER_ID);
    }

    @Test
    void checkWithBankStillPendingOnlyMarksChecked() {
        TinkoffGetStateResponse state = new TinkoffGetStateResponse();
        state.setSuccess(true);
        state.setStatus("FORM_SHOWED");
        when(tinkoffClient.getState(eq("13660"), any(TinkoffCredentials.class))).thenReturn(state);

        assertEquals(PaymentStatus.AWAITING_PAYMENT, service.checkWithBank(order, NOW));
        assertEquals(NOW, order.getPaymentCheckedAt());
    }

    @Test
    void bankErrorIsSwallowedButCheckIsRecorded() {
        when(tinkoffClient.getState(anyString(), any(TinkoffCredentials.class)))
                .thenThrow(new TinkoffClient.TinkoffException("Т-Банк не отвечает"));

        assertNull(service.checkWithBank(order, NOW));

        verify(repository).markPaymentChecked(ORDER_ID, NOW);
        verify(repository, never()).findByIdForUpdate(anyLong());
        assertEquals(PaymentStatus.AWAITING_PAYMENT, order.getPaymentStatus());
    }

    @Test
    void shopWithoutTerminalIsTreatedAsBankError() {
        order.getShop().setTinkoffPasswordEnc(null);

        assertNull(service.checkWithBank(order, NOW));

        verify(tinkoffClient, never()).getState(anyString(), any());
        verify(repository).markPaymentChecked(ORDER_ID, NOW);
    }

    // ── Массовые переводы в FAILED ──

    @Test
    void attemptsExpiredFailsAwaitingOrderRightAway() {
        // Конечный статус Т-Банка: без маппинга заказ сутки висел бы AWAITING_PAYMENT под поллером
        assertEquals(PaymentStatus.FAILED, service.applyTinkoffStatus(order, "ATTEMPTS_EXPIRED"));
        assertEquals(PaymentStatus.FAILED, order.getPaymentStatus());
        verify(notifications, never()).onOrderPaid(any());
    }

    @Test
    void claimBankCheckIsAtomicConditionalUpdate() {
        when(repository.claimPaymentCheck(ORDER_ID, NOW, NOW.minus(Duration.ofSeconds(5)))).thenReturn(1, 0);

        assertTrue(service.claimBankCheck(ORDER_ID, NOW, Duration.ofSeconds(5)));
        // Второй одновременный запрос уже не обновил строку — в банк не идёт
        assertFalse(service.claimBankCheck(ORDER_ID, NOW, Duration.ofSeconds(5)));
    }

    @Test
    void staleAndInitFailuresGoThroughConditionalUpdates() {
        when(repository.failAwaitingCreatedBefore(EARLIER, NOW)).thenReturn(3);

        assertEquals(3, service.failStaleAwaiting(EARLIER));
        service.markInitFailed(ORDER_ID);

        verify(repository).failIfAwaiting(ORDER_ID, NOW);
    }
}
