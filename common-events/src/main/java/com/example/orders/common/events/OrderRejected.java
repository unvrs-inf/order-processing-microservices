package com.example.orders.common.events;

import java.util.UUID;

/**
 * Отклонение заказа до операций, требующих компенсации.
 * @param orderId идентификатор заказа
 * @param orderNumber номер заказа для клиента
 * @param customerId идентификатор покупателя
 * @param reason причина операции
 */
public record OrderRejected(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String reason) {
}
