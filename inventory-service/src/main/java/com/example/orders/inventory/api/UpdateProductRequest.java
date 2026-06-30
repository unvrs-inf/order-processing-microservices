package com.example.orders.inventory.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Тело {@code PUT /api/inventory/products/{id}}. Сохраняет артикул и остатки.
 * @param name название
 * @param description описание
 * @param price цена
 */
public record UpdateProductRequest(
        @NotBlank(message = "must be provided")
        @Size(max = 200, message = "must be at most 200 characters")
        String name,

        @Size(max = 4000, message = "must be at most 4000 characters")
        String description,

        @NotNull(message = "must be provided")
        @DecimalMin(value = "0.00", message = "must not be negative")
        @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimals")
        BigDecimal price) {
}
