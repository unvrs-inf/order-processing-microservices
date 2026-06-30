package com.example.orders.common.events;

import java.util.List;
import java.util.UUID;

/** Команда освобождения складского резерва. */
public record ReleaseStock(
        UUID orderId,
        UUID reservationId,
        String reason,
        List<OrderItem> items) {
}
