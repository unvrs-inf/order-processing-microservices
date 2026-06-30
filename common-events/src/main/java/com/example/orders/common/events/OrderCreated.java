package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Заказ принят в состоянии {@code PENDING}. */
public record OrderCreated(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        BigDecimal totalAmount,
        String currency,
        List<OrderItem> items) {
}
