package ru.marketplace.cdek;

import ru.marketplace.cdek.dto.CdekLocation;
import ru.marketplace.cdek.dto.CdekPackage;
import ru.marketplace.cdek.dto.CdekTariffQuote;

import java.util.List;

/** Калькулятор тарифа СДЭК по ключам магазина. Ошибку расчёта сообщает через {@link CdekCalculationException}. */
public interface CdekCalculatorGateway {

    CdekTariffQuote calculate(CdekCredentials credentials, int tariffCode, CdekLocation from, CdekLocation to,
                              List<CdekPackage> packages);
}
