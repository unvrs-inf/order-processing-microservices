package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Платёж отклонён. Saga запускает компенсацию. */
public record PaymentFailed(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String reason) {
}
