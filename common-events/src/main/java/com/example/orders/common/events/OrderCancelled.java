package com.example.orders.common.events;

import java.util.UUID;

/** Конечное событие после отмены заказа. */
public record OrderCancelled(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String reason) {
}
