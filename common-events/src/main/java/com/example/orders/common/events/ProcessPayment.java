package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Команда оплаты заказа после резервирования остатков.
 * @param orderId идентификатор заказа и ключ Kafka
 * @param customerId идентификатор клиента для проверки доступа к платежу
 * @param amount сумма
 * @param currency код ISO 4217
 */
public record ProcessPayment(
        UUID orderId,
        UUID customerId,
        BigDecimal amount,
        String currency) {
}
