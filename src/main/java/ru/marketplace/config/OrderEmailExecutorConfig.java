package ru.marketplace.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Отдельный пул для писем об оплаченном заказе. Письма шлются уже после коммита PAID, но раньше — в том же
 * потоке, что вебхук Т-Банка или запрос страницы успеха: до трёх писем по 20 с таймаута NotiSend, а Т-Банк ждёт
 * ответа на нотификацию 10 с и при задержке считает её неуспешной и повторяет раз в час.
 * Размеры — константы: это не настройка окружения, а защита потоков запросов от медленной почты.
 */
@Configuration
public class OrderEmailExecutorConfig {

    public static final String ORDER_EMAIL_EXECUTOR = "orderEmailExecutor";

    /** Два потока: письма редкие, NotiSend держит запрос до 20 с — пары хватает, чтобы очередь не копилась */
    private static final int THREADS = 2;
    /** Очередь с запасом на всплеск оплат; переполнение — письма заказа только логируются (как ошибка NotiSend) */
    private static final int QUEUE_CAPACITY = 500;
    /** При остановке (деплой) дожидаемся уже взятых писем, но не дольше — иначе редеплой зависнет на сломанной почте */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    @Bean(name = ORDER_EMAIL_EXECUTOR)
    public ThreadPoolTaskExecutor orderEmailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("order-email-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        return executor;
    }
}
