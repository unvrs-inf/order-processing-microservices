package com.example.orders.inventory.api;

import jakarta.validation.constraints.NotNull;

/**
 * Тело {@code PATCH /api/inventory/products/{id}/stock}. Указывает изменение остатка относительно текущего значения.
 * @param delta изменение количества
 */
public record StockAdjustmentRequest(@NotNull(message = "must be provided") Integer delta) {
}
