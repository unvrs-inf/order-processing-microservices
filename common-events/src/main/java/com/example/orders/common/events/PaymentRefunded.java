package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Платёж возвращён после компенсации.
 * @param orderId идентификатор заказа
 * @param paymentId идентификатор платежа
 * @param amount сумма платежа
 * @param currency код валюты ISO 4217
 * @param reason причина операции
 */
public record PaymentRefunded(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String reason) {
}
