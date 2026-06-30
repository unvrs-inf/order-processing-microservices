package com.example.orders.common.events;

import java.util.UUID;

/**
 * Подтверждение резерва после оплаты заказа.
 * @param orderId идентификатор заказа и ключ сообщения Kafka
 */
public record CommitStock(UUID orderId) {
}
