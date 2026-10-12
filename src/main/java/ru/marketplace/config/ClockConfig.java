package ru.marketplace.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Часы приложения — бином, а не Instant.now() по коду: так сроки кода входа, окна оплаты
 * и границы дней аналитики проверяются в тестах на фиксированном времени.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
