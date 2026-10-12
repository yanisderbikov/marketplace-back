package ru.marketplace.order;

import ru.marketplace.common.PageDTO;
import ru.marketplace.order.dto.FulfillmentUpdateRequestDTO;
import ru.marketplace.order.dto.MerchantOrderDTO;

public interface MerchantOrderService {

    /**
     * Заказы магазина текущего мерчанта, новые сверху.
     * payment — PAID | AWAITING_PAYMENT | FAILED | CANCELED | ALL, fulfillment — NEW | SHIPPED | DONE | CANCELLED | ALL
     * (иначе 400); size больше 100 урезается до 100.
     */
    PageDTO<MerchantOrderDTO> list(String payment, String fulfillment, int page, int size);

    /** Статус исполнения и трек-номер: только свой (иначе 404) и только оплаченный (иначе 409) заказ. */
    MerchantOrderDTO updateFulfillment(String publicId, FulfillmentUpdateRequestDTO request);
}
