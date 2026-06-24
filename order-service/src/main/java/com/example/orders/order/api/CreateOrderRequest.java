package com.example.orders.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Тело запроса {@code POST /api/orders}.
 * @param items строки заказа
 */
public record CreateOrderRequest(

        @NotEmpty(message = "must contain at least one item")
        @Size(max = MAX_ITEMS, message = "must contain at most " + MAX_ITEMS + " items")
        @Valid
        List<OrderItemRequest> items) {

    /** Максимум отдельных строк, ограничивающий число запросов к каталогу. */
    public static final int MAX_ITEMS = 20;
}
