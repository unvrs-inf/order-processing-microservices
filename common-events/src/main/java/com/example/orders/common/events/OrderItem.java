package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Строка заказа, передаваемая в событиях об остатках. */
public record OrderItem(
        UUID productId,
        String sku,
        int quantity,
        BigDecimal unitPrice) {
}
