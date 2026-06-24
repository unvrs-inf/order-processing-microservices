package com.example.orders.order.api;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Представление заказа для API. Версия записи и ключ идемпотентности остаются внутри сервиса.
 * @param id идентификатор записи
 * @param orderNumber номер заказа для клиента
 * @param userId идентификатор пользователя
 * @param status статус
 * @param totalAmount общая стоимость заказа
 * @param currency код валюты ISO 4217
 * @param failureReason причина отказа
 * @param createdAt время создания
 * @param updatedAt время последнего изменения
 * @param items строки заказа
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
        UUID id,
        String orderNumber,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<OrderItemResponse> items) {

    /**
     * @param order сохранённый заказ с загруженными строками
     * @return ответ для клиента
     */
    public static OrderResponse of(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getItems().stream().map(OrderItemResponse::of).toList());
    }
}
