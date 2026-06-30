package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Команда компенсации завершённого платежа после отмены заказа. */
public record RefundPayment(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String reason) {
}
