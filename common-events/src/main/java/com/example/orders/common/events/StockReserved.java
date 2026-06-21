package com.example.orders.common.events;

import java.util.List;
import java.util.UUID;

/** Склад зарезервировал все позиции заказа. */
public record StockReserved(
        UUID orderId,
        UUID reservationId,
        List<OrderItem> items) {
}
