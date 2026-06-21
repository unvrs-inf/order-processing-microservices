package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Подтверждение заказа после резервирования и оплаты. */
public record OrderConfirmed(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        BigDecimal totalAmount,
        String currency) {
}
