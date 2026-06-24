package com.example.orders.inventory.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Тело запроса {@code POST /api/inventory/reservations/reconcile}. Оператор явно указывает заказы.
 * @param orderIds идентификаторы заказов
 * @param reason причина операции
 */
public record ReconciliationRequest(
        @NotEmpty @Size(max = 200) List<@NotNull UUID> orderIds,
        @Size(max = 500) String reason) {

    /** @return уникальные идентификаторы в порядке первого появления */
    public List<UUID> distinctOrderIds() {
        return orderIds.stream().distinct().toList();
    }
}
