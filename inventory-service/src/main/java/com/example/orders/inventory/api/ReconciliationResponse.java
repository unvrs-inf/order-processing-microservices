package com.example.orders.inventory.api;

import com.example.orders.inventory.service.ReconciliationOutcome;
import java.util.List;
import java.util.UUID;

/**
 * Результаты сверки в порядке запроса, по одному на уникальный заказ.
 * @param entries результаты сверки по заказам
 */
public record ReconciliationResponse(List<Entry> entries) {

    /**
     * @param orderId проверенный заказ
     * @param outcome результат сверки
     */
    public record Entry(UUID orderId, ReconciliationOutcome outcome) {
    }
}
