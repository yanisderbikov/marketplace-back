package ru.marketplace.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.marketplace.model.Order;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.repository.OrderRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Провайдер не присылает нотификацию, когда покупатель просто ушёл со страницы оплаты, а вебхук мог
 * потеряться (сеть, деплой) — без этого поллера такие заказы навсегда остались бы AWAITING_PAYMENT,
 * а оплаченный без вебхука заказ мерчант бы не увидел. Раз в {@code payment.pending-check.interval-ms}
 * спрашиваем у провайдера заказа (GetState Т-Банка или платёж ЮKassa, ключами магазина) статус свежих неоплаченных заказов и применяем
 * его тем же путём, что и вебхук. Заказы старше суток считаем брошенными: ссылка на оплату живёт 15 минут.
 * (Перенос anyforms PendingPaymentCheckServiceImpl + PendingPaymentCheckTask.)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingPaymentChecker {

    /** Старше — не спрашиваем банк, а переводим в FAILED. */
    static final Duration LOOKBACK = Duration.ofHours(24);
    /** Один заказ спрашиваем у банка не чаще раза в минуту (страница успеха тоже отмечает проверки). */
    static final Duration RECHECK_AFTER = Duration.ofSeconds(60);

    private final OrderRepository orderRepository;
    private final OrderPaymentService orderPaymentService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${payment.pending-check.interval-ms}")
    public void tick() {
        try {
            check();
        } catch (Exception e) {
            log.error("Проверка неоплаченных заказов не сработала", e);
        }
    }

    /** @return сколько заказов сменили статус оплаты по ответу банка */
    int check() {
        Instant now = clock.instant();

        int expired = orderPaymentService.failStaleAwaiting(now.minus(LOOKBACK));
        if (expired > 0) {
            log.info("Заказов без оплаты дольше {} ч переведено в FAILED: {}", LOOKBACK.toHours(), expired);
        }

        List<Order> candidates = orderRepository.findAwaitingForCheck(now.minus(LOOKBACK), now.minus(RECHECK_AFTER));
        int changed = 0;
        for (Order order : candidates) {
            try {
                // Тот же атомарный «застолбить», что и у страницы успеха: если её запрос уже спрашивает банк
                // по этому заказу, второй GetState параллельно не нужен
                if (!orderPaymentService.claimBankCheck(order.getId(), now, RECHECK_AFTER)) {
                    continue;
                }
                PaymentStatus status = orderPaymentService.checkWithBank(order, now);
                if (status != null && status != PaymentStatus.AWAITING_PAYMENT) {
                    changed++;
                }
            } catch (Exception e) {
                // Один сломанный заказ не останавливает проверку остальных
                log.error("Не удалось проверить оплату заказа {} (платёж {})",
                        order.getPublicId(), order.getPaymentId(), e);
            }
        }
        if (!candidates.isEmpty()) {
            log.info("Проверка неоплаченных заказов: кандидатов {}, сменили статус {}", candidates.size(), changed);
        }
        return changed;
    }
}
