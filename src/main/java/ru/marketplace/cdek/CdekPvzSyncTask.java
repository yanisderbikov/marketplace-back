package ru.marketplace.cdek;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ежесуточное обновление справочника ПВЗ СДЭК ночью по Москве, когда на витринах меньше всего покупателей. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CdekPvzSyncTask {

    private final CdekPvzService cdekPvzService;

    @Scheduled(
            cron = "0 30 4 * * *",
            zone = "Europe/Moscow"
    )
    public void process() {
        try {
            cdekPvzService.refresh();
        } catch (Exception e) {
            log.error("Шедулер обновления ПВЗ СДЭК не сработал", e);
        }
    }
}
