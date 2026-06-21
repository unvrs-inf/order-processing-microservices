package com.example.orders.common.events;

import java.util.List;
import java.util.UUID;

/** Товар не удалось зарезервировать. Заказ отклоняется. */
public record StockRejected(
        UUID orderId,
        String reason,
        List<String> unavailableSkus) {
}
