package com.example.orders.inventory.api;

import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.StockReservation;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Одна строка резерва в ответе {@code GET /api/inventory/reservations}.
 * @param id идентификатор записи
 * @param orderId идентификатор заказа
 * @param productId идентификатор товара
 * @param sku артикул товара
 * @param quantity количество единиц
 * @param status статус
 * @param createdAt время создания
 * @param updatedAt время последнего изменения
 */
public record ReservationResponse(
        UUID id,
        UUID orderId,
        UUID productId,
        String sku,
        int quantity,
        ReservationStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param reservation сохранённая строка резерва
     * @param sku артикул товара
     * @return представление резерва для API
     */
    public static ReservationResponse of(StockReservation reservation, String sku) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getOrderId(),
                reservation.getProductId(),
                sku,
                reservation.getQuantity(),
                reservation.getStatus(),
                reservation.getCreatedAt(),
                reservation.getUpdatedAt());
    }
}
