package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Платёж по заказу прошёл. */
public record PaymentCompleted(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency) {
}
