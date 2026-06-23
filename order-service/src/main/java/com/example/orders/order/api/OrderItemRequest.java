package com.example.orders.order.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Клиент указывает товар и количество. Цена загружается на сервере.
 * @param productId идентификатор товара
 * @param quantity количество единиц
 */
public record OrderItemRequest(

        @NotNull(message = "must be provided")
        UUID productId,

        @Min(value = 1, message = "must be at least 1")
        @Max(value = MAX_QUANTITY, message = "must be at most " + MAX_QUANTITY)
        int quantity) {

    /** Ограничение количества единиц одного товара в заказе. */
    public static final int MAX_QUANTITY = 100;
}
